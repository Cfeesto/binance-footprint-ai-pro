"""
fetch_data.py
-------------
Downloads 12 months of 5m klines for BTCUSDT and ETHUSDT from Binance public API.
Each kline row includes taker_buy_base_asset_volume (real buy volume).
No API key required.
"""

import os
import time
import requests
import pandas as pd
from datetime import datetime, timedelta
from tqdm import tqdm

BASE_URL = "https://api.binance.us/api/v3/klines"
DATA_DIR = os.path.join(os.path.dirname(__file__), "data")

KLINE_COLUMNS = [
    "open_time", "open", "high", "low", "close", "volume",
    "close_time", "quote_volume", "num_trades",
    "taker_buy_base_volume", "taker_buy_quote_volume", "ignore"
]


def fetch_klines(symbol: str, interval: str, start_ms: int, end_ms: int) -> list:
    """Fetch klines in 1000-candle batches between start_ms and end_ms."""
    all_klines = []
    current = start_ms

    with tqdm(desc=f"Fetching {symbol} {interval}", unit="batch") as pbar:
        while current < end_ms:
            params = {
                "symbol": symbol,
                "interval": interval,
                "startTime": current,
                "endTime": end_ms,
                "limit": 1000
            }
            try:
                resp = requests.get(BASE_URL, params=params, timeout=15)
                resp.raise_for_status()
                data = resp.json()
            except Exception as e:
                print(f"  Error fetching {symbol}: {e}, retrying in 5s...")
                time.sleep(5)
                continue

            if not data:
                break

            all_klines.extend(data)
            last_open_time = data[-1][0]

            if last_open_time == current:
                break  # no progress, stop

            current = last_open_time + 1  # move past last candle
            pbar.update(1)
            time.sleep(0.3)  # respect rate limits

    return all_klines


def klines_to_df(klines: list) -> pd.DataFrame:
    df = pd.DataFrame(klines, columns=KLINE_COLUMNS)
    df = df[["open_time", "open", "high", "low", "close", "volume",
             "taker_buy_base_volume", "num_trades"]].copy()

    for col in ["open", "high", "low", "close", "volume", "taker_buy_base_volume"]:
        df[col] = pd.to_numeric(df[col], errors="coerce")
    df["num_trades"] = pd.to_numeric(df["num_trades"], errors="coerce").astype(int)

    df["open_time"] = pd.to_datetime(df["open_time"], unit="ms", utc=True)
    df.set_index("open_time", inplace=True)
    df.sort_index(inplace=True)
    df.drop_duplicates(inplace=True)

    # Derived footprint columns
    df["sell_volume"] = df["volume"] - df["taker_buy_base_volume"]
    df["buy_volume"] = df["taker_buy_base_volume"]
    df["delta"] = df["buy_volume"] - df["sell_volume"]

    return df


def load_or_fetch(symbol: str, interval: str = "5m", months: int = 12) -> pd.DataFrame:
    os.makedirs(DATA_DIR, exist_ok=True)
    cache_path = os.path.join(DATA_DIR, f"{symbol}_{interval}_{months}m.parquet")

    if os.path.exists(cache_path):
        print(f"  Loading cached data: {cache_path}")
        df = pd.read_parquet(cache_path)
        print(f"  Loaded {len(df):,} candles ({df.index[0]} → {df.index[-1]})")
        return df

    end_dt = datetime.utcnow()
    start_dt = end_dt - timedelta(days=30 * months)
    start_ms = int(start_dt.timestamp() * 1000)
    end_ms = int(end_dt.timestamp() * 1000)

    print(f"\nFetching {symbol} {interval} from {start_dt.date()} to {end_dt.date()}")
    klines = fetch_klines(symbol, interval, start_ms, end_ms)

    if not klines:
        raise ValueError(f"No data returned for {symbol}")

    df = klines_to_df(klines)
    df.to_parquet(cache_path)
    print(f"  Saved {len(df):,} candles to {cache_path}")
    return df


if __name__ == "__main__":
    for sym in ["BTCUSDT", "ETHUSDT"]:
        df = load_or_fetch(sym, interval="5m", months=12)
        print(f"\n{sym} sample:")
        print(df[["open", "close", "buy_volume", "sell_volume", "delta"]].tail(3))
        print(f"  NaN check: {df.isnull().sum().sum()} NaNs")
