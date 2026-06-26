package com.footprintai.app.inference

import com.footprintai.app.data.PaperTradeStore
import com.footprintai.app.model.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * PaperTradeEngine
 * ────────────────
 * 规则：
 *  - LONG 和 SHORT 均启用（9 LONG 样本已重新校准，ATR-scaled SL 降低假信号损失）
 *  - 仓位大小：固定风险 riskPct 余额 / 止损幅度（Kelly 启发）
 *  - 止损 = slAtrMult × ATR%（夹紧 1.5%-5%），止盈 = 2× 止损（2:1 R:R）
 *  - 追踪止损：最有利价格逐步收紧 SL
 *  - 最大持仓根数：maxHoldCandles（超时平仓）
 *  - 最大回撤熔断：余额跌至 startBalance × (1 - maxDrawdownPct) → 停止交易
 *  - 止盈止损在每根实时 tick 检查（onLivePrice）
 *  - 并发安全：@Synchronized 保护 account 状态（IO 线程调用）
 */
class PaperTradeEngine(private val store: PaperTradeStore) {

    private var account: PaperAccount = store.load()

    val state get() = account

    // ── 可运行时调整的参数（由 ChartViewModel 从 AppSettings 同步）──────────────
    var enableLong:       Boolean = true
    var riskPct:          Double  = 0.02        // 每笔最大亏损占余额比例
    var slAtrMult:        Float   = 1.5f         // SL = slAtrMult × ATR%
    var maxDrawdownPct:   Double  = 0.30         // 最大回撤 30%
    val maxHoldCandles:   Int     = 12           // 超时平仓根数（5m × 12 = 1h）

    private val dateFmt     = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // ── 追踪止损（不持久化，重启后从入场价重新追踪）────────────────────────────
    private var trailingBest: Double = Double.NaN
    private var trailSlPct:   Double = 0.03    // 追踪止损距离，动态由 withOpen 设定
    private var holdCandles:  Int    = 0        // 当前持仓已过 K 线数

    // ── 信号处理（每根已关闭 K 线触发）────────────────────────────────────────

    @Synchronized
    fun onSignal(signal: Signal, closePrice: Double, timestamp: Long, atrPct: Float = 0.02f) {
        // 最大回撤熔断：不再开新仓
        val isKilled = account.balance <= account.startBalance * (1 - maxDrawdownPct)

        var acc = account
        val pos = acc.openPosition

        // 持仓计数 + 超时平仓
        if (pos != null) {
            holdCandles++
            if (holdCandles >= maxHoldCandles) {
                acc = acc.withClose(closePrice, timestamp, CloseReason.MAX_HOLD).withDailySnapshot()
                account = acc
                store.save(acc)
                holdCandles = 0
                trailingBest = Double.NaN
                return
            }
        }

        val wantLong  = signal == Signal.LONG  && enableLong
        val wantShort = signal == Signal.SHORT

        when {
            // 开新仓
            pos == null && !isKilled && (wantLong || wantShort) -> {
                acc = acc.withOpen(signal, closePrice, timestamp, atrPct)
                holdCandles = 0
                trailingBest = closePrice
                trailSlPct = (slAtrMult * atrPct).toDouble().coerceIn(0.015, 0.05)
            }
            // 反向信号 → 平仓后开反向
            pos != null && !isKilled && signal != Signal.NEUTRAL && signal != pos.direction
                && ((signal == Signal.LONG && enableLong) || signal == Signal.SHORT) -> {
                acc = acc.withClose(closePrice, timestamp, CloseReason.SIGNAL_FLIP)
                          .withOpen(signal, closePrice, timestamp, atrPct)
                holdCandles = 0
                trailingBest = closePrice
                trailSlPct = (slAtrMult * atrPct).toDouble().coerceIn(0.015, 0.05)
            }
        }

        acc = acc.withDailySnapshot()
        account = acc
        store.save(acc)
    }

