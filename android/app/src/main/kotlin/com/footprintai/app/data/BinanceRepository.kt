package com.footprintai.app.data

import com.footprintai.app.model.Kline
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tinder.scarlet.Scarlet
import com.tinder.scarlet.messageadapter.moshi.MoshiMessageAdapter
import com.tinder.scarlet.streamadapter.coroutines.CoroutinesStreamAdapterFactory
import com.tinder.scarlet.websocket.okhttp.newWebSocketFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class BinanceRepository(symbol: String = "ethusdt", interval: String = "5m") {

    private val url = "wss://stream.binance.com/ws/${symbol}@kline_$interval"

    private val okhttp = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val scarlet = Scarlet.Builder()
        .webSocketFactory(okhttp.newWebSocketFactory(url))
        .addMessageAdapterFactory(MoshiMessageAdapter.Factory(moshi))
        .addStreamAdapterFactory(CoroutinesStreamAdapterFactory())
        .build()

    private val service = scarlet.create<BinanceWsService>()

    /** 只发出已关闭的 5m K 线（isClosed = true）— 触发推断 */
    val closedKlines: Flow<Kline> = service.observeKline()
        .filter { it.k.isClosed }
        .map { msg ->
            val k = msg.k
            val vol    = k.volume.toDouble()
            val buyVol = k.buyVolume.toDouble()
            Kline(
                openTime  = k.openTime,
                open      = k.open.toDouble(),
                high      = k.high.toDouble(),
                low       = k.low.toDouble(),
                close     = k.close.toDouble(),
                volume    = vol,
                buyVolume = buyVol,
                isClosed  = true,
            )
        }

    /** 实时未关闭 K 线 — 用于图表更新 */
    val liveKlines: Flow<Kline> = service.observeKline()
        .map { msg ->
            val k = msg.k
            Kline(
                openTime  = k.openTime,
                open      = k.open.toDouble(),
                high      = k.high.toDouble(),
                low       = k.low.toDouble(),
                close     = k.close.toDouble(),
                volume    = k.volume.toDouble(),
                buyVolume = k.buyVolume.toDouble(),
                isClosed  = k.isClosed,
            )
        }
}
