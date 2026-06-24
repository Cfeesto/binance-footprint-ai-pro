package com.footprintai.app.data

import com.footprintai.app.BuildConfig
import com.footprintai.app.model.Kline
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class BinanceRepository(
    val symbol: String = "ethusdt",
    val interval: String = "5m",
) {
    // ponytail: no ping — Binance WS drops idle connections; reconnect via retry() on the flow instead
    private val okhttp = OkHttpClient.Builder().build()
    private val moshi  = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val klineAdapter = moshi.adapter(BinanceKlineMsg::class.java)
    private val tradeAdapter = moshi.adapter(AggTradeMsg::class.java)
    private val backendBaseUrl = BuildConfig.BACKEND_BASE_URL
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun wsFlow(stream: String): Flow<String> = callbackFlow {
        val ws = okhttp.newWebSocket(
            Request.Builder().url("wss://stream.binance.com/ws/$stream").build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { trySend(text) }
                override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) { close(t) }
            }
        )
        awaitClose { ws.cancel() }
    }.retry() // 断线自动重连

    private fun klineFlow() = wsFlow("${symbol}@kline_$interval")
        .map { klineAdapter.fromJson(it) }.filter { it != null }.map { it!! }

    val closedKlines: Flow<Kline> = klineFlow().filter { it.k.isClosed }.map { it.toKline() }
    val liveKlines:   Flow<Kline> = klineFlow().map { it.toKline() }

    val aggTrades: Flow<AggTradeMsg> = wsFlow("${symbol}@aggTrade")
        .map { tradeAdapter.fromJson(it) }.filter { it != null }.map { it!! }

    suspend fun fetchHistory(limit: Int = 200): List<Kline> = withContext(Dispatchers.IO) {
        val url = "https://api.binance.com/api/v3/klines?symbol=${symbol.uppercase()}&interval=$interval&limit=$limit"
        val body = okhttp.newCall(Request.Builder().url(url).build()).execute().body!!.string()
        val arr = JSONArray(body)
        (0 until arr.length()).map { i ->
            val r = arr.getJSONArray(i)
            Kline(r.getLong(0), r.getString(1).toDouble(), r.getString(2).toDouble(),
                  r.getString(3).toDouble(), r.getString(4).toDouble(),
                  r.getString(5).toDouble(), r.getString(9).toDouble(), isClosed = true)
        }
    }

    suspend fun sendTradingSignal(signal: String, price: Double, timestamp: Long) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("symbol", symbol.uppercase()); put("signal", signal)
            put("price", price); put("timestamp", timestamp)
        }.toString().toRequestBody(JSON)
        okhttp.newCall(Request.Builder().url("$backendBaseUrl/signal").post(body).build())
            .execute().use { if (!it.isSuccessful) throw IOException("Unexpected code $it") }
    }

    suspend fun getAccountStatus(): String = withContext(Dispatchers.IO) {
        okhttp.newCall(Request.Builder().url("$backendBaseUrl/account_status").build())
            .execute().use { r ->
                if (!r.isSuccessful) throw IOException("Unexpected code $r")
                r.body?.string() ?: "{}"
            }
    }

    private fun BinanceKlineMsg.toKline() = Kline(
        openTime  = k.openTime,  open      = k.open.toDouble(),
        high      = k.high.toDouble(),  low = k.low.toDouble(),
        close     = k.close.toDouble(), volume = k.volume.toDouble(),
        buyVolume = k.buyVolume.toDouble(), isClosed = k.isClosed,
    )
}