    @Synchronized
    fun onLivePrice(currentPrice: Double, timestamp: Long) {
        val pos = account.openPosition ?: return

        // 追踪止损更新
        if (!trailingBest.isNaN()) {
            if (pos.direction == Signal.SHORT) {
                if (currentPrice < trailingBest) trailingBest = currentPrice
            } else {
                if (currentPrice > trailingBest) trailingBest = currentPrice
            }
        } else {
            trailingBest = pos.entryPrice
        }

        // 有效 SL（追踪后 vs 原始 SL，取更优方向）
        val effectiveSL = if (pos.direction == Signal.SHORT) {
            // SHORT：最低价向上追踪 SL → 取更小的（保护更多利润）
            val trailSL = trailingBest * (1 + trailSlPct)
            minOf(pos.stopLoss, trailSL)    // 追踪 SL 向下收紧（不能上移）
        } else {
            // LONG：最高价向下追踪 SL → 取更大的
            val trailSL = trailingBest * (1 - trailSlPct)
            maxOf(pos.stopLoss, trailSL)
        }

        val reason = when {
            pos.direction == Signal.SHORT && currentPrice >= effectiveSL   -> CloseReason.STOP_LOSS
            pos.direction == Signal.SHORT && currentPrice <= pos.takeProfit -> CloseReason.TAKE_PROFIT
            pos.direction == Signal.LONG  && currentPrice <= effectiveSL   -> CloseReason.STOP_LOSS
            pos.direction == Signal.LONG  && currentPrice >= pos.takeProfit -> CloseReason.TAKE_PROFIT
            else -> return  // 无状态变化，不写磁盘
        }

        val acc = account.withClose(currentPrice, timestamp, reason).withDailySnapshot()
        account = acc
        store.save(acc)
        trailingBest = Double.NaN
        holdCandles  = 0
    }

    @Synchronized
    fun reset() {
        store.reset()
        account      = PaperAccount()
        trailingBest = Double.NaN
        holdCandles  = 0
    }

    // ── 账户变换（纯函数）────────────────────────────────────────────────────

    private fun PaperAccount.withOpen(dir: Signal, price: Double, ts: Long, atrPct: Float): PaperAccount {
        // ATR 自适应止损：slAtrMult × ATR%，夹紧 1.5%-5%
        val slPct       = (slAtrMult * atrPct).toDouble().coerceIn(0.015, 0.05)
        val tpPct       = slPct * 2.0                   // 2:1 R:R
        val riskDollars  = balance * riskPct
        val positionValue = riskDollars / slPct
        val qty          = positionValue / price

        val sl = when (dir) {
            Signal.SHORT -> price * (1 + slPct)
            Signal.LONG  -> price * (1 - slPct)
            else         -> price
        }
        val tp = when (dir) {
            Signal.SHORT -> price * (1 - tpPct)
            Signal.LONG  -> price * (1 + tpPct)
            else         -> price
        }
        return copy(openPosition = OpenPosition(dir, price, qty, ts, sl, tp))
    }

    private fun PaperAccount.withClose(price: Double, ts: Long, reason: CloseReason): PaperAccount {
        val pos = openPosition ?: return this
        val FEE = 0.0004    // Binance taker 0.04% × 开 + 关
        val grossPnl = when (pos.direction) {
            Signal.LONG  -> (price - pos.entryPrice) * pos.quantity
            Signal.SHORT -> (pos.entryPrice - price) * pos.quantity
            else         -> 0.0
        }
        val feeAmount = pos.quantity * pos.entryPrice * FEE * 2
        val netPnl    = grossPnl - feeAmount

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
        val updated = if (dailySnapshots.lastOrNull()?.date == today) {
            dailySnapshots.dropLast(1) + DailySnapshot(today, balance)
        } else {
            dailySnapshots + DailySnapshot(today, balance)
        }
        return copy(dailySnapshots = updated)
    }
}
