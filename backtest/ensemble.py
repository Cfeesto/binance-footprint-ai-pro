"""
ensemble.py
-----------
Four-model ensemble:
  1. Lorentzian Classification (KNN with Lorentzian distance, jdehorty algo)
  2. CatBoost
  3. XGBoost
  4. Random Forest

Returns per-candle probability of NEXT candle being bullish.
Ensemble signal = LONG if prob >= 0.90, SHORT if prob <= 0.10.
"""

import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.isotonic import IsotonicRegression
from sklearn.preprocessing import StandardScaler
from catboost import CatBoostClassifier
from xgboost import XGBClassifier
from features import FEATURE_COLS, LORENTZIAN_COLS

# Ensemble weights (must sum to 1.0)
WEIGHTS = {
    "lorentzian": 0.35,
    "catboost":   0.30,
    "xgboost":    0.25,
    "rf":         0.10,
}

SIGNAL_THRESHOLD_LONG  = 0.72
SIGNAL_THRESHOLD_SHORT = 0.28


# ─── Lorentzian Classification ────────────────────────────────────────────────

class LorentzianClassifier:
    """
    KNN classifier using Lorentzian distance:
        d(x, y) = sum( log(1 + |x_i - y_i|) )

    Ported from TradingView Pine Script by jdehorty.
    Uses a sliding window of the last `max_bars_back` training samples,
    with subsampling every `subsample` candles to speed up KNN search.
    """

    def __init__(self, k: int = 8, max_bars_back: int = 2000, subsample: int = 4):
        self.k = k
        self.max_bars_back = max_bars_back
        self.subsample = subsample
        self.scaler = StandardScaler()
        self._X_train = None
        self._y_train = None

    def fit(self, X: np.ndarray, y: np.ndarray):
        # Keep only subsampled training points
        idx = np.arange(0, len(X), self.subsample)
        self._X_train = self.scaler.fit_transform(X[idx])
        self._y_train = y[idx]
        return self

    def _lorentzian_dist(self, a: np.ndarray, B: np.ndarray) -> np.ndarray:
        """Vectorised: distance from single vector a to all rows of B."""
        return np.sum(np.log1p(np.abs(B - a)), axis=1)

    def predict_proba_single(self, x: np.ndarray) -> float:
        """Return bullish probability [0,1] for a single normalised feature vector."""
        x_s = self.scaler.transform(x.reshape(1, -1))[0]
        # Use only last max_bars_back training points
        X_win = self._X_train[-self.max_bars_back:]
        y_win = self._y_train[-self.max_bars_back:]
        dists = self._lorentzian_dist(x_s, X_win)
        nn_idx = np.argpartition(dists, self.k)[:self.k]
        return float(y_win[nn_idx].mean())

    def predict_proba(self, X: np.ndarray) -> np.ndarray:
        """Batch predict — used during walk-forward test phase."""
        X_s = self.scaler.transform(X)
        probs = []
        X_train_win = self._X_train[-self.max_bars_back:]
        y_train_win = self._y_train[-self.max_bars_back:]
        for x in X_s:
            dists  = self._lorentzian_dist(x, X_train_win)
            nn_idx = np.argpartition(dists, min(self.k, len(dists)-1))[:self.k]
            probs.append(float(y_train_win[nn_idx].mean()))
        return np.array(probs)


# ─── Tree Models ──────────────────────────────────────────────────────────────

def build_catboost(random_state: int = 42) -> CatBoostClassifier:
    return CatBoostClassifier(
        depth=6,
        iterations=500,
        learning_rate=0.05,
        early_stopping_rounds=50,
        eval_metric="AUC",
        random_seed=random_state,
        auto_class_weights="Balanced",
        verbose=0,
        thread_count=-1,
    )


def build_xgboost(random_state: int = 42) -> XGBClassifier:
    return XGBClassifier(
        max_depth=5,
        n_estimators=300,
        learning_rate=0.05,
        subsample=0.8,
        colsample_bytree=0.8,
        eval_metric="logloss",
        random_state=random_state,
        n_jobs=-1,
        verbosity=0,
    )


def build_rf(random_state: int = 42) -> RandomForestClassifier:
    return RandomForestClassifier(
        n_estimators=200,
        max_depth=8,
        min_samples_split=20,
        min_samples_leaf=10,
        class_weight="balanced",
        random_state=random_state,
        n_jobs=-1,
    )


