"""
features.py
-----------
Computes all footprint + technical indicator features from kline data.
Uses the taker_buy_base_volume field from Binance klines as real buy volume.
"""

import numpy as np
import pandas as pd


# ─── Footprint Features ────────────────────────────────────────────────────────

def add_footprint_features(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    vol = df["volume"].replace(0, np.nan)

    # Core footprint ratios（零成交量时填充中性值，避免NaN传播）
    df["buy_ratio"]   = (df["buy_volume"] / vol).fillna(0.5)
    df["sell_ratio"]  = (df["sell_volume"] / vol).fillna(0.5)
    df["delta_ratio"] = (df["delta"] / vol).fillna(0.0)       # [-1, +1]

    # Cumulative volume delta
    df["cvd_5"]  = df["delta"].rolling(5).sum()
    df["cvd_20"] = df["delta"].rolling(20).sum()

    # Normalised CVD (relative to rolling volume)
    roll_vol_20 = df["volume"].rolling(20).sum().replace(0, np.nan)
    df["cvd_ratio_20"] = df["cvd_20"] / roll_vol_20

    # Volume spike (how unusual is this candle's volume)
    df["vol_ma20"] = df["volume"].rolling(20).mean()
    df["volume_spike"] = df["volume"] / df["vol_ma20"].replace(0, np.nan)

    # POC position estimate:
    #   0.0 = POC near low (sell-dominated), 1.0 = POC near high (buy-dominated)
    df["poc_position"] = df["buy_ratio"].clip(0, 1)

    # Delta divergence: price moved up but delta negative (bearish hidden pressure)
    price_up  = (df["close"] > df["open"]).astype(int)
    price_dn  = (df["close"] < df["open"]).astype(int)
    df["delta_div_bear"] = (price_up  & (df["delta"] < 0)).astype(float)
    df["delta_div_bull"] = (price_dn  & (df["delta"] > 0)).astype(float)

    # Candle structure（doji K线 high=low 时，wick/body 填0，避免NaN）
    candle_range = (df["high"] - df["low"]).replace(0, np.nan)
    df["candle_range_pct"] = (candle_range / df["close"]).fillna(0.0)
    df["body_ratio"]       = ((df["close"] - df["open"]).abs() / candle_range).fillna(0.0)
    df["upper_wick"]       = ((df["high"] - df[["open","close"]].max(axis=1)) / candle_range).fillna(0.0)
    df["lower_wick"]       = ((df[["open","close"]].min(axis=1) - df["low"]) / candle_range).fillna(0.0)

    # Stacked imbalance proxy: consecutive candles with same delta direction
    delta_sign = np.sign(df["delta"])
    df["stacked_bull"] = (
        (delta_sign == 1) &
        (delta_sign.shift(1) == 1) &
        (delta_sign.shift(2) == 1)
    ).astype(float)
    df["stacked_bear"] = (
        (delta_sign == -1) &
        (delta_sign.shift(1) == -1) &
        (delta_sign.shift(2) == -1)
    ).astype(float)

    # Buy/sell volume rolling ratios (momentum)
    buy_roll5  = df["buy_volume"].rolling(5).mean()
    sell_roll5 = df["sell_volume"].rolling(5).mean()
    df["buy_sell_momentum"] = (buy_roll5 - sell_roll5) / (buy_roll5 + sell_roll5).replace(0, np.nan)

    # ── 清算代理特征 (Liquidation Proxy Features) ──────────────────────────────
    # 下影线 × 放量 → 空头被清算（看多信号）
    df["liq_short_proxy"] = df["lower_wick"] * df["volume_spike"]
    # 上影线 × 放量 → 多头被清算（看空信号）
    df["liq_long_proxy"]  = df["upper_wick"] * df["volume_spike"]
    # 净清算压力：正值 = 空头被清算多，负值 = 多头被清算多
    df["liq_net"]         = df["liq_short_proxy"] - df["liq_long_proxy"]
    # 大单方向力度：放量时的 delta 方向
    df["vol_spike_delta"] = df["volume_spike"] * df["delta_ratio"]
    # CVD 加速度：过去5根K线的CVD变化速度
    df["cvd_accel"]       = df["cvd_5"] - df["cvd_5"].shift(5)
    # 价格动量（5根 & 20根 K线涨跌幅）
    df["price_mom_5"]  = df["close"].pct_change(5)
    df["price_mom_20"] = df["close"].pct_change(20)

    # ── VWAP 偏离特征 ─────────────────────────────────────────────────────────
    # 滚动20根K线近似 VWAP，保留更多训练数据
    typical = (df["high"] + df["low"] + df["close"]) / 3
    roll20_tvol = (typical * df["volume"]).rolling(20).sum()
    roll20_vol  = df["volume"].rolling(20).sum().replace(0, np.nan)
    vwap_20     = roll20_tvol / roll20_vol
    df["vwap_dev"] = (df["close"] - vwap_20) / vwap_20   # 偏离度

    # ── 趋势斜率特征 ──────────────────────────────────────────────────────────
    ema20 = df["close"].ewm(span=20, adjust=False).mean()
    df["ema20_slope"] = (ema20 - ema20.shift(5)) / df["close"]

    # ── 区间位置特征 ──────────────────────────────────────────────────────────
    # 20根K线内高低点位置 [0=最低, 1=最高]
    high20 = df["high"].rolling(20).max()
    low20  = df["low"].rolling(20).min()
    df["range_pos_20"]     = (df["close"] - low20) / (high20 - low20).replace(0, np.nan)
    df["dist_from_high20"] = (high20 - df["close"]) / df["close"]
    df["dist_from_low20"]  = (df["close"] - low20) / df["close"]

    # ── 买压一致性（buy_ratio 已填充0.5，rolling std 不会产生NaN）────────────
    df["buy_ratio_std10"] = df["buy_ratio"].rolling(10).std().fillna(0.0)

    return df


# ─── Technical Indicators ──────────────────────────────────────────────────────

def _ema(series: pd.Series, period: int) -> pd.Series:
    return series.ewm(span=period, adjust=False).mean()


def add_rsi(df: pd.DataFrame, period: int = 14) -> pd.DataFrame:
    delta = df["close"].diff()
    gain  = delta.clip(lower=0)
    loss  = (-delta).clip(lower=0)
    avg_gain = gain.ewm(com=period - 1, adjust=False).mean()
    avg_loss = loss.ewm(com=period - 1, adjust=False).mean()
    # 用极小值替代0，避免强趋势时产生 NaN 导致大量数据丢失
    rs = avg_gain / avg_loss.clip(lower=1e-10)
    df["rsi14"] = 100 - (100 / (1 + rs))
    df["rsi14_norm"] = (df["rsi14"] - 50) / 50   # [-1, +1] centred
    return df


def add_cci(df: pd.DataFrame, period: int = 20) -> pd.DataFrame:
    tp = (df["high"] + df["low"] + df["close"]) / 3
    tp_ma  = tp.rolling(period).mean()
    tp_std = tp.rolling(period).std()
    df["cci20"] = (tp - tp_ma) / (0.015 * tp_std.replace(0, np.nan))
    df["cci20_norm"] = df["cci20"] / 200          # rough normalise
    return df


def add_adx(df: pd.DataFrame, period: int = 14) -> pd.DataFrame:
    high, low, close = df["high"], df["low"], df["close"]
    tr = pd.concat([
        (high - low),
        (high - close.shift(1)).abs(),
        (low  - close.shift(1)).abs()
    ], axis=1).max(axis=1)

    plus_dm  = (high.diff()).clip(lower=0)
    minus_dm = (-low.diff()).clip(lower=0)
    plus_dm  = plus_dm.where(plus_dm > minus_dm, 0)
    minus_dm = minus_dm.where(minus_dm > plus_dm.where(plus_dm > minus_dm, 0), 0)

    atr      = tr.ewm(span=period, adjust=False).mean()
    plus_di  = 100 * plus_dm.ewm(span=period, adjust=False).mean() / atr.replace(0, np.nan)
    minus_di = 100 * minus_dm.ewm(span=period, adjust=False).mean() / atr.replace(0, np.nan)
    dx       = (100 * (plus_di - minus_di).abs() / (plus_di + minus_di).replace(0, np.nan))
    df["adx14"]       = dx.ewm(span=period, adjust=False).mean()
    df["adx14_norm"]  = df["adx14"] / 100
    df["plus_di"]     = plus_di / 100
    df["minus_di"]    = minus_di / 100
    return df


def add_wave_trend(df: pd.DataFrame, n1: int = 10, n2: int = 21) -> pd.DataFrame:
    """WaveTrend oscillator (LazyBear) — used by Lorentzian Classification."""
    ap  = (df["high"] + df["low"] + df["close"]) / 3
    esa = _ema(ap, n1)
    d   = _ema((ap - esa).abs(), n1)
    ci  = (ap - esa) / (0.015 * d.replace(0, np.nan))
    tci = _ema(ci, n2)
    df["wt1"] = tci
    df["wt2"] = tci.rolling(4).mean()
    df["wt_diff"] = df["wt1"] - df["wt2"]
    df["wt1_norm"] = df["wt1"] / 100
    return df


def add_macd(df: pd.DataFrame) -> pd.DataFrame:
    fast = _ema(df["close"], 12)
    slow = _ema(df["close"], 26)
    macd = fast - slow
    signal = _ema(macd, 9)
    df["macd_hist"] = macd - signal
    df["macd_hist_norm"] = df["macd_hist"] / df["close"]
    return df


def add_atr(df: pd.DataFrame, period: int = 14) -> pd.DataFrame:
    tr = pd.concat([
        (df["high"] - df["low"]),
        (df["high"] - df["close"].shift(1)).abs(),
        (df["low"]  - df["close"].shift(1)).abs()
    ], axis=1).max(axis=1)
    df["atr14"] = tr.ewm(span=period, adjust=False).mean()
    df["atr14_pct"] = df["atr14"] / df["close"]
    return df


def add_bollinger(df: pd.DataFrame, period: int = 20) -> pd.DataFrame:
    ma  = df["close"].rolling(period).mean()
    std = df["close"].rolling(period).std()
    df["bb_upper"]  = ma + 2 * std
    df["bb_lower"]  = ma - 2 * std
    bandwidth = (df["bb_upper"] - df["bb_lower"]).replace(0, np.nan)
    df["bb_pos"] = (df["close"] - df["bb_lower"]) / bandwidth   # [0,1] in band
    df["bb_width_pct"] = bandwidth / df["close"]
    return df


# ─── Target Label ─────────────────────────────────────────────────────────────

def add_target(df: pd.DataFrame) -> pd.DataFrame:
    """
    Binary target: 1 if NEXT candle is bullish (close > open), 0 if bearish.
    The model predicts the next forming candle's direction.
    """
    next_close = df["close"].shift(-1)
    next_open  = df["open"].shift(-1)
    df["target"] = (next_close > next_open).astype(float)
    df["next_open"]  = next_open
    df["next_close"] = next_close
    return df


# ─── Master Feature Builder ───────────────────────────────────────────────────

FEATURE_COLS = [
    # Footprint
    "buy_ratio", "sell_ratio", "delta_ratio",
    "cvd_ratio_20", "volume_spike", "poc_position",
    "delta_div_bear", "delta_div_bull",
    "candle_range_pct", "body_ratio", "upper_wick", "lower_wick",
    "stacked_bull", "stacked_bear", "buy_sell_momentum",
    # Technical
    "rsi14_norm", "cci20_norm", "adx14_norm", "plus_di", "minus_di",
    "wt1_norm", "wt_diff",
    "macd_hist_norm", "atr14_pct",
    "bb_pos", "bb_width_pct",
    # 清算代理特征
    "liq_short_proxy", "liq_long_proxy", "liq_net",
    "vol_spike_delta", "cvd_accel",
    "price_mom_5", "price_mom_20",
    # VWAP + 趋势 + 区间位置
    "vwap_dev", "ema20_slope", "range_pos_20",
    "dist_from_high20", "dist_from_low20",
    # 买压一致性
    "buy_ratio_std10",
]

# Subset used by Lorentzian KNN
LORENTZIAN_COLS = [
    "rsi14_norm", "wt1_norm", "cci20_norm", "adx14_norm",
    "delta_ratio", "buy_ratio",
    "liq_net", "vol_spike_delta",
    "vwap_dev", "ema20_slope",
]


def build_features(df: pd.DataFrame) -> pd.DataFrame:
    df = add_footprint_features(df)
    df = add_rsi(df)
    df = add_cci(df)
    df = add_adx(df)
    df = add_wave_trend(df)
    df = add_macd(df)
    df = add_atr(df)
    df = add_bollinger(df)
    df = add_target(df)

    # Drop rows with NaNs in feature or target columns
    required = FEATURE_COLS + ["target", "next_open", "next_close"]
    df = df.dropna(subset=required)
    return df


if __name__ == "__main__":
    from fetch_data import load_or_fetch
    raw = load_or_fetch("ETHUSDT")
    df  = build_features(raw)
    print(f"Features built: {len(df):,} rows, {len(FEATURE_COLS)} features")
    print(df[FEATURE_COLS].describe().round(3))
