package com.footprintai.app.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ─── Binance WS aggTrade 消息结构 ──────────────────────────────────────────

@JsonClass(generateAdapter = false)
data class AggTradeMsg(
    @Json(name = "p") val price: String,
    @Json(name = "q") val qty: String,
    @Json(name = "m") val isBuyerMaker: Boolean, // true=卖方主动, false=买方主动
)

// ─── Binance WS kline 消息结构 ─────────────────────────────────────────────

@JsonClass(generateAdapter = false)
data class BinanceKlineMsg(
    @Json(name = "e") val eventType: String,
    @Json(name = "k") val k: KlineData,
)

@JsonClass(generateAdapter = false)
data class KlineData(
    @Json(name = "t") val openTime:   Long,
    @Json(name = "o") val open:       String,
    @Json(name = "h") val high:       String,
    @Json(name = "l") val low:        String,
    @Json(name = "c") val close:      String,
    @Json(name = "v") val volume:     String,
    @Json(name = "V") val buyVolume:  String,
    @Json(name = "x") val isClosed:   Boolean,
)
