"""
tune.py
-------
Optuna hyperparameter search for the FourModelEnsemble.

Optimises:
  - CatBoost: depth, iterations, learning_rate, l2_leaf_reg
  - XGBoost:  max_depth, n_estimators, learning_rate, subsample, colsample_bytree
  - RF:       n_estimators, max_depth, min_samples_leaf
  - Ensemble: signal thresholds (long / short)

Objective: win_rate * tanh(total_signals / 50)
  → rewards high WR while penalising near-zero signal count.

Saves best params to results/best_params.json.
Run with:  python tune.py [--trials 50]
"""

import os
import sys
import json
import warnings
warnings.filterwarnings("ignore")

import numpy as np
import pandas as pd
import optuna
optuna.logging.set_verbosity(optuna.logging.WARNING)

sys.path.insert(0, os.path.dirname(__file__))

from fetch_data import load_or_fetch
from features  import build_features, FEATURE_COLS, LORENTZIAN_COLS
from sklearn.ensemble import RandomForestClassifier
from sklearn.isotonic import IsotonicRegression
from sklearn.preprocessing import StandardScaler
from catboost import CatBoostClassifier
from xgboost  import XGBClassifier
from ensemble import LorentzianClassifier

RESULTS_DIR = os.path.join(os.path.dirname(__file__), "results")
os.makedirs(RESULTS_DIR, exist_ok=True)

TUNE_TRAIN_MONTHS = 4
TUNE_TEST_MONTHS  = 1
SYMBOL            = "ETHUSDT"


def _load_fold(df: pd.DataFrame):
    """Return first fold: 4 months train, 1 month test."""
    start = df.index[0]
    train_end  = start + pd.DateOffset(months=TUNE_TRAIN_MONTHS)
    test_end   = train_end + pd.DateOffset(months=TUNE_TEST_MONTHS)
    train = df[(df.index >= start)      & (df.index < train_end)]
    test  = df[(df.index >= train_end)  & (df.index < test_end)]
    return train, test


