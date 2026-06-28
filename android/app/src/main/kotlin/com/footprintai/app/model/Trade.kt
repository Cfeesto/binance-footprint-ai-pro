package com.footprintai.app.model

/** 当前 MT5 持仓（供图表 SL/TP 叠加层使用）*/
data class OpenPosition(
    val direction:  Signal,
    val entryPrice: Double,
    val quantity:   Double,
    val openedAt:   Long,
    val stopLoss:   Double,
    val takeProfit: Double,
)
