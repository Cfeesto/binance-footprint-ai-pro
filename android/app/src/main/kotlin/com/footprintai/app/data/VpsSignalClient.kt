package com.footprintai.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * VpsSignalClient — connects to the VPS signal_server WebSocket.
 *
 * Emits [VpsSignal] on each incoming signal message.
 * Auto-reconnects on disconnect.
 *
 * Usage: call [connect] once; collect [signals] flow in ViewModel.
 */
class VpsSignalClient {

    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _signals = MutableSharedFlow<VpsSignal>(extraBufferCapacity = 64)
    val signals: SharedFlow<VpsSignal> = _signals.asSharedFlow()

    private var ws: WebSocket? = null
    private var currentUrl: String = ""

    /** Connect (or reconnect) to the given VPS WebSocket URL. */
    fun connect(wsUrl: String) {
        if (wsUrl == currentUrl && ws != null) return
        currentUrl = wsUrl
        ws?.cancel()
        scope.launch { reconnectLoop(wsUrl) }
    }

    fun disconnect() {
        ws?.cancel()
        ws = null
        currentUrl = ""
    }

    private suspend fun reconnectLoop(url: String) {
        while (url == currentUrl) {
            try {
                val req = Request.Builder().url(url).build()
                var connected = false
                ws = client.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) { connected = true }
                    override fun onMessage(ws: WebSocket, text: String) {
                        scope.launch { _signals.emit(parseSignal(text)) }
                    }
                    override fun onClosed(ws: WebSocket, code: Int, reason: String) { connected = false }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { connected = false }
                })
                // Wait while connected
                while (connected || !connected) {   // loop; reconnect on any close
                    delay(1000)
                    if (ws?.send("ping") == false) break
                }
            } catch (_: Exception) {}
            delay(5000)  // retry after 5s
        }
    }

    private fun parseSignal(raw: String): VpsSignal {
        return try {
            val j = JSONObject(raw)
            val paper = j.optJSONObject("paper")
            // parse mtf_signals object → Map<String, String>
            val mtfObj = j.optJSONObject("mtf_signals")
            val tfSignals = buildMap<String, String> {
                mtfObj?.keys()?.forEach { k -> put(k, mtfObj.optString(k, "NONE")) }
            }
            VpsSignal(
                type            = j.optString("type", "signal"),
                signal          = j.optString("signal", "NONE"),
                prob            = j.optDouble("prob", 0.5),
                close           = j.optDouble("close", 0.0),
                ts              = j.optLong("ts", 0L),
                atrPct          = j.optDouble("atr_pct", 0.02),
                liveMode        = j.optBoolean("live_mode", false),
                exchange        = j.optString("exchange", ""),
                paperBalance    = paper?.optDouble("balance", 10000.0) ?: 10000.0,
                paperRoiPct     = paper?.optDouble("roi_pct", 0.0) ?: 0.0,
                paperTrades     = paper?.optInt("total_trades", 0) ?: 0,
                paperWinRate    = paper?.optDouble("win_rate_pct", 0.0) ?: 0.0,
                canPromote      = (paper?.optInt("total_trades",0) ?: 0) >= 50 &&
                                  (paper?.optDouble("win_rate_pct",0.0) ?: 0.0) >= 52.0,
                confluenceScore = j.optInt("confluence_score", 0),
                confluenceMax   = j.optInt("confluence_max", 5),
                tfSignals       = tfSignals,
                symbol          = j.optString("symbol", "ETHUSDT"),
                interval        = j.optString("interval", "5m"),
            )
        } catch (_: Exception) {
            VpsSignal()
        }
    }
}

data class VpsSignal(
    val type:            String              = "signal",
    val signal:          String              = "NONE",   // LONG | SHORT | NONE
    val prob:            Double              = 0.5,
    val close:           Double              = 0.0,
    val ts:              Long                = 0L,
    val atrPct:          Double              = 0.02,
    val liveMode:        Boolean             = false,
    val exchange:        String              = "",
    val paperBalance:    Double              = 10000.0,
    val paperRoiPct:     Double              = 0.0,
    val paperTrades:     Int                 = 0,
    val paperWinRate:    Double              = 0.0,
    val canPromote:      Boolean             = false,
    val confluenceScore: Int                 = 0,
    val confluenceMax:   Int                 = 5,
    val tfSignals:       Map<String, String> = emptyMap(),
    val symbol:          String              = "ETHUSDT",
    val interval:        String              = "5m",
)
