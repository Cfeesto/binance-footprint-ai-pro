"""
validate_features.py
--------------------
Generates ground-truth feature vectors from Python for use in Android JVM unit tests.

Picks 20 representative kline windows from the parquet cache, computes features.py
output for each, and saves to results/feature_parity_samples.json.

The Kotlin FeatureBuilderTest.kt loads this file and asserts parity.

Usage:
    python3 validate_features.py
"""

import json, os, sys
import numpy as np
import pandas as pd

HERE = os.path.dirname(__file__)
sys.path.insert(0, HERE)
from features import FEATURE_COLS, build_features

DATA_DIR = os.path.join(HERE, "data")
RESULTS  = os.path.join(HERE, "results")
os.makedirs(RESULTS, exist_ok=True)

# ── Load data ──────────────────────────────────────────────────────────────────

parquets = [f for f in os.listdir(DATA_DIR) if f.endswith(".parquet")]
if not parquets:
    sys.exit("No parquet data found — run fetch_data.py first")

raw = pd.read_parquet(os.path.join(DATA_DIR, parquets[0]))
df  = build_features(raw)
print(f"Loaded {len(df):,} feature rows from {parquets[0]}")

# ── Select 20 windows spread across the dataset ───────────────────────────────
# Each window = 50 raw klines → enough EWM warm-up for all indicators.
# We use the raw (pre-build_features) klines as input to Kotlin,
# and the Python feature vector for the last bar as ground truth.

WINDOW_SIZE = 50
N_SAMPLES   = 20

raw_sorted = raw.sort_index()
valid_idx   = df.index  # rows that survived dropna

# Pick N_SAMPLES evenly spaced from valid rows
step   = max(1, len(valid_idx) // N_SAMPLES)
chosen = [valid_idx[i * step] for i in range(N_SAMPLES) if i * step < len(valid_idx)]

samples = []
for ts in chosen:
    # Find position of ts in raw_sorted
    pos = raw_sorted.index.get_loc(ts)
    if isinstance(pos, slice):
        pos = pos.start
    if pos < WINDOW_SIZE:
        continue

    window_raw = raw_sorted.iloc[pos - WINDOW_SIZE + 1 : pos + 1]
    assert len(window_raw) == WINDOW_SIZE

    # Ground-truth features for the LAST bar (pos)
    gt_features = {col: float(df.loc[ts, col]) for col in FEATURE_COLS}

    # Serialize raw klines for Kotlin
    klines = []
    for _, row in window_raw.iterrows():
        klines.append({
            "open":      float(row["open"]),
            "high":      float(row["high"]),
            "low":       float(row["low"]),
            "close":     float(row["close"]),
            "volume":    float(row["volume"]),
            "buyVolume": float(row["buy_volume"]),
            "openTime":  int(row.name.timestamp() * 1000) if hasattr(row.name, 'timestamp') else 0,
        })

    samples.append({
        "klines":   klines,
        "features": gt_features,
        "ts":       str(ts),
    })

print(f"Generated {len(samples)} parity samples (window={WINDOW_SIZE} bars each)")

out_path = os.path.join(RESULTS, "feature_parity_samples.json")
with open(out_path, "w") as f:
    json.dump({"samples": samples, "feature_cols": FEATURE_COLS}, f, indent=2)
print(f"Saved {out_path}")

# ── Quick sanity: show feature ranges ─────────────────────────────────────────
feat_df = pd.DataFrame([s["features"] for s in samples])
print("\nFeature ranges across samples:")
print(feat_df.describe().loc[["min", "max", "mean"]].T.round(4).to_string())
