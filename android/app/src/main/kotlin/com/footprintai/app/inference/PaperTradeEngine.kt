package com.footprintai.app.inference

import com.footprintai.app.data.PaperTradeStore
import com.footprintai.app.model.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * PaperTradeEngine
 * ────────────────
 * 规则：
 *  - 每次收到已关闭 K 线的信号时执行交易决策
 *  - 每笔交易使用余额的 50%（固定仓位）
 *  - 止损 3%，止盈 6%（相对入场价）
 *  - 同方向信号 → 持仓不变；反向信号 → 平仓 + 开新仓；NEUTRAL → 只检查止盈止损
 *  - livePrice() 在每根实时 tick 时调用，用于止盈止损检查
 */
class PaperTradeEngine(private val store: PaperTradeStore) {

    @Volatile
    private var account: PaperAccount = store.load()

    val state get() = account

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // ── 信号处理（每根已关闭 K 线触发）────────────────────────────────────────

    fun onSignal(signal: Signal, closePrice: Double, timestamp: Long) {
        var acc = account
        val pos = acc.openPosition

        when {
            // 无持仓 + 有方向信号 → 开新仓
            pos == null && signal != Signal.NEUTRAL -> {
                acc = acc.withOpen(signal, closePrice, timestamp)
            }
            // 有持仓 + 反向信号 → 平仓并开新仓
            pos != null && signal != Signal.NEUTRAL && signal != pos.direction -> {
                acc = acc.withClose(closePrice, timestamp, CloseReason.SIGNAL_FLIP)
                    .withOpen(signal, closePrice, timestamp)
            }
            // 其余（同向信号、NEUTRAL）→ 不操作，但仍记录日快照
        }

        acc = acc.withDailySnapshot()
        account = acc
        store.save(acc)
    }

    /** 每根实时 tick 调用（止盈止损检查） */
    fun onLivePrice(currentPrice: Double, timestamp: Long) {
        val pos = account.openPosition ?: return
        val reason = when {
            pos.direction == Signal.LONG  && currentPrice <= pos.stopLoss   -> CloseReason.STOP_LOSS
            pos.direction == Signal.LONG  && currentPrice >= pos.takeProfit -> CloseReason.TAKE_PROFIT
            pos.direction == Signal.SHORT && currentPrice >= pos.stopLoss   -> CloseReason.STOP_LOSS
            pos.direction == Signal.SHORT && currentPrice <= pos.takeProfit -> CloseReason.TAKE_PROFIT
            else -> return
        }
        val acc = account.withClose(currentPrice, timestamp, reason).withDailySnapshot()
        account = acc
        store.save(acc)
    }

    fun reset() {
        store.reset()
        account = PaperAccount()
    }

    // ── 账户变换（纯函数，不可变操作）────────────────────────────────────────

    private fun PaperAccount.withOpen(dir: Signal, price: Double, ts: Long): PaperAccount {
        val positionValue = balance * 0.50       // 每笔用 50% 余额
        val qty           = positionValue / price
        val sl = when (dir) {
            Signal.LONG  -> price * 0.97    // 止损 -3%
            Signal.SHORT -> price * 1.03
            else         -> price
        }
        val tp = when (dir) {
            Signal.LONG  -> price * 1.06    // 止盈 +6%
            Signal.SHORT -> price * 0.94
            else         -> price
        }
        return copy(
            openPosition = OpenPosition(dir, price, qty, ts, sl, tp)
        )
    }

    private fun PaperAccount.withClose(price: Double, ts: Long, reason: CloseReason): PaperAccount {
        val pos = openPosition ?: return this
        val pnl = when (pos.direction) {
            Signal.LONG  -> (price - pos.entryPrice) * pos.quantity
            Signal.SHORT -> (pos.entryPrice - price) * pos.quantity
            else         -> 0.0
        }
        val record = TradeRecord(
            id          = System.currentTimeMillis(),
            direction   = pos.direction,
            entryPrice  = pos.entryPrice,
            exitPrice   = price,
            quantity    = pos.quantity,
            pnl         = pnl,
            openedAt    = pos.openedAt,
            closedAt    = ts,
            closeReason = reason,
        )
        return copy(
            balance      = (balance + pnl).coerceAtLeast(0.0),
            openPosition = null,
            trades       = trades + record,
            totalTrades  = totalTrades + 1,
            winTrades    = winTrades + if (pnl > 0) 1 else 0,
        )
    }

    private fun PaperAccount.withDailySnapshot(): PaperAccount {
        val today = dateFmt.format(Date())
        // 当天已有快照则更新最后一条（当天收盘时余额变动）
        val updated = if (dailySnapshots.lastOrNull()?.date == today) {
            dailySnapshots.dropLast(1) + DailySnapshot(today, balance)
        } else {
            dailySnapshots + DailySnapshot(today, balance)
        }
        return copy(dailySnapshots = updated)
    }
}
