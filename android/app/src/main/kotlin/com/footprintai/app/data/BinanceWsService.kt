package com.footprintai.app.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.tinder.scarlet.WebSocket
import com.tinder.scarlet.ws.Receive
import com.tinder.scarlet.ws.Send
import kotlinx.coroutines.flow.Flow

// ─── Binance WS kline 消息结构 ─────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class BinanceKlineMsg(
    @Json(name = "e") val eventType: String,
    @Json(name = "k") val k: KlineData,
)

@JsonClass(generateAdapter = true)
data class KlineData(
    @Json(name = "t") val openTime:   Long,
    @Json(name = "o") val open:       String,
    @Json(name = "h") val high:       String,
    @Json(name = "l") val low:        String,
    @Json(name = "c") val close:      String,
    @Json(name = "v") val volume:     String,
    @Json(name = "V") val buyVolume:  String,   // taker buy base asset volume
    @Json(name = "x") val isClosed:   Boolean,
)

/** Scarlet 服务接口 — 连接到 wss://stream.binance.com/ws/<symbol>@kline_5m */
interface BinanceWsService {
    @Receive
    fun observeWebSocketEvent(): Flow<WebSocket.Event>

    @Receive
    fun observeKline(): Flow<BinanceKlineMsg>

    @Send
    fun sendPing(msg: String)
}
