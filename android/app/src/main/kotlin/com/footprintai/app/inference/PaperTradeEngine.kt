package com.footprintai.app.inference

import com.footprintai.app.data.PaperTradeStore
import com.footprintai.app.model.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * PaperTradeEngine
 * ────────────────
 * 规则：
 *  - SHORT-only（LONG 禁用：9 个信号，55.6% WR，统计上无意义）
 *  - 仓位大小：固定风险 2% 余额 / 止损幅度（Kelly 启发）
 *    例：余额 $200，SL 3% → 风险 $4 → 仓位 $133 → qty = $133 / 入场价
 *  - 止损 3%，止盈 6%（相对入场价）
 *  - 止盈止损在每根实时 tick 检查（onLivePrice）
 *  - 并发安全：@Synchronized 保护 account 状态（IO 线程调用）
 */
class PaperTradeEngine(private val store: PaperTradeStore) {

    private var account: PaperAccount = store.load()

    val state get() = account

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // ── 信号处理（每根已关闭 K 线触发）────────────────────────────────────────

    @Synchronized
    fun onSignal(signal: Signal, closePrice: Double, timestamp: Long) {
        if (signal == Signal.LONG) return   // LONG disabled

        var acc = account
        val pos = acc.openPosition

        when {
            pos == null && signal == Signal.SHORT -> {
                acc = acc.withOpen(signal, closePrice, timestamp)
            }
            pos != null && signal != Signal.NEUTRAL && signal != pos.direction -> {
                acc = acc.withClose(closePrice, timestamp, CloseReason.SIGNAL_FLIP)
                    .withOpen(signal, closePrice, timestamp)
            }
        }

        acc = acc.withDailySnapshot()
        account = acc
        store.save(acc)
    }

    @Synchronized
    fun onLivePrice(currentPrice: Double, timestamp: Long) {
        val pos = account.openPosition ?: return
        val reason = when {
            pos.direction == Signal.SHORT && currentPrice >= pos.stopLoss   -> CloseReason.STOP_LOSS
            pos.direction == Signal.SHORT && currentPrice <= pos.takeProfit -> CloseReason.TAKE_PROFIT
            else -> return
        }
        val acc = account.withClose(currentPrice, timestamp, reason).withDailySnapshot()
        account = acc
        store.save(acc)
    }

    @Synchronized
    fun reset() {
        store.reset()
        account = PaperAccount()
    }

    // ── 账户变换（纯函数）────────────────────────────────────────────────────

    private fun PaperAccount.withOpen(dir: Signal, price: Double, ts: Long): PaperAccount {
        // 固定风险仓位：每笔最多亏 2% 余额
        // qty = (balance × 0.02) / (price × SL_PCT)
        val SL_PCT       = 0.03
        val riskDollars  = balance * 0.02
        val positionValue = riskDollars / SL_PCT        // e.g. $4 / 3% = $133
        val qty          = positionValue / price

        val sl = when (dir) {
            Signal.SHORT -> price * (1 + SL_PCT)        // SHORT: SL 在入场价上方
            Signal.LONG  -> price * (1 - SL_PCT)
            else         -> price
        }
        val tp = when (dir) {
            Signal.SHORT -> price * (1 - SL_PCT * 2)    // 止盈 = 2× 风险（6%）
            Signal.LONG  -> price * (1 + SL_PCT * 2)
            else         -> price
        }
        return copy(openPosition = OpenPosition(dir, price, qty, ts, sl, tp))
    }

    private fun PaperAccount.withClose(price: Double, ts: Long, reason: CloseReason): PaperAccount {
        val pos = openPosition ?: return this
        // 扣除双边手续费（Binance taker 0.04% × 2）
        val FEE = 0.0004
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
