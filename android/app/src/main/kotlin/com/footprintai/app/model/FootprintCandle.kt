package com.footprintai.app.model

/** 单价位的买卖量 */
data class FootprintLevel(
    val price: Double,     // 价格区间下沿（区间 = price … price + tickSize）
    val buyVol: Float,     // 主动买量
    val sellVol: Float,    // 主动卖量
) {
    val delta get() = buyVol - sellVol
    val isBuyDominant get() = buyVol >= sellVol
}

/** 带订单流数据的K线 */
data class FootprintCandle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val delta: Float,                    // 全K线净delta
    val levels: List<FootprintLevel>,    // 按价格降序
    val isClosed: Boolean,
)
