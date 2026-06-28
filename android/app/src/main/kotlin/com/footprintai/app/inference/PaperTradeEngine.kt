package com.footprintai.app.inference

import com.footprintai.app.data.PaperTradeStore
import com.footprintai.app.model.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * PaperTradeEngine
 * ────────────────
 * Executes paper trades driven by TradingView webhook signals (or ML signals).
 * Rules:
 *  - One position at a time
 *  - SL = slAtrMult × ATR% (clamped 1%-5%), TP = 2× SL
 *  - Trailing stop: tightens SL toward best price
 *  - Max hold: maxHoldCandles (timeout close)
 *  - Max drawdown circuit breaker
 *  - @Synchronized: called from IO coroutine, state is shared
 */
class PaperTradeEngine(private val store: PaperTradeStore) {

    private var account: PaperAccount = store.load()

    val state get() = account

    var enableLong:     Boolean = true
    var riskPct:        Double  = 0.02
    var slAtrMult:      Float   = 1.5f
    var maxDrawdownPct: Double  = 0.30
    val maxHoldCandles: Int     = 12

    private val dateFmt     = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private var trailingBest: Double = Double.NaN
    private var trailSlPct:   Double = 0.03
    private var holdCandles:  Int    = 0

    // ── TradingView signal (buy / sell / close) ───────────────────────────────

    @Synchronized
    fun onTvSignal(
        action:    String,   // "buy" | "sell" | "close"
        price:     Double,
        timestamp: Long,
        strategy:  String = "",
        atrPct:    Float  = 0.02f,
    ) {
        val signal = when (action.lowercase()) {
            "buy"   -> Signal.LONG
            "sell"  -> Signal.SHORT
            "close" -> {
                account.openPosition?.let { closePosition(price, timestamp, CloseReason.TV_CLOSE) }
                return
            }
            else -> return
        }
        onSignal(signal, price, timestamp, atrPct, strategy)
    }

    // ── ML / internal signal ──────────────────────────────────────────────────

    @Synchronized
    fun onSignal(
        signal:    Signal,
        closePrice: Double,
        timestamp: Long,
        atrPct:    Float = 0.02f,
        strategy:  String = "",
    ) {
        val isKilled = account.balance <= account.startBalance * (1 - maxDrawdownPct)
        var acc = account
        val pos = acc.openPosition

        if (pos != null) {
            holdCandles++
            if (holdCandles >= maxHoldCandles) {
                acc = acc.withClose(closePrice, timestamp, CloseReason.MAX_HOLD, strategy)
                    .withDailySnapshot()
                account = acc; store.save(acc)
                holdCandles = 0; trailingBest = Double.NaN
                return
            }
        }

        val wantLong  = signal == Signal.LONG  && enableLong
        val wantShort = signal == Signal.SHORT

        when {
            pos == null && !isKilled && (wantLong || wantShort) -> {
                acc = acc.withOpen(signal, closePrice, timestamp, atrPct, strategy)
                holdCandles = 0; trailingBest = closePrice
                trailSlPct = (slAtrMult * atrPct).toDouble().coerceIn(0.01, 0.05)
            }
            pos != null && !isKilled && signal != Signal.NEUTRAL && signal != pos.direction
                && ((signal == Signal.LONG && enableLong) || signal == Signal.SHORT) -> {
                acc = acc.withClose(closePrice, timestamp, CloseReason.SIGNAL_FLIP, strategy)
                          .withOpen(signal, closePrice, timestamp, atrPct, strategy)
                holdCandles = 0; trailingBest = closePrice
                trailSlPct = (slAtrMult * atrPct).toDouble().coerceIn(0.01, 0.05)
            }
        }

        acc = acc.withDailySnapshot()
        account = acc; store.save(acc)
    }

