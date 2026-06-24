"""
shap_analysis.py
----------------
SHAP feature importance analysis for the CatBoost model.

Trains CatBoost on the full ETH dataset, computes SHAP values,
and saves a ranked importance chart + CSV.

Outputs:
  results/shap_importance.png  — bar chart top 20 features
  results/shap_values.csv      — mean |SHAP| per feature (all 33)

Run: python shap_analysis.py
"""

import os
import sys
import warnings
warnings.filterwarnings("ignore")

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

sys.path.insert(0, os.path.dirname(__file__))

from fetch_data import load_or_fetch
from features  import build_features, FEATURE_COLS
from catboost  import CatBoostClassifier, Pool

RESULTS_DIR = os.path.join(os.path.dirname(__file__), "results")
os.makedirs(RESULTS_DIR, exist_ok=True)


def run_shap_analysis():
    print("  Loading ETHUSDT data...")
    raw = load_or_fetch("ETHUSDT", interval="5m", months=12)
    df  = build_features(raw)
    print(f"  {len(df):,} candles")

    X = df[FEATURE_COLS].values
    y = df["target"].values

    # Train CatBoost on 80%, analyse on 20% (out-of-fold is more honest)
    split = int(len(X) * 0.8)
    X_tr, X_val = X[:split], X[split:]
    y_tr, y_val = y[:split], y[split:]

    print("  Training CatBoost for SHAP...")
    model = CatBoostClassifier(
        depth=6, iterations=500, learning_rate=0.05,
        early_stopping_rounds=50, eval_metric="AUC",
        auto_class_weights="Balanced", random_seed=42,
        verbose=0, thread_count=-1,
    )
    model.fit(X_tr, y_tr, eval_set=(X_val, y_val))

    print("  Computing SHAP values (validation set)...")
    pool_val = Pool(X_val, y_val, feature_names=FEATURE_COLS)
    shap_vals = model.get_feature_importance(pool_val, type="ShapValues")
    # shap_vals shape: (n_samples, n_features + 1) — last col is bias
    shap_vals = shap_vals[:, :-1]

    mean_abs = np.abs(shap_vals).mean(axis=0)
    importance = pd.DataFrame({
        "feature":   FEATURE_COLS,
        "mean_shap": mean_abs,
    }).sort_values("mean_shap", ascending=False).reset_index(drop=True)

    # ── Save CSV ───────────────────────────────────────────────────────────────
    csv_path = os.path.join(RESULTS_DIR, "shap_values.csv")
    importance.to_csv(csv_path, index=False)
    print(f"  Saved → {csv_path}")

    # ── Plot top 20 ───────────────────────────────────────────────────────────
    top20 = importance.head(20)
    fig, ax = plt.subplots(figsize=(10, 7), facecolor="#0d1117")
    ax.set_facecolor("#161b22")

    colors = ["#00d4aa" if i < 5 else "#627eea" if i < 10 else "#888"
              for i in range(len(top20))]
    bars = ax.barh(top20["feature"][::-1], top20["mean_shap"][::-1],
                   color=colors[::-1], edgecolor="none", height=0.7)

    ax.set_xlabel("Mean |SHAP value|", color="#aaa", fontsize=10)
    ax.set_title("Feature Importance — SHAP (CatBoost, ETH 5m)",
                 color="white", fontsize=12, pad=10)
    ax.tick_params(colors="white", labelsize=9)
    ax.spines[:].set_color("#30363d")

    # Annotate values
    for bar, val in zip(bars, top20["mean_shap"][::-1]):
        ax.text(bar.get_width() + 0.0002, bar.get_y() + bar.get_height() / 2,
                f"{val:.4f}", va="center", ha="left", color="#aaa", fontsize=8)

    plt.tight_layout()
    img_path = os.path.join(RESULTS_DIR, "shap_importance.png")
    plt.savefig(img_path, dpi=150, bbox_inches="tight", facecolor="#0d1117")
    plt.close()
    print(f"  Saved → {img_path}")

    # ── Print ranked list ─────────────────────────────────────────────────────
    print("\n  ── Top 10 features (most predictive) ──")
    for _, row in importance.head(10).iterrows():
        print(f"    {row['feature']:<25} {row['mean_shap']:.5f}")

    print("\n  ── Bottom 5 features (least predictive) ──")
    for _, row in importance.tail(5).iterrows():
        print(f"    {row['feature']:<25} {row['mean_shap']:.5f}")

    # ── Suggest cuts ──────────────────────────────────────────────────────────
    threshold = importance["mean_shap"].quantile(0.25)
    weak = importance[importance["mean_shap"] < threshold]["feature"].tolist()
    print(f"\n  ── Features below 25th percentile SHAP (consider removing) ──")
    print(f"    {weak}")

    return importance


if __name__ == "__main__":
    run_shap_analysis()