# ─── Ensemble ─────────────────────────────────────────────────────────────────

class FourModelEnsemble:
    """
    Trains all four models on a training slice and predicts on a test slice.
    Returns a DataFrame of per-candle ensemble probabilities + signals.
    """

    def __init__(self, random_state: int = 42):
        self.rs = random_state
        self.lorentzian = LorentzianClassifier(k=8, max_bars_back=2000, subsample=4)
        self.catboost    = build_catboost(random_state)
        self.xgboost     = build_xgboost(random_state)
        self.rf          = build_rf(random_state)

    def fit(self, df_train: pd.DataFrame):
        X = df_train[FEATURE_COLS].values
        y = df_train["target"].values
        X_lor = df_train[LORENTZIAN_COLS].values

        # CatBoost: use last 20% as internal validation for early stopping
        split = int(len(X) * 0.8)
        X_tr, X_val = X[:split], X[split:]
        y_tr, y_val = y[:split], y[split:]

        print("    Fitting Lorentzian...")
        self.lorentzian.fit(X_lor, y)

        print("    Fitting CatBoost (+ isotonic calibration)...")
        self.catboost.fit(X_tr, y_tr, eval_set=(X_val, y_val))
        raw_cat = self.catboost.predict_proba(X_val)[:, 1]
        self._cat_iso = IsotonicRegression(out_of_bounds="clip")
        self._cat_iso.fit(raw_cat, y_val)

        print("    Fitting XGBoost (+ isotonic calibration)...")
        self.xgboost.fit(X_tr, y_tr, eval_set=[(X_val, y_val)], verbose=False)
        raw_xgb = self.xgboost.predict_proba(X_val)[:, 1]
        self._xgb_iso = IsotonicRegression(out_of_bounds="clip")
        self._xgb_iso.fit(raw_xgb, y_val)

        print("    Fitting Random Forest (+ isotonic calibration)...")
        self.rf.fit(X, y)
        raw_rf = self.rf.predict_proba(X_val)[:, 1]
        self._rf_iso = IsotonicRegression(out_of_bounds="clip")
        self._rf_iso.fit(raw_rf, y_val)

        return self

    def predict_proba(self, df_test: pd.DataFrame) -> pd.DataFrame:
        X     = df_test[FEATURE_COLS].values
        X_lor = df_test[LORENTZIAN_COLS].values

        p_lor = self.lorentzian.predict_proba(X_lor)
        p_cat = self._cat_iso.predict(self.catboost.predict_proba(X)[:, 1])
        p_xgb = self._xgb_iso.predict(self.xgboost.predict_proba(X)[:, 1])
        p_rf  = self._rf_iso.predict(self.rf.predict_proba(X)[:, 1])

        ensemble = (
            WEIGHTS["lorentzian"] * p_lor +
            WEIGHTS["catboost"]   * p_cat +
            WEIGHTS["xgboost"]    * p_xgb +
            WEIGHTS["rf"]         * p_rf
        )

        result = df_test[["open", "high", "low", "close",
                           "buy_volume", "sell_volume", "delta",
                           "target", "next_open", "next_close"]].copy()
        result["p_lorentzian"] = p_lor
        result["p_catboost"]   = p_cat
        result["p_xgboost"]    = p_xgb
        result["p_rf"]         = p_rf
        result["ensemble_prob"] = ensemble

        result["signal"] = "NONE"
        result.loc[ensemble >= SIGNAL_THRESHOLD_LONG,  "signal"] = "LONG"
        result.loc[ensemble <= SIGNAL_THRESHOLD_SHORT, "signal"] = "SHORT"

        # Win = direction predicted matches next candle's direction
        result["actual_bull"] = (result["next_close"] > result["next_open"]).astype(int)
        result["won"] = (
            ((result["signal"] == "LONG")  & (result["actual_bull"] == 1)) |
            ((result["signal"] == "SHORT") & (result["actual_bull"] == 0))
        ).astype(int)
        result["won"] = result.apply(
            lambda r: r["won"] if r["signal"] != "NONE" else np.nan, axis=1
        )

        return result


if __name__ == "__main__":
    print("ensemble.py — import OK, models defined")
    print(f"Weights: {WEIGHTS}")
    print(f"Signal threshold: LONG >= {SIGNAL_THRESHOLD_LONG}, SHORT <= {SIGNAL_THRESHOLD_SHORT}")
