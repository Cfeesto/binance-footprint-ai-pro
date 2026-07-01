"""
signal_server.py — real-time trading signal server.

Data flow:
  Binance Futures kline WS → candle aggregator → features → ONNX ensemble
  → paper trade engine → (optional) live exchange execution
  → WebSocket broadcast → Android app

Exchanges supported for live trading:
  binance  — Binance Futures (HMAC-SHA256)
  hyperliquid — Hyperliquid (EIP-712 via HL Python SDK)
  bybit    — Bybit V5 Futures (HMAC-SHA256)
  okx      — OKX Futures (HMAC-SHA256 + passphrase)

Polymarket:
  GET /polymarket/prices — current YES prices for hot markets

Paper→Live promotion:
  POST /promote?exchange=binance  — unlock live trading
  Requires: win_rate >= 0.52, trades >= 50
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import os
import pickle
import time
import urllib.parse
from collections import deque
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Any, Optional

import numpy as np
import onnxruntime as ort
import requests
import websockets
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
import uvicorn

# ── Paths ─────────────────────────────────────────────────────────────────────

HERE      = Path(__file__).parent
BACKTEST  = HERE.parent / "backtest"
RESULTS   = BACKTEST / "results"

# ── Config from env ───────────────────────────────────────────────────────────

SYMBOL       = os.environ.get("SYMBOL",       "ETHUSDT")
INTERVAL     = os.environ.get("INTERVAL",     "5m")        # 5m candles
CRYEXC_WS    = os.environ.get("CRYEXC_WS",    "")          # optional jose-donato backend

BINANCE_KEY  = os.environ.get("BINANCE_API_KEY",    "")
BINANCE_SEC  = os.environ.get("BINANCE_API_SECRET",  "")

HL_PRIVATE   = os.environ.get("HL_PRIVATE_KEY",   "")      # Hyperliquid EVM private key
HL_ACCOUNT   = os.environ.get("HL_ACCOUNT_ADDR",  "")

BYBIT_KEY    = os.environ.get("BYBIT_API_KEY",    "")
BYBIT_SEC    = os.environ.get("BYBIT_API_SECRET",  "")

OKX_KEY      = os.environ.get("OKX_API_KEY",      "")
OKX_SEC      = os.environ.get("OKX_API_SECRET",    "")
OKX_PASS     = os.environ.get("OKX_PASSPHRASE",    "")

PAPER_START  = float(os.environ.get("PAPER_START_BALANCE", "10000"))
RISK_PCT     = float(os.environ.get("RISK_PCT",   "0.02"))
PROMOTE_WR   = float(os.environ.get("PROMOTE_WIN_RATE",  "0.52"))
PROMOTE_MIN  = int(os.environ.get("PROMOTE_MIN_TRADES", "50"))

# ── Feature columns (must match features.py order) ───────────────────────────

FEATURE_NAMES_PATH = RESULTS / "feature_names.json"
if FEATURE_NAMES_PATH.exists():
    FEATURE_COLS = json.loads(FEATURE_NAMES_PATH.read_text())
else:
    # fallback — same order as features.py
    FEATURE_COLS = [
        "buy_ratio","sell_ratio","delta_ratio","cvd_norm","buy_vol_norm","sell_vol_norm",
        "liq_buy_norm","liq_sell_norm","liq_net","liq_ratio",
        "stacked_bid","stacked_ask","imbalance_top5",
        "vwap_dev","atr_norm","rsi14_norm","wt1_norm","wt2_norm","cci20_norm","adx14_norm",
        "macd_norm","macd_sig_norm","bb_pos","bb_wid","ema9_slope","ema20_slope",
        "ema50_slope","ema9_20_cross","ema20_50_cross","vol_spike_delta",
        "price_acc","body_ratio","high_low_range","range_pos","upper_wick","lower_wick",
        "mom5","mom10","mom20","ret1",
    ]

THRESH_PATH = RESULTS / "thresholds.json"
_thresholds = json.loads(THRESH_PATH.read_text()) if THRESH_PATH.exists() else {}
LONG_THRESH  = _thresholds.get("long_thresh",  0.64)
SHORT_THRESH = _thresholds.get("short_thresh", 0.25)
WEIGHTS      = _thresholds.get("weights", {"lorentzian":0.35,"catboost":0.30,"xgboost":0.25,"rf":0.10})

# ── MTF scanner config ────────────────────────────────────────────────────────

TIMEFRAMES   = ["1m", "5m", "15m", "1h", "4h"]
SCAN_SYMBOLS = [
    "BTCUSDT","ETHUSDT","BNBUSDT","SOLUSDT","XRPUSDT",
    "ADAUSDT","DOGEUSDT","AVAXUSDT","DOTUSDT","LINKUSDT",
    "LTCUSDT","UNIUSDT","AAVEUSDT","ATOMUSDT","NEARUSDT",
    "MATICUSDT","APTUSDT","SUIUSDT","ARBUSDT","OPUSDT",
]
scanner_state: dict[str, dict] = {}
_scanner_lock = asyncio.Lock()

# ── ONNX models ───────────────────────────────────────────────────────────────

def _load_ort(name: str) -> Optional[ort.InferenceSession]:
    p = RESULTS / f"{name}.onnx"
    if not p.exists():
        print(f"[warn] {p} not found — {name} disabled")
        return None
    return ort.InferenceSession(str(p), providers=["CPUExecutionProvider"])

cat_session = _load_ort("catboost")
xgb_session = _load_ort("xgboost")
rf_session  = _load_ort("rf")

# Lorentzian — pickle
_lor_path = RESULTS / "lorentzian.bin"
if _lor_path.exists():
    import sys; sys.path.insert(0, str(BACKTEST))
    with open(_lor_path, "rb") as _f:
        lorentzian_clf = pickle.load(_f)
    print("[signal] Lorentzian loaded")
else:
    lorentzian_clf = None
    print("[warn] lorentzian.bin not found")


def _ort_predict_proba(session: ort.InferenceSession, X: np.ndarray) -> float:
    """Run ONNX session, return prob of class=1."""
    inp_name = session.get_inputs()[0].name
    out_name = session.get_outputs()[0].name
    result   = session.run([out_name], {inp_name: X})
    out = result[0]
    # CatBoost: dict list; XGB/RF: array [[p0,p1],...]
    if isinstance(out, (list, np.ndarray)):
        arr = np.array(out)
        if arr.ndim == 2:
            return float(arr[0, 1])
        if arr.ndim == 1:
            return float(arr[0])
    return 0.5


def ensemble_predict(features: np.ndarray) -> tuple[float, str]:
    """
    Returns (probability, signal) where signal ∈ {LONG, SHORT, NONE}.
    probability: ensemble prob of bullish next candle.
    """
    X = features.reshape(1, -1).astype(np.float32)
    probs = {}

    if cat_session:
        probs["catboost"] = _ort_predict_proba(cat_session, X)
    if xgb_session:
        probs["xgboost"]  = _ort_predict_proba(xgb_session, X)
    if rf_session:
        probs["rf"]       = _ort_predict_proba(rf_session, X)

    total_w = 0.0
    weighted = 0.0
    for name, p in probs.items():
        w = WEIGHTS.get(name, 0.0)
        weighted += p * w
        total_w  += w

    if lorentzian_clf is not None:
        try:
            lor_p = float(lorentzian_clf.predict_proba(X)[0, 1])
            w = WEIGHTS.get("lorentzian", 0.0)
            weighted += lor_p * w
            total_w  += w
        except Exception:
            pass

    prob = weighted / total_w if total_w > 0 else 0.5

    if prob >= LONG_THRESH:
        signal = "LONG"
    elif prob <= SHORT_THRESH:
        signal = "SHORT"
    else:
        signal = "NONE"

    return prob, signal


# ── Candle model ──────────────────────────────────────────────────────────────

@dataclass
class Candle:
    open_time: int
    open: float
    high: float
    low:  float
    close: float
    volume: float
    buy_vol:  float = 0.0
    sell_vol: float = 0.0
    liq_buy:  float = 0.0
    liq_sell: float = 0.0
    vwap: float = 0.0
    # CVD accumulated across session
    cvd: float = 0.0

    @property
    def delta(self):
        return self.buy_vol - self.sell_vol


# ── Feature builder (simplified real-time version) ────────────────────────────

def build_realtime_features(candles: list[Candle]) -> Optional[np.ndarray]:
    """
    Build feature vector from recent candles.
    Requires at least 50 candles for indicators.
    """
    if len(candles) < 50:
        return None

    closes = np.array([c.close for c in candles], dtype=np.float64)
    highs  = np.array([c.high  for c in candles], dtype=np.float64)
    lows   = np.array([c.low   for c in candles], dtype=np.float64)
    vols   = np.array([c.volume for c in candles], dtype=np.float64)
    buy_v  = np.array([c.buy_vol  for c in candles], dtype=np.float64)
    sell_v = np.array([c.sell_vol for c in candles], dtype=np.float64)
    cvds   = np.array([c.cvd      for c in candles], dtype=np.float64)
    liq_b  = np.array([c.liq_buy  for c in candles], dtype=np.float64)
    liq_s  = np.array([c.liq_sell for c in candles], dtype=np.float64)

    eps = 1e-9
    c  = candles[-1]
    v_sum = c.buy_vol + c.sell_vol + eps

    # ── Footprint ratios (last candle) ────────────────────────────────────────
    buy_ratio   = c.buy_vol  / v_sum
    sell_ratio  = c.sell_vol / v_sum
    delta_ratio = c.delta    / v_sum

    # ── CVD normalization (rolling std of last 20) ────────────────────────────
    cvd_std  = max(np.std(cvds[-20:]), eps)
    cvd_norm = c.cvd / cvd_std

    vol_std   = max(np.std(vols[-20:]), eps)
    buy_norm  = c.buy_vol  / vol_std
    sell_norm = c.sell_vol / vol_std

    liq_bsum = np.sum(liq_b[-5:]) / (vol_std + eps)
    liq_ssum = np.sum(liq_s[-5:]) / (vol_std + eps)
    liq_net  = liq_bsum - liq_ssum
    liq_ratio = liq_bsum / (liq_bsum + liq_ssum + eps)

    # Stacked imbalance — placeholder (0 without orderbook)
    stacked_bid = 0.0
    stacked_ask = 0.0
    imbalance   = 0.0

    # ── VWAP deviation ────────────────────────────────────────────────────────
    if c.vwap > 0:
        vwap_dev = (c.close - c.vwap) / (c.vwap + eps)
    else:
        vwap_dev = 0.0

    # ── ATR ───────────────────────────────────────────────────────────────────
    trs = np.maximum(highs[1:] - lows[1:],
           np.maximum(np.abs(highs[1:] - closes[:-1]),
                      np.abs(lows[1:]  - closes[:-1])))
    atr14 = np.mean(trs[-14:])
    atr_norm = atr14 / (closes[-1] + eps)

    # ── RSI 14 ────────────────────────────────────────────────────────────────
    diffs  = np.diff(closes[-15:])
    gains  = np.where(diffs > 0, diffs, 0.0)
    losses = np.where(diffs < 0, -diffs, 0.0)
    ag = np.mean(gains); al = np.mean(losses) + eps
    rsi14_norm = (ag / al) / (1 + ag / al)   # normalised 0-1

    # ── WaveTrend (simplified) ────────────────────────────────────────────────
    ap  = (highs[-10:] + lows[-10:] + closes[-10:]) / 3.0
    esa = np.mean(ap)
    d   = np.mean(np.abs(ap - esa))
    ci  = (ap[-1] - esa) / (0.015 * d + eps)
    wt1 = np.tanh(ci / 60)
    wt2 = np.mean([wt1])   # simplified
    wt1_norm = (wt1 + 1) / 2
    wt2_norm = (wt2 + 1) / 2

    # ── CCI 20 ───────────────────────────────────────────────────────────────
    tp20  = (highs[-20:] + lows[-20:] + closes[-20:]) / 3.0
    tp_m  = np.mean(tp20)
    mad   = np.mean(np.abs(tp20 - tp_m))
    cci20 = (tp20[-1] - tp_m) / (0.015 * mad + eps)
    cci20_norm = np.tanh(cci20 / 100)

    # ── ADX 14 ───────────────────────────────────────────────────────────────
    adx14_norm = 0.5  # placeholder (complex, skip for real-time simplicity)

    # ── MACD (12,26,9) ───────────────────────────────────────────────────────
    def ema(arr, n):
        k = 2/(n+1); e = arr[0]
        for x in arr[1:]: e = x*k + e*(1-k)
        return e
    fast = ema(closes[-26:], 12)
    slow = ema(closes[-26:], 26)
    macd_val = fast - slow
    macd_std = max(np.std(closes[-26:]) * 0.01, eps)
    macd_norm     = macd_val / macd_std
    macd_sig_norm = 0.0  # skip signal line

    # ── Bollinger bands ───────────────────────────────────────────────────────
    bb_mid = np.mean(closes[-20:])
    bb_std = np.std(closes[-20:]) + eps
    bb_pos = (closes[-1] - bb_mid) / (2 * bb_std)
    bb_wid = 2 * bb_std / (bb_mid + eps)

    # ── EMA slopes ───────────────────────────────────────────────────────────
    ema9  = ema(closes[-9:],  9)
    ema20 = ema(closes[-20:], 20)
    ema50 = ema(closes[-50:], 50)
    ema9_slope  = (ema9  - ema(closes[-10:-1], 9))  / (closes[-1] + eps)
    ema20_slope = (ema20 - ema(closes[-21:-1], 20)) / (closes[-1] + eps)
    ema50_slope = (ema50 - ema(closes[-51:-1], 50)) / (closes[-1] + eps) if len(closes) > 51 else 0.0
    ema9_20_cross  = 1.0 if ema9  > ema20 else -1.0
    ema20_50_cross = 1.0 if ema20 > ema50 else -1.0

    # ── Volume spike ─────────────────────────────────────────────────────────
    mean_vol = np.mean(vols[-20:]) + eps
    vol_spike_delta = (c.volume - mean_vol) / mean_vol * delta_ratio

    # ── Price action ─────────────────────────────────────────────────────────
    op  = c.open + eps
    rng = c.high - c.low + eps
    price_acc  = (closes[-1] - closes[-2]) / closes[-2] if len(closes) > 1 else 0.0
    body_ratio = (c.close - c.open) / rng
    hl_range   = rng / closes[-1]
    range_pos  = (closes[-1] - c.low) / rng
    upper_wick = (c.high - max(c.open, c.close)) / rng
    lower_wick = (min(c.open, c.close) - c.low) / rng

    # ── Momentum ─────────────────────────────────────────────────────────────
    mom5  = (closes[-1] - closes[-6])  / (closes[-6]  + eps) if len(closes) > 5  else 0.0
    mom10 = (closes[-1] - closes[-11]) / (closes[-11] + eps) if len(closes) > 10 else 0.0
    mom20 = (closes[-1] - closes[-21]) / (closes[-21] + eps) if len(closes) > 20 else 0.0
    ret1  = (closes[-1] - closes[-2])  / (closes[-2]  + eps) if len(closes) > 1  else 0.0

    feats = [
        buy_ratio, sell_ratio, delta_ratio, cvd_norm, buy_norm, sell_norm,
        liq_bsum, liq_ssum, liq_net, liq_ratio,
        stacked_bid, stacked_ask, imbalance,
        vwap_dev, atr_norm, rsi14_norm, wt1_norm, wt2_norm, cci20_norm, adx14_norm,
        macd_norm, macd_sig_norm, bb_pos, bb_wid, ema9_slope, ema20_slope,
        ema50_slope, ema9_20_cross, ema20_50_cross, vol_spike_delta,
        price_acc, body_ratio, hl_range, range_pos, upper_wick, lower_wick,
        mom5, mom10, mom20, ret1,
    ]
    return np.array(feats, dtype=np.float32)


# ── Paper trading state ───────────────────────────────────────────────────────

@dataclass
class PaperPosition:
    direction: str       # LONG | SHORT
    entry_price: float
    quantity: float
    entry_time: int
    stop_loss: float
    take_profit: float

@dataclass
class PaperTrade:
    direction: str
    entry_price: float
    exit_price: float
    pnl: float
    opened_at: int
    closed_at: int
    reason: str

@dataclass
class PaperState:
    balance: float = 0.0
    start_balance: float = 0.0
    open_position: Optional[PaperPosition] = None
    trades: list[PaperTrade] = field(default_factory=list)

    def __post_init__(self):
        if self.balance == 0.0:
            self.balance = PAPER_START
            self.start_balance = PAPER_START

    @property
    def total_trades(self): return len(self.trades)
    @property
    def win_trades(self): return sum(1 for t in self.trades if t.pnl > 0)
    @property
    def win_rate(self): return self.win_trades / max(self.total_trades, 1)
    @property
    def roi(self): return (self.balance - self.start_balance) / self.start_balance

    def summary(self) -> dict:
        return {
            "balance":      round(self.balance, 2),
            "start_balance": round(self.start_balance, 2),
            "roi_pct":      round(self.roi * 100, 2),
            "total_trades": self.total_trades,
            "win_trades":   self.win_trades,
            "win_rate_pct": round(self.win_rate * 100, 1),
            "open_position": asdict(self.open_position) if self.open_position else None,
            "recent_trades": [asdict(t) for t in self.trades[-10:]],
        }

paper = PaperState()


def paper_on_signal(signal: str, price: float, ts: int, atr_pct: float = 0.02):
    pos = paper.open_position
    sl_pct = min(max(1.5 * atr_pct, 0.01), 0.05)
    tp_pct = sl_pct * 2.0

    if pos:
        # close if opposite or TP/SL
        should_close = False
        reason = "signal_flip"
        if signal == "LONG"  and pos.direction == "SHORT": should_close = True
        if signal == "SHORT" and pos.direction == "LONG":  should_close = True
        # check TP/SL
        if pos.direction == "SHORT" and price <= pos.take_profit: should_close = True; reason = "take_profit"
        if pos.direction == "SHORT" and price >= pos.stop_loss:   should_close = True; reason = "stop_loss"
        if pos.direction == "LONG"  and price >= pos.take_profit: should_close = True; reason = "take_profit"
        if pos.direction == "LONG"  and price <= pos.stop_loss:   should_close = True; reason = "stop_loss"

        if should_close:
            gross = (price - pos.entry_price) * pos.quantity if pos.direction == "LONG" \
                    else (pos.entry_price - price) * pos.quantity
            fee = pos.quantity * pos.entry_price * 0.0004 * 2
            pnl = gross - fee
            paper.balance = max(paper.balance + pnl, 0.0)
            paper.trades.append(PaperTrade(
                direction=pos.direction, entry_price=pos.entry_price, exit_price=price,
                pnl=round(pnl, 4), opened_at=pos.entry_time, closed_at=ts, reason=reason,
            ))
            paper.open_position = None

    if signal in ("LONG", "SHORT") and paper.open_position is None:
        risk_usd = paper.balance * RISK_PCT
        qty      = (risk_usd / sl_pct) / price
        sl = price * (1 + sl_pct) if signal == "SHORT" else price * (1 - sl_pct)
        tp = price * (1 - tp_pct) if signal == "SHORT" else price * (1 + tp_pct)
        paper.open_position = PaperPosition(
            direction=signal, entry_price=price, quantity=qty,
            entry_time=ts, stop_loss=sl, take_profit=tp,
        )


# ── Exchange connectors ───────────────────────────────────────────────────────

def _binance_sign(params: dict) -> str:
    qs = urllib.parse.urlencode(sorted(params.items()))
    return hmac.new(BINANCE_SEC.encode(), qs.encode(), hashlib.sha256).hexdigest()

def binance_place_order(symbol: str, side: str, qty: float) -> dict:
    """side: BUY or SELL. Returns order dict."""
    url  = "https://fapi.binance.com/fapi/v1/order"
    ts   = int(time.time() * 1000)
    params = {
        "symbol":    symbol,
        "side":      side,
        "type":      "MARKET",
        "quantity":  f"{qty:.4f}",
        "timestamp": ts,
        "recvWindow": 5000,
    }
    params["signature"] = _binance_sign(params)
    hdrs = {"X-MBX-APIKEY": BINANCE_KEY}
    r = requests.post(url, params=params, headers=hdrs, timeout=5)
    r.raise_for_status()
    return r.json()

def binance_close_position(symbol: str) -> dict:
    """Close any open position for symbol."""
    url = "https://fapi.binance.com/fapi/v1/allOpenOrders"
    ts  = int(time.time() * 1000)
    params = {"symbol": symbol, "timestamp": ts, "recvWindow": 5000}
    params["signature"] = _binance_sign(params)
    hdrs = {"X-MBX-APIKEY": BINANCE_KEY}
    requests.delete(url, params=params, headers=hdrs, timeout=5)
    # fetch position then close
    pos_url = "https://fapi.binance.com/fapi/v2/positionRisk"
    params2 = {"symbol": symbol, "timestamp": int(time.time()*1000), "recvWindow": 5000}
    params2["signature"] = _binance_sign(params2)
    r = requests.get(pos_url, params=params2, headers=hdrs, timeout=5)
    positions = r.json()
    for p in positions:
        amt = float(p.get("positionAmt", 0))
        if amt != 0:
            close_side = "SELL" if amt > 0 else "BUY"
            return binance_place_order(symbol, close_side, abs(amt))
    return {}

def hyperliquid_place_order(coin: str, is_buy: bool, sz: float, price: float) -> dict:
    """Requires hyperliquid-python-sdk installed."""
    try:
        from hyperliquid.exchange import Exchange
        from hyperliquid.utils import constants
        from eth_account import Account
        acct = Account.from_key(HL_PRIVATE)
        exchange = Exchange(acct, constants.MAINNET_API_URL)
        order_result = exchange.order(
            coin, is_buy, sz, price,
            {"limit": {"tif": "Ioc"}},  # IOC = immediate-or-cancel market-like
        )
        return order_result
    except ImportError:
        return {"error": "hyperliquid-python-sdk not installed"}

def bybit_place_order(symbol: str, side: str, qty: float) -> dict:
    """side: Buy or Sell."""
    url = "https://api.bybit.com/v5/order/create"
    ts  = str(int(time.time() * 1000))
    body = json.dumps({
        "category": "linear",
        "symbol": symbol,
        "side": side,
        "orderType": "Market",
        "qty": str(qty),
    })
    sign_str = ts + BYBIT_KEY + "5000" + body
    sig = hmac.new(BYBIT_SEC.encode(), sign_str.encode(), hashlib.sha256).hexdigest()
    hdrs = {
        "X-BAPI-API-KEY": BYBIT_KEY,
        "X-BAPI-TIMESTAMP": ts,
        "X-BAPI-RECV-WINDOW": "5000",
        "X-BAPI-SIGN": sig,
        "Content-Type": "application/json",
    }
    r = requests.post(url, data=body, headers=hdrs, timeout=5)
    r.raise_for_status()
    return r.json()

def okx_place_order(inst_id: str, side: str, sz: str) -> dict:
    """side: buy or sell."""
    import base64, datetime
    url = "https://www.okx.com/api/v5/trade/order"
    ts = datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%S.000Z")
    body = json.dumps({
        "instId": inst_id,
        "tdMode": "cross",
        "side":   side,
        "ordType": "market",
        "sz": sz,
    })
    msg = ts + "POST" + "/api/v5/trade/order" + body
    sig = base64.b64encode(hmac.new(OKX_SEC.encode(), msg.encode(), hashlib.sha256).digest()).decode()
    hdrs = {
        "OK-ACCESS-KEY": OKX_KEY,
        "OK-ACCESS-SIGN": sig,
        "OK-ACCESS-TIMESTAMP": ts,
        "OK-ACCESS-PASSPHRASE": OKX_PASS,
        "Content-Type": "application/json",
    }
    r = requests.post(url, data=body, headers=hdrs, timeout=5)
    r.raise_for_status()
    return r.json()


# ── Live trading state ────────────────────────────────────────────────────────

live_mode: bool = False       # False = paper only, True = send real orders
live_exchange: str = "binance"  # which exchange to use
live_open_side: Optional[str] = None  # current live position direction

def can_promote() -> bool:
    return paper.win_rate >= PROMOTE_WR and paper.total_trades >= PROMOTE_MIN

def _live_signal(signal: str, price: float):
    global live_open_side
    if not live_mode: return
    sym = SYMBOL
    try:
        if live_exchange == "binance":
            if live_open_side and live_open_side != signal:
                binance_close_position(sym)
                live_open_side = None
            if signal in ("LONG", "SHORT") and live_open_side is None:
                # 1% of notional at price as size
                usdt = 100.0  # fixed USDT per trade — adjust as needed
                qty  = round(usdt / price, 3)
                side = "BUY" if signal == "LONG" else "SELL"
                binance_place_order(sym, side, qty)
                live_open_side = signal
        elif live_exchange == "hyperliquid":
            if live_open_side and live_open_side != signal:
                coin = sym.replace("USDT","")
                hyperliquid_place_order(coin, live_open_side == "SHORT", 0.001, price)
                live_open_side = None
            if signal in ("LONG", "SHORT") and live_open_side is None:
                coin = sym.replace("USDT","")
                hyperliquid_place_order(coin, signal == "LONG", 0.001, price)
                live_open_side = signal
        elif live_exchange == "bybit":
            if live_open_side and live_open_side != signal:
                bybit_place_order(sym, "Sell" if live_open_side=="LONG" else "Buy", 0.001)
                live_open_side = None
            if signal in ("LONG", "SHORT") and live_open_side is None:
                bybit_place_order(sym, "Buy" if signal=="LONG" else "Sell", 0.001)
                live_open_side = signal
        elif live_exchange == "okx":
            if live_open_side and live_open_side != signal:
                okx_place_order(sym+"-USDT-SWAP", "sell" if live_open_side=="LONG" else "buy", "1")
                live_open_side = None
            if signal in ("LONG", "SHORT") and live_open_side is None:
                okx_place_order(sym+"-USDT-SWAP", "buy" if signal=="LONG" else "sell", "1")
                live_open_side = signal
    except Exception as e:
        print(f"[live] {live_exchange} order error: {e}")


# ── Candle aggregator ─────────────────────────────────────────────────────────

CANDLE_HISTORY: deque[Candle] = deque(maxlen=500)
_current_candle: Optional[Candle] = None
_cvd_running = 0.0
_last_signal: dict[str, Any] = {}
_clients: set[WebSocket] = set()

async def _broadcast(msg: dict):
    data = json.dumps(msg)
    dead = set()
    for ws in _clients:
        try:
            await ws.send_text(data)
        except Exception:
            dead.add(ws)
    _clients.difference_update(dead)


def _candle_interval_ms() -> int:
    mapping = {"1m":60000,"3m":180000,"5m":300000,"15m":900000,"30m":1800000,
               "1h":3600000,"4h":14400000,"1d":86400000}
    return mapping.get(INTERVAL, 300000)


def _open_time_for(ts_ms: int) -> int:
    iv = _candle_interval_ms()
    return (ts_ms // iv) * iv


def on_trade(price: float, qty: float, is_buy: bool, ts_ms: int,
             liq: bool = False):
    """Called for each incoming trade tick."""
    global _current_candle, _cvd_running

    ot = _open_time_for(ts_ms)
    delta = qty if is_buy else -qty
    _cvd_running += delta

    if _current_candle is None or _current_candle.open_time != ot:
        # close previous
        if _current_candle is not None:
            CANDLE_HISTORY.append(_current_candle)
            asyncio.create_task(_on_closed_candle(_current_candle))
        _current_candle = Candle(
            open_time=ot, open=price, high=price, low=price, close=price,
            volume=0.0, cvd=_cvd_running,
        )

    c = _current_candle
    c.high  = max(c.high, price)
    c.low   = min(c.low,  price)
    c.close = price
    c.volume += qty
    if is_buy:  c.buy_vol  += qty
    else:       c.sell_vol += qty
    if liq:
        if is_buy: c.liq_buy  += qty
        else:      c.liq_sell += qty
    c.cvd = _cvd_running
    # update VWAP
    c.vwap = (c.vwap * (c.volume - qty) + price * qty) / (c.volume + 1e-9)


async def _on_closed_candle(candle: Candle):
    """Run inference on each closed candle, apply MTF confluence gate, broadcast signal."""
    global _last_signal
    hist = list(CANDLE_HISTORY)
    feat = build_realtime_features(hist)
    if feat is None:
        return   # not enough history yet

    prob, signal = ensemble_predict(feat)
    ts      = candle.open_time
    atr_pct = (candle.high - candle.low) / (candle.close + 1e-9)

    # ── MTF confluence gate (≥3/5 TFs must agree) ─────────────────────────────
    tf_results, confluence_score, confluence_max = await run_mtf_analysis(SYMBOL)
    tf_signals = {tf: r["signal"] for tf, r in tf_results.items()}
    if signal in ("LONG", "SHORT"):
        matching = sum(1 for s in tf_signals.values() if s == signal)
        if matching < 3:
            print(f"[signal] {signal} gated — {matching}/{confluence_max} TFs agree")
            signal = "NONE"

    paper_on_signal(signal, candle.close, ts, atr_pct)
    _live_signal(signal, candle.close)

    _last_signal = {
        "type":             "signal",
        "ts":               ts,
        "close":            candle.close,
        "signal":           signal,
        "prob":             round(prob, 4),
        "atr_pct":          round(atr_pct, 5),
        "live_mode":        live_mode,
        "exchange":         live_exchange if live_mode else None,
        "paper":            paper.summary(),
        "symbol":           SYMBOL,
        "interval":         INTERVAL,
        "confluence_score": confluence_score,
        "confluence_max":   confluence_max,
        "mtf_signals":      tf_signals,
    }
    await _broadcast(_last_signal)
    print(f"[signal] {signal} prob={prob:.3f} close={candle.close} "
          f"confluence={confluence_score}/{confluence_max}")


# ── Binance Futures WS ────────────────────────────────────────────────────────

async def binance_ws_loop():
    """
    Streams aggTrade from Binance Futures.
    Falls back to kline WS if aggTrade fails.
    """
    uri = f"wss://fstream.binance.com/ws/{SYMBOL.lower()}@aggTrade"
    while True:
        try:
            async with websockets.connect(uri, ping_interval=20) as ws:
                print(f"[binance_ws] Connected: {uri}")
                async for raw in ws:
                    msg = json.loads(raw)
                    # aggTrade: p=price, q=qty, m=isMakerBuyer (True=sell), T=ts
                    price  = float(msg["p"])
                    qty    = float(msg["q"])
                    is_buy = not msg["m"]   # if maker is buyer, taker is seller
                    ts_ms  = int(msg["T"])
                    on_trade(price, qty, is_buy, ts_ms)
        except Exception as e:
            print(f"[binance_ws] Error: {e}  retrying in 5s")
            await asyncio.sleep(5)


async def cryexc_ws_loop():
    """
    Connect to jose-donato's cryexc-backend for enriched trade + liquidation data.
    URL format: ws://HOST:8000/ws/trades/BTCUSDT
    """
    if not CRYEXC_WS:
        return
    uri = f"{CRYEXC_WS}/ws/trades/{SYMBOL}"
    while True:
        try:
            async with websockets.connect(uri, ping_interval=20) as ws:
                print(f"[cryexc_ws] Connected: {uri}")
                async for raw in ws:
                    msg = json.loads(raw)
                    # Adapt cryexc schema: {price, quantity, is_buy_maker, time_ms, is_liquidation}
                    price  = float(msg.get("price", 0))
                    qty    = float(msg.get("quantity", 0))
                    is_buy = not msg.get("is_buy_maker", True)
                    ts_ms  = int(msg.get("time_ms", time.time() * 1000))
                    liq    = bool(msg.get("is_liquidation", False))
                    on_trade(price, qty, is_buy, ts_ms, liq=liq)
        except Exception as e:
            print(f"[cryexc_ws] Error: {e}  retrying in 5s")
            await asyncio.sleep(5)


# ── MTF Scanner ───────────────────────────────────────────────────────────────

async def fetch_klines_rest(symbol: str, interval: str, limit: int = 100) -> list[Candle]:
    """Fetch historical klines from Binance Futures REST, return list[Candle]."""
    loop = asyncio.get_event_loop()
    url  = (f"https://fapi.binance.com/fapi/v1/klines"
            f"?symbol={symbol}&interval={interval}&limit={limit}")
    def _get():
        r = requests.get(url, timeout=10)
        r.raise_for_status()
        return r.json()
    rows = await loop.run_in_executor(None, _get)
    out  = []
    for row in rows:
        vol  = float(row[5])
        buyv = float(row[9])
        out.append(Candle(
            open_time=int(row[0]),
            open=float(row[1]), high=float(row[2]),
            low=float(row[3]),  close=float(row[4]),
            volume=vol, buy_vol=buyv, sell_vol=vol - buyv,
        ))
    return out


async def run_tf_analysis(symbol: str, interval: str) -> dict:
    """Fetch klines for one TF, run ensemble. Returns {"signal": str, "prob": float}."""
    try:
        candles = await fetch_klines_rest(symbol, interval)
        feat = build_realtime_features(candles)
        if feat is None:
            return {"signal": "NONE", "prob": 0.5}
        prob, sig = ensemble_predict(feat)
        return {"signal": sig, "prob": round(prob, 4)}
    except Exception as e:
        print(f"[mtf] {symbol} {interval}: {e}")
        return {"signal": "NONE", "prob": 0.5}


async def run_mtf_analysis(symbol: str) -> tuple[dict, int, int]:
    """
    Run ensemble across all TIMEFRAMES for symbol.
    Returns (tf_results dict, confluence_score, total_tfs).
    Confluence = max(longs, shorts) across TFs.
    """
    results = await asyncio.gather(*[run_tf_analysis(symbol, tf) for tf in TIMEFRAMES])
    tf_results = dict(zip(TIMEFRAMES, results))
    longs  = sum(1 for r in results if r["signal"] == "LONG")
    shorts = sum(1 for r in results if r["signal"] == "SHORT")
    return tf_results, max(longs, shorts), len(TIMEFRAMES)


async def scanner_task():
    """Background: every 5 min scan all SCAN_SYMBOLS across all TIMEFRAMES."""
    global scanner_state
    await asyncio.sleep(15)   # let server warm up first
    while True:
        for sym in SCAN_SYMBOLS:
            try:
                tf_results, score, total = await run_mtf_analysis(sym)
                async with _scanner_lock:
                    scanner_state[sym] = {
                        "tf_signals":      {tf: r["signal"] for tf, r in tf_results.items()},
                        "confluence_score": score,
                        "confluence_max":  total,
                        "ts":             int(time.time() * 1000),
                    }
                await asyncio.sleep(0.5)   # polite rate limiting
            except Exception as e:
                print(f"[scanner] {sym}: {e}")
        await asyncio.sleep(300)   # 5-minute cycle


# ── Polymarket prices ─────────────────────────────────────────────────────────

async def polymarket_prices() -> list[dict]:
    """Fetch top crypto prediction market prices from Polymarket."""
    try:
        # Polymarket public API — top markets by volume
        url = "https://gamma-api.polymarket.com/markets?closed=false&limit=20&order=volume24hr&ascending=false"
        r = requests.get(url, timeout=5)
        r.raise_for_status()
        markets = r.json()
        results = []
        for m in markets:
            results.append({
                "question":   m.get("question", ""),
                "yes_price":  m.get("outcomePrices", [0.5])[0],
                "volume24h":  m.get("volume24hr", 0),
                "end_date":   m.get("endDate", ""),
            })
        return results
    except Exception as e:
        print(f"[polymarket] Error: {e}")
        return []


# ── FastAPI app ───────────────────────────────────────────────────────────────

app = FastAPI(title="Signal Server")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


@app.get("/health")
def health():
    return {"status": "ok", "symbol": SYMBOL, "interval": INTERVAL,
            "live_mode": live_mode, "paper_trades": paper.total_trades}


@app.get("/paper/status")
def paper_status():
    return paper.summary()


@app.get("/paper/can_promote")
def check_promote():
    return {
        "can_promote": can_promote(),
        "win_rate":    round(paper.win_rate, 3),
        "trades":      paper.total_trades,
        "required_wr": PROMOTE_WR,
        "required_trades": PROMOTE_MIN,
    }


@app.post("/paper/reset")
def reset_paper():
    global paper, live_open_side, live_mode
    paper = PaperState()
    live_open_side = None
    live_mode = False
    return {"status": "reset"}


@app.post("/promote")
def promote(
    exchange: str = "binance",
    binance_api_key: str = "", binance_api_secret: str = "",
    hl_private_key: str  = "",
    bybit_api_key: str   = "", bybit_api_secret: str  = "",
    okx_api_key: str     = "", okx_api_secret: str    = "", okx_passphrase: str = "",
):
    global live_mode, live_exchange, BINANCE_KEY, BINANCE_SEC, HL_PRIVATE, BYBIT_KEY, BYBIT_SEC, OKX_KEY, OKX_SEC, OKX_PASS
    if not can_promote():
        return {"error": "Performance threshold not met",
                "win_rate": paper.win_rate, "trades": paper.total_trades}
    # Store whatever keys were sent (non-blank overrides env)
    if binance_api_key:    BINANCE_KEY  = binance_api_key
    if binance_api_secret: BINANCE_SEC  = binance_api_secret
    if hl_private_key:     HL_PRIVATE   = hl_private_key
    if bybit_api_key:      BYBIT_KEY    = bybit_api_key
    if bybit_api_secret:   BYBIT_SEC    = bybit_api_secret
    if okx_api_key:        OKX_KEY      = okx_api_key
    if okx_api_secret:     OKX_SEC      = okx_api_secret
    if okx_passphrase:     OKX_PASS     = okx_passphrase
    live_mode     = True
    live_exchange = exchange
    return {"status": "live", "exchange": exchange}


@app.post("/demote")
def demote():
    global live_mode
    live_mode = False
    return {"status": "paper"}


@app.get("/signal/latest")
def latest_signal():
    return _last_signal or {"signal": "NONE", "paper": paper.summary()}


@app.get("/symbols")
def symbols():
    return {"symbols": SCAN_SYMBOLS, "timeframes": TIMEFRAMES}


@app.get("/scanner")
async def scanner():
    async with _scanner_lock:
        return dict(scanner_state)


@app.get("/polymarket/prices")
async def poly_prices():
    return await polymarket_prices()


@app.websocket("/ws")
async def signal_ws(ws: WebSocket):
    await ws.accept()
    _clients.add(ws)
    # Send current state immediately on connect
    if _last_signal:
        await ws.send_text(json.dumps(_last_signal))
    else:
        await ws.send_text(json.dumps({"type": "paper", "paper": paper.summary()}))
    try:
        while True:
            # Keep alive — client can send pings
            await ws.receive_text()
    except WebSocketDisconnect:
        _clients.discard(ws)


# ── Startup ───────────────────────────────────────────────────────────────────

@app.on_event("startup")
async def startup():
    # Prefer cryexc-backend if configured, else use Binance WS directly
    if CRYEXC_WS:
        asyncio.create_task(cryexc_ws_loop())
    else:
        asyncio.create_task(binance_ws_loop())
    asyncio.create_task(scanner_task())
    print(f"[signal_server] Started — symbol={SYMBOL} interval={INTERVAL}")
    print(f"[signal_server] Models: cat={cat_session is not None} xgb={xgb_session is not None} "
          f"rf={rf_session is not None} lor={lorentzian_clf is not None}")


if __name__ == "__main__":
    uvicorn.run("signal_server:app", host="0.0.0.0", port=8001, reload=False)
