package com.footprintai.app.data

import com.footprintai.app.model.Kline
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class BinanceRepository(private val symbol: String = "ethusdt", private val interval: String = "5m") {

    private val url = "wss://stream.binance.com/ws/${symbol}@kline_$interval"

    private val okhttp = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val adapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(BinanceKlineMsg::class.java)

    // 原始 K 线消息流 — 每次 collect 开一条 WebSocket 连接
    private fun klineFlow(): Flow<BinanceKlineMsg> = callbackFlow {
        val ws = okhttp.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    adapter.fromJson(text)?.let { trySend(it) }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    close(t)
                }
            }
        )
        awaitClose { ws.cancel() }
    }

    /** 只发出已关闭的 K 线 — 触发推断 */
    val closedKlines: Flow<Kline> = klineFlow()
        .filter { it.k.isClosed }
        .map { it.toKline() }

    /** 实时未关闭 K 线 — 用于图表更新 */
    val liveKlines: Flow<Kline> = klineFlow()
        .map { it.toKline() }

    /** REST 预加载历史 K 线，启动时调用避免空图表 */
    suspend fun fetchHistory(limit: Int = 200): List<Kline> {
        val restUrl = "https://api.binance.com/api/v3/klines?symbol=${symbol.uppercase()}&interval=$interval&limit=$limit"
        return withContext(Dispatchers.IO) {
            val body = okhttp.newCall(Request.Builder().url(restUrl).build()).execute().body!!.string()
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val r = arr.getJSONArray(i)
                Kline(
                    openTime  = r.getLong(0),
                    open      = r.getString(1).toDouble(),
                    high      = r.getString(2).toDouble(),
                    low       = r.getString(3).toDouble(),
                    close     = r.getString(4).toDouble(),
                    volume    = r.getString(5).toDouble(),
                    buyVolume = r.getString(9).toDouble(),
                    isClosed  = true,
                )
            }
        }
    }

    private fun BinanceKlineMsg.toKline() = Kline(
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