def _objective(trial: optuna.Trial, df: pd.DataFrame) -> float:
    df_train, df_test = _load_fold(df)

    X     = df_train[FEATURE_COLS].values
    y     = df_train["target"].values
    X_lor = df_train[LORENTZIAN_COLS].values

    # ── Model hyperparams ──────────────────────────────────────────────────────
    cat_depth  = trial.suggest_int("cat_depth",   4, 8)
    cat_iter   = trial.suggest_int("cat_iter",  200, 800, step=100)
    cat_lr     = trial.suggest_float("cat_lr",  0.01, 0.15, log=True)
    cat_l2     = trial.suggest_float("cat_l2",  1.0, 10.0)

    xgb_depth  = trial.suggest_int("xgb_depth",   3, 7)
    xgb_n      = trial.suggest_int("xgb_n",    100, 500, step=50)
    xgb_lr     = trial.suggest_float("xgb_lr",  0.01, 0.15, log=True)
    xgb_sub    = trial.suggest_float("xgb_sub", 0.6, 1.0)
    xgb_col    = trial.suggest_float("xgb_col", 0.6, 1.0)

    rf_n       = trial.suggest_int("rf_n",      100, 300, step=50)
    rf_depth   = trial.suggest_int("rf_depth",    5, 12)
    rf_leaf    = trial.suggest_int("rf_leaf",     5, 30)

    # ── Signal thresholds ──────────────────────────────────────────────────────
    long_thresh  = trial.suggest_float("long_thresh",  0.62, 0.80)
    short_thresh = trial.suggest_float("short_thresh", 0.20, 0.38)
    if long_thresh <= short_thresh + 0.15:
        return 0.0  # guard: thresholds must be well-separated

    # ── Train/val split for calibration ───────────────────────────────────────
    split  = int(len(X) * 0.8)
    X_tr, X_val = X[:split], X[split:]
    y_tr, y_val = y[:split], y[split:]

    # Lorentzian
    lor = LorentzianClassifier(k=8, max_bars_back=2000, subsample=4)
    lor.fit(X_lor, y)

    # CatBoost
    cat = CatBoostClassifier(
        depth=cat_depth, iterations=cat_iter, learning_rate=cat_lr,
        l2_leaf_reg=cat_l2, early_stopping_rounds=40, eval_metric="AUC",
        auto_class_weights="Balanced", random_seed=42, verbose=0, thread_count=-1,
    )
    cat.fit(X_tr, y_tr, eval_set=(X_val, y_val))
    cat_iso = IsotonicRegression(out_of_bounds="clip")
    cat_iso.fit(cat.predict_proba(X_val)[:, 1], y_val)

    # XGBoost
    xgb = XGBClassifier(
        max_depth=xgb_depth, n_estimators=xgb_n, learning_rate=xgb_lr,
        subsample=xgb_sub, colsample_bytree=xgb_col,
        eval_metric="logloss", random_state=42, n_jobs=-1, verbosity=0,
    )
    xgb.fit(X_tr, y_tr, eval_set=[(X_val, y_val)], verbose=False)
    xgb_iso = IsotonicRegression(out_of_bounds="clip")
    xgb_iso.fit(xgb.predict_proba(X_val)[:, 1], y_val)

    # RF
    rf = RandomForestClassifier(
        n_estimators=rf_n, max_depth=rf_depth, min_samples_leaf=rf_leaf,
        class_weight="balanced", random_state=42, n_jobs=-1,
    )
    rf.fit(X, y)
    rf_iso = IsotonicRegression(out_of_bounds="clip")
    rf_iso.fit(rf.predict_proba(X_val)[:, 1], y_val)

    # ── Predict on test set ────────────────────────────────────────────────────
    Xt     = df_test[FEATURE_COLS].values
    Xt_lor = df_test[LORENTZIAN_COLS].values

    p_lor = lor.predict_proba(Xt_lor)
    p_cat = cat_iso.predict(cat.predict_proba(Xt)[:, 1])
    p_xgb = xgb_iso.predict(xgb.predict_proba(Xt)[:, 1])
    p_rf  = rf_iso.predict(rf.predict_proba(Xt)[:, 1])

    ensemble = 0.35 * p_lor + 0.30 * p_cat + 0.25 * p_xgb + 0.10 * p_rf

    actual_bull = (df_test["next_close"] > df_test["next_open"]).values

    long_mask  = ensemble >= long_thresh
    short_mask = ensemble <= short_thresh
    n_signals  = long_mask.sum() + short_mask.sum()

    if n_signals == 0:
        return 0.0

    long_wins  = (long_mask  & actual_bull).sum()
    short_wins = (short_mask & ~actual_bull).sum()
    win_rate   = (long_wins + short_wins) / n_signals

    # Objective: WR * tanh(signals / 50) — penalises too few signals
    score = float(win_rate) * float(np.tanh(n_signals / 50))
    return score


def run_tuning(n_trials: int = 50):
    print(f"\n  Loading {SYMBOL} data...")
    raw = load_or_fetch(SYMBOL, interval="5m", months=12)
    df  = build_features(raw)
    print(f"  {len(df):,} candles ready. Running {n_trials} Optuna trials...\n")

    study = optuna.create_study(
        direction="maximize",
        sampler=optuna.samplers.TPESampler(seed=42),
        pruner=optuna.pruners.MedianPruner(n_warmup_steps=10),
    )
    study.optimize(
        lambda trial: _objective(trial, df),
        n_trials=n_trials,
        show_progress_bar=True,
        n_jobs=1,  # CatBoost uses all threads internally
    )

    best = study.best_trial
    print(f"\n  Best score  : {best.value:.4f}")
    print(f"  Best params : {best.params}")

    # ── Save ──────────────────────────────────────────────────────────────────
    out_path = os.path.join(RESULTS_DIR, "best_params.json")
    with open(out_path, "w") as f:
        json.dump(best.params, f, indent=2)
    print(f"\n  Saved → {out_path}")

    # ── Quick summary ─────────────────────────────────────────────────────────
    p = best.params
    print(f"\n  ── Thresholds ──")
    print(f"    LONG  >= {p['long_thresh']:.3f}   (was 0.720)")
    print(f"    SHORT <= {p['short_thresh']:.3f}  (was 0.280)")
    print(f"\n  Rerun backtest.py to see full walk-forward results with tuned params.")

    return best.params


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--trials", type=int, default=50)
    args = ap.parse_args()
    run_tuning(n_trials=args.trials)
