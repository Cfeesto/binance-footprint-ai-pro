package com.footprintai.app.model

/** 已关闭的交易记录 */
data class TradeRecord(
    val id:           Long,
    val direction:    Signal,         // LONG / SHORT
    val entryPrice:   Double,
    val exitPrice:    Double,
    val quantity:     Double,         // ETH 数量
    val pnl:          Double,         // 已实现盈亏（美元）
    val openedAt:     Long,           // 毫秒时间戳
    val closedAt:     Long,
    val closeReason:  CloseReason,
)

enum class CloseReason { SIGNAL_FLIP, STOP_LOSS, TAKE_PROFIT, MAX_HOLD }

/** 当前持仓（只允许一个）*/
data class OpenPosition(
    val direction:  Signal,
    val entryPrice: Double,
    val quantity:   Double,     // ETH 数量
    val openedAt:   Long,
    val stopLoss:   Double,     // 触发价格（亏损 3%）
    val takeProfit: Double,     // 触发价格（盈利 6%）
)

/** 每日余额快照（用于权益曲线）*/
data class DailySnapshot(
    val date:    String,    // "2026-06-24"
    val balance: Double,
)

/** 完整的纸仓状态 */
data class PaperAccount(
    val startBalance:    Double         = 200.0,
    val balance:         Double         = 200.0,
    val openPosition:    OpenPosition?  = null,
    val trades:          List<TradeRecord> = emptyList(),
    val dailySnapshots:  List<DailySnapshot> = emptyList(),
    val startedAt:       Long           = System.currentTimeMillis(),
    /** 总交易次数 */
    val totalTrades:     Int            = 0,
    val winTrades:       Int            = 0,
)
