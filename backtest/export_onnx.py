"""
export_onnx.py
--------------
Train ensemble on full dataset, export each sklearn-compatible model to ONNX.
CatBoost and XGBoost use their native ONNX exporters.
RF uses skl2onnx.

Outputs to results/:
  catboost.onnx
  xgboost.onnx
  rf.onnx
  feature_names.json   ← ordered list Android reads to build input tensor
  thresholds.json      ← long/short thresholds from best_params.json
"""

import json, os, sys
import numpy as np
import pandas as pd

# ── 路径设置 ──────────────────────────────────────────────────────────────────
HERE    = os.path.dirname(__file__)
RESULTS = os.path.join(HERE, "results")
os.makedirs(RESULTS, exist_ok=True)

sys.path.insert(0, HERE)
from features import FEATURE_COLS, build_features
from ensemble  import (build_catboost, build_xgboost, build_rf,
                       SIGNAL_THRESHOLD_LONG, SIGNAL_THRESHOLD_SHORT, _TUNED)

# ── 加载数据 ──────────────────────────────────────────────────────────────────
DATA_CACHE = os.path.join(HERE, "data", "ETHUSDT_5m_12m.parquet")
if not os.path.exists(DATA_CACHE):
    # 尝试找任意 parquet 文件
    parquets = [f for f in os.listdir(os.path.join(HERE, "data")) if f.endswith(".parquet")]
    if not parquets:
        sys.exit("No parquet data found — run fetch_data.py first")
    DATA_CACHE = os.path.join(HERE, "data", parquets[0])
    print(f"Using {parquets[0]}")

print("Loading data...")
df = pd.read_parquet(DATA_CACHE)
df = build_features(df)

X = df[FEATURE_COLS].values.astype(np.float32)
y = df["target"].values

# 80% train (same split as shap_analysis)
split = int(len(X) * 0.8)
X_tr, X_val = X[:split], X[split:]
y_tr, y_val = y[:split], y[split:]

print(f"Train: {len(X_tr):,}  Val: {len(X_val):,}  Features: {len(FEATURE_COLS)}")

# ── CatBoost → native ONNX ───────────────────────────────────────────────────
print("\nTraining CatBoost...")
cat = build_catboost()
# CatBoost ONNX 要求整数标签
y_tr_i = y_tr.astype(int)
y_val_i = y_val.astype(int)
cat.fit(X_tr, y_tr_i, eval_set=(X_val, y_val_i), verbose=0)

cat_path = os.path.join(RESULTS, "catboost.onnx")
cat.save_model(cat_path, format="onnx",
               export_parameters={"onnx_domain": "ai.catboost",
                                   "onnx_model_version": 1})
print(f"  Saved {cat_path}")

# ── XGBoost → native ONNX ────────────────────────────────────────────────────
print("\nTraining XGBoost...")
xgb = build_xgboost()
xgb.fit(X_tr, y_tr, eval_set=[(X_val, y_val)], verbose=False)

xgb_path = os.path.join(RESULTS, "xgboost.onnx")
xgb.get_booster().save_model(xgb_path)
print(f"  Saved {xgb_path}")

# ── Random Forest → skl2onnx ─────────────────────────────────────────────────
print("\nTraining Random Forest...")
rf = build_rf()
rf.fit(X, y)

try:
    from skl2onnx import convert_sklearn
    from skl2onnx.common.data_types import FloatTensorType
    rf_onnx = convert_sklearn(rf, "RandomForest",
                              [("input", FloatTensorType([None, len(FEATURE_COLS)]))])
    rf_path = os.path.join(RESULTS, "rf.onnx")
    with open(rf_path, "wb") as f:
        f.write(rf_onnx.SerializeToString())
    print(f"  Saved {rf_path}")
except ImportError:
    print("  skl2onnx not installed — pip3 install skl2onnx onnx")
    print("  RF ONNX skipped; Android will use CatBoost + XGB only (reweight)")

# ── Metadata ─────────────────────────────────────────────────────────────────
feat_path = os.path.join(RESULTS, "feature_names.json")
with open(feat_path, "w") as f:
    json.dump(FEATURE_COLS, f, indent=2)
print(f"\nSaved {feat_path}")

thresh_path = os.path.join(RESULTS, "thresholds.json")
with open(thresh_path, "w") as f:
    json.dump({
        "long_thresh":  float(SIGNAL_THRESHOLD_LONG),
        "short_thresh": float(SIGNAL_THRESHOLD_SHORT),
        "weights": {
            # ponytail: lorentzian is KNN — not exportable to ONNX, redistribute weight
            "catboost": 0.45,
            "xgboost":  0.35,
            "rf":       0.20,
        }
    }, f, indent=2)
print(f"Saved {thresh_path}")
print("\nExport complete. Copy results/*.onnx + *.json to android/app/src/main/assets/")