    @Synchronized
    fun onLivePrice(currentPrice: Double, timestamp: Long) {
        val pos = account.openPosition ?: return

        if (!trailingBest.isNaN()) {
            if (pos.direction == Signal.SHORT) { if (currentPrice < trailingBest) trailingBest = currentPrice }
            else                               { if (currentPrice > trailingBest) trailingBest = currentPrice }
        } else { trailingBest = pos.entryPrice }

        val effectiveSL = if (pos.direction == Signal.SHORT) {
            minOf(pos.stopLoss, trailingBest * (1 + trailSlPct))
        } else {
            maxOf(pos.stopLoss, trailingBest * (1 - trailSlPct))
        }

        val reason = when {
            pos.direction == Signal.SHORT && currentPrice >= effectiveSL    -> CloseReason.STOP_LOSS
            pos.direction == Signal.SHORT && currentPrice <= pos.takeProfit -> CloseReason.TAKE_PROFIT
            pos.direction == Signal.LONG  && currentPrice <= effectiveSL    -> CloseReason.STOP_LOSS
            pos.direction == Signal.LONG  && currentPrice >= pos.takeProfit -> CloseReason.TAKE_PROFIT
            else -> return
        }

        val acc = account.withClose(currentPrice, timestamp, reason).withDailySnapshot()
        account = acc; store.save(acc)
        trailingBest = Double.NaN; holdCandles = 0
    }

    @Synchronized
    fun reset() {
        store.reset(); account = PaperAccount()
        trailingBest = Double.NaN; holdCandles = 0
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun closePosition(price: Double, timestamp: Long, reason: CloseReason) {
        val acc = account.withClose(price, timestamp, reason).withDailySnapshot()
        account = acc; store.save(acc)
        trailingBest = Double.NaN; holdCandles = 0
    }

    private fun PaperAccount.withOpen(dir: Signal, price: Double, ts: Long, atrPct: Float, strat: String): PaperAccount {
        val slPct       = (slAtrMult * atrPct).toDouble().coerceIn(0.01, 0.05)
        val tpPct       = slPct * 2.0
        val riskDollars = balance * riskPct
        val qty         = (riskDollars / slPct) / price

        val sl = if (dir == Signal.LONG) price * (1 - slPct) else price * (1 + slPct)
        val tp = if (dir == Signal.LONG) price * (1 + tpPct) else price * (1 - tpPct)
        return copy(openPosition = OpenPosition(dir, price, qty, ts, sl, tp))
    }

    private fun PaperAccount.withClose(price: Double, ts: Long, reason: CloseReason, strat: String = ""): PaperAccount {
        val pos = openPosition ?: return this
        val grossPnl = if (pos.direction == Signal.LONG) (price - pos.entryPrice) * pos.quantity
                       else                               (pos.entryPrice - price) * pos.quantity
        val fee    = pos.quantity * pos.entryPrice * 0.0004 * 2  // 0.04% taker both sides
        val netPnl = grossPnl - fee

        val record = TradeRecord(
            id          = System.currentTimeMillis(),
            direction   = pos.direction,
            entryPrice  = pos.entryPrice,
            exitPrice   = price,
            quantity    = pos.quantity,
            pnl         = netPnl,
            openedAt    = pos.openedAt,
            closedAt    = ts,
            closeReason = reason,
            strategy    = strat,
        )
        return copy(
            balance      = (balance + netPnl).coerceAtLeast(0.0),
            openPosition = null,
            trades       = trades + record,
            totalTrades  = totalTrades + 1,
            winTrades    = winTrades + if (netPnl > 0) 1 else 0,
        )
    }

    private fun PaperAccount.withDailySnapshot(): PaperAccount {
        val today = dateFmt.format(Date())
        val updated = if (dailySnapshots.lastOrNull()?.date == today)
            dailySnapshots.dropLast(1) + DailySnapshot(today, balance)
        else
            dailySnapshots + DailySnapshot(today, balance)
        return copy(dailySnapshots = updated)
    }
}
