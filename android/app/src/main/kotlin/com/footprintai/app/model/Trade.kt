package com.footprintai.app.model

/** 当前持仓（供图表 SL/TP 叠加层和 paper 账户使用）*/
data class OpenPosition(
    val direction:  Signal,
    val entryPrice: Double,
    val quantity:   Double,
    val openedAt:   Long,
    val stopLoss:   Double,
    val takeProfit: Double,
)

/** 已关闭的交易记录 */
data class TradeRecord(
    val id:          Long,
    val direction:   Signal,
    val entryPrice:  Double,
    val exitPrice:   Double,
    val quantity:    Double,
    val pnl:         Double,
    val openedAt:    Long,
    val closedAt:    Long,
    val closeReason: CloseReason,
    val strategy:    String = "",   // TradingView strategy name / symbol
)

enum class CloseReason { SIGNAL_FLIP, STOP_LOSS, TAKE_PROFIT, MAX_HOLD, TV_CLOSE }

/** 每日余额快照（权益曲线）*/
data class DailySnapshot(
    val date:    String,
    val balance: Double,
)

/** 纸仓账户完整状态 */
data class PaperAccount(
    val startBalance:    Double            = 200.0,
    val balance:         Double            = 200.0,
    val openPosition:    OpenPosition?     = null,
    val trades:          List<TradeRecord> = emptyList(),
    val dailySnapshots:  List<DailySnapshot> = emptyList(),
    val startedAt:       Long              = System.currentTimeMillis(),
    val totalTrades:     Int               = 0,
    val winTrades:       Int               = 0,
)
