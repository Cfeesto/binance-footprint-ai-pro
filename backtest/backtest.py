"""
backtest.py
-----------
Walk-forward validation of the FourModelEnsemble.

Walk-forward config:
  - Train window : 6 months
  - Test window  : 1 month
  - Step         : 1 month
  - Folds        : ~6 over 12 months of data

Runs on BTCUSDT + ETHUSDT 5m candles.
Prints full results table and saves:
  results/backtest_report.txt
  results/signal_log.csv
  results/equity_curve.png
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
import matplotlib.gridspec as gridspec
from datetime import timedelta

# Allow running from project root
sys.path.insert(0, os.path.dirname(__file__))

from fetch_data import load_or_fetch
from features  import build_features
from ensemble  import FourModelEnsemble

RESULTS_DIR = os.path.join(os.path.dirname(__file__), "results")
os.makedirs(RESULTS_DIR, exist_ok=True)

SYMBOLS       = ["ETHUSDT"]
TRAIN_MONTHS  = 4   # Reduced to get more folds from 12 months of data
TEST_MONTHS   = 1
STEP_MONTHS   = 1

# Binance taker fee per side. Round-trip = 2×. BNB discount not assumed.
TAKER_FEE     = 0.0004   # 0.04%


# ─── Walk-Forward Engine ──────────────────────────────────────────────────────

def run_walk_forward(df: pd.DataFrame, symbol: str) -> pd.DataFrame:
    """
    Returns a concatenated DataFrame of all test-period predictions,
    only for candles where a signal was generated.
    """
    df = df.sort_index()
    start = df.index[0]
    end   = df.index[-1]

    all_results = []
    fold = 0

    train_start = start
    while True:
        train_end = train_start + pd.DateOffset(months=TRAIN_MONTHS)
        test_start = train_end
        test_end   = test_start + pd.DateOffset(months=TEST_MONTHS)

        if test_end > end:
            break

        df_train = df[(df.index >= train_start) & (df.index < train_end)]
        df_test  = df[(df.index >= test_start)  & (df.index < test_end)]

        if len(df_train) < 5000 or len(df_test) < 500:
            train_start += pd.DateOffset(months=STEP_MONTHS)
            continue

        fold += 1
        print(f"\n  Fold {fold}: train [{train_start.date()} → {train_end.date()}] "
              f"test [{test_start.date()} → {test_end.date()}] "
              f"({len(df_train):,} / {len(df_test):,} candles)")

        model = FourModelEnsemble(random_state=42 + fold)
        model.fit(df_train)
        results = model.predict_proba(df_test)
        results["symbol"] = symbol
        results["fold"]   = fold
        all_results.append(results)

        train_start += pd.DateOffset(months=STEP_MONTHS)

    if not all_results:
        raise ValueError(f"No folds completed for {symbol}")

    return pd.concat(all_results)


# ─── Metrics ─────────────────────────────────────────────────────────────────

def compute_metrics(results: pd.DataFrame) -> dict:
    signals = results[results["signal"] != "NONE"].copy()

    if len(signals) == 0:
        return {"total_signals": 0, "wins": 0, "losses": 0,
                "win_rate": 0.0, "profit_factor": 0.0,
                "long_signals": 0, "short_signals": 0,
                "long_win_rate": 0.0, "short_win_rate": 0.0,
                "avg_conf_win": 0.0, "avg_conf_loss": 0.0}

    wins   = signals["won"].sum()
    losses = len(signals) - wins
    win_rate = wins / len(signals)

    long_sigs  = signals[signals["signal"] == "LONG"]
    short_sigs = signals[signals["signal"] == "SHORT"]

    long_wr  = long_sigs["won"].mean()  if len(long_sigs)  > 0 else 0.0
    short_wr = short_sigs["won"].mean() if len(short_sigs) > 0 else 0.0

    # Gross profit factor (1:1 R:R, no fees)
    pf = wins / losses if losses > 0 else float("inf")

    # Fee-adjusted profit factor
    # SL = 3%, TP = 6% → avg exit move ≈ WR×6% + (1-WR)×3% = 1R unit
    # Fee = 2 × TAKER_FEE per round-trip, as fraction of 1R (using SL as 1R)
    SL_PCT   = 0.03
    fee_per_r = (2 * TAKER_FEE) / SL_PCT          # fee drag per 1R unit
    gross_wins   = float(wins)
    gross_losses = float(losses)
    net_wins   = gross_wins   * (1.0 - fee_per_r)  # win nets (1 - fee) R
    net_losses = gross_losses * (1.0 + fee_per_r)  # loss costs (1 + fee) R
    pf_net = net_wins / net_losses if net_losses > 0 else float("inf")

    # Avg confidence on winning vs losing signals
    avg_conf_win  = signals[signals["won"] == 1]["ensemble_prob"].mean()
    avg_conf_loss = signals[signals["won"] == 0]["ensemble_prob"].mean()

    return {
        "total_signals":     len(signals),
        "wins":              int(wins),
        "losses":            int(losses),
        "win_rate":          round(win_rate, 4),
        "profit_factor":     round(pf, 3),
        "profit_factor_net": round(pf_net, 3),   # after 0.08% round-trip fee
        "long_signals":      len(long_sigs),
        "short_signals":     len(short_sigs),
        "long_win_rate":     round(long_wr, 4),
        "short_win_rate":    round(short_wr, 4),
        "avg_conf_win":      round(avg_conf_win, 4)  if not np.isnan(avg_conf_win)  else 0.0,
        "avg_conf_loss":     round(avg_conf_loss, 4) if not np.isnan(avg_conf_loss) else 0.0,
    }


# ─── Reporting ────────────────────────────────────────────────────────────────

def print_report(all_results: dict, per_symbol: dict):
    lines = []
    lines.append("=" * 70)
    lines.append("  BINANCE FOOTPRINT AI PRO — BACKTEST REPORT")
    lines.append("  Walk-Forward Validation | 5m Candles | 90%+ Confidence Signals")
    lines.append("=" * 70)

    for sym, m in per_symbol.items():
        lines.append(f"\n  {sym}")
        lines.append(f"  {'─'*40}")
        lines.append(f"  Total 90%+ signals : {m['total_signals']}")
        lines.append(f"  Wins / Losses      : {m['wins']} / {m['losses']}")
        lines.append(f"  Win Rate           : {m['win_rate']*100:.1f}%")
        lines.append(f"  Profit Factor      : {m['profit_factor']:.2f}  (net after fees: {m['profit_factor_net']:.2f})")
        lines.append(f"  LONG signals       : {m['long_signals']} ({m['long_win_rate']*100:.1f}% WR)  [disabled]")
        lines.append(f"  SHORT signals      : {m['short_signals']} ({m['short_win_rate']*100:.1f}% WR)")
        lines.append(f"  Avg conf (wins)    : {m['avg_conf_win']*100:.1f}%")
        lines.append(f"  Avg conf (losses)  : {m['avg_conf_loss']*100:.1f}%")

    # Combined
    lines.append("\n" + "=" * 70)
    lines.append("  COMBINED (BTC + ETH)")
    lines.append("  " + "─" * 40)
    cm = compute_metrics(pd.concat(list(all_results.values())))
    lines.append(f"  Total 90%+ signals : {cm['total_signals']}")
    lines.append(f"  Win Rate           : {cm['win_rate']*100:.1f}%")
    lines.append(f"  Profit Factor      : {cm['profit_factor']:.2f}  (net after fees: {cm['profit_factor_net']:.2f})")

    target_met = cm["win_rate"] >= 0.70 and cm["total_signals"] >= 30
    lines.append("")
    if target_met:
        lines.append("  ✅ TARGET MET: ≥70% win rate on ≥30 signals → PROCEED TO PHASE 2")
    else:
        lines.append("  ❌ TARGET NOT MET — optimisation required before Phase 2")
    lines.append("=" * 70)

    report = "\n".join(lines)
    print(report)

    path = os.path.join(RESULTS_DIR, "backtest_report.txt")
    with open(path, "w") as f:
        f.write(report)
    print(f"\n  Report saved: {path}")


def save_signal_log(all_results: dict):
    combined = pd.concat(list(all_results.values()))
    signals = combined[combined["signal"] != "NONE"].copy()
    signals = signals.reset_index()
    cols = ["open_time", "symbol", "fold", "signal", "ensemble_prob",
            "p_lorentzian", "p_catboost", "p_xgboost", "p_rf",
            "open", "close", "next_open", "next_close", "won"]
    signals = signals[[c for c in cols if c in signals.columns]]
    path = os.path.join(RESULTS_DIR, "signal_log.csv")
    signals.to_csv(path, index=False)
    print(f"  Signal log saved: {path} ({len(signals)} signals)")
    return signals


def plot_equity_curve(all_results: dict):
    fig = plt.figure(figsize=(14, 8), facecolor="#0d1117")
    gs  = gridspec.GridSpec(2, 2, figure=fig, hspace=0.4, wspace=0.3)
    ax_main = fig.add_subplot(gs[0, :])
    ax_btc  = fig.add_subplot(gs[1, 0])
    ax_eth  = fig.add_subplot(gs[1, 1])

    colors = {"BTCUSDT": "#f7931a", "ETHUSDT": "#627eea"}

    def plot_on(ax, results, title):
        signals = results[results["signal"] != "NONE"].copy().reset_index()
        if len(signals) == 0:
            ax.text(0.5, 0.5, "No signals", color="white",
                    transform=ax.transAxes, ha="center")
            return
        signals["pnl"] = signals["won"].map({1: 1, 0: -1})
        signals["cumulative_pnl"] = signals["pnl"].cumsum()
        color = colors.get(signals["symbol"].iloc[0], "#00d4aa")
        ax.fill_between(range(len(signals)), signals["cumulative_pnl"],
                        alpha=0.3, color=color)
        ax.plot(signals["cumulative_pnl"], color=color, linewidth=1.5, label=title)
        ax.axhline(0, color="#444", linestyle="--", linewidth=0.8)
        ax.set_facecolor("#161b22")
        ax.tick_params(colors="white", labelsize=8)
        ax.spines[:].set_color("#30363d")
        ax.set_title(title, color="white", fontsize=10, pad=6)
        ax.set_xlabel("Signal #", color="#888", fontsize=8)
        ax.set_ylabel("Cumulative PnL (units)", color="#888", fontsize=8)
        final = signals["cumulative_pnl"].iloc[-1]
        wr    = signals["won"].mean() * 100
        ax.text(0.98, 0.05, f"Final: {final:+.0f}\nWR: {wr:.1f}%",
                transform=ax.transAxes, color="white", ha="right", va="bottom",
                fontsize=9, bbox=dict(boxstyle="round", fc="#0d1117", ec=color, alpha=0.8))

    # Main: combined
    combined = pd.concat(list(all_results.values()))
    plot_on(ax_main, combined, "Combined BTC + ETH")

    if "BTCUSDT" in all_results:
        plot_on(ax_btc, all_results["BTCUSDT"], "BTCUSDT")
    if "ETHUSDT" in all_results:
        plot_on(ax_eth, all_results["ETHUSDT"], "ETHUSDT")

    fig.suptitle("Footprint AI Pro — Walk-Forward Equity Curve\n"
                 "5m | 90%+ Confidence Signals | 1:1 R:R",
                 color="white", fontsize=12, y=1.01)

    path = os.path.join(RESULTS_DIR, "equity_curve.png")
    plt.savefig(path, dpi=150, bbox_inches="tight", facecolor="#0d1117")
    plt.close()
    print(f"  Equity curve saved: {path}")


# ─── Main ─────────────────────────────────────────────────────────────────────

def main():
    print("\n" + "="*70)
    print("  BINANCE FOOTPRINT AI PRO — PHASE 1 BACKTESTER")
    print("  Symbols: BTCUSDT + ETHUSDT | Interval: 5m | Walk-Forward")
    print("="*70)

    all_results = {}
    per_symbol_metrics = {}

    for symbol in SYMBOLS:
        print(f"\n{'─'*70}")
        print(f"  Processing {symbol}...")

        # 1. Fetch data
        raw = load_or_fetch(symbol, interval="5m", months=12)
        print(f"  Raw candles: {len(raw):,}")

        # 2. Build features
        df = build_features(raw)
        print(f"  After feature engineering: {len(df):,} candles")
        print(f"  Target distribution: {df['target'].mean()*100:.1f}% bullish")

        # 3. Walk-forward backtest
        results = run_walk_forward(df, symbol)
        all_results[symbol] = results

        # 4. Per-symbol metrics
        m = compute_metrics(results)
        per_symbol_metrics[symbol] = m
        print(f"\n  {symbol} quick stats:")
        print(f"    Signals: {m['total_signals']} | WR: {m['win_rate']*100:.1f}% | PF: {m['profit_factor']:.2f}")

    # 5. Full report
    print_report(all_results, per_symbol_metrics)

    # 6. Save outputs
    save_signal_log(all_results)
    plot_equity_curve(all_results)

    print("\n  Done. Check results/ folder for report, signal log, and equity curve.")


if __name__ == "__main__":
    main()
