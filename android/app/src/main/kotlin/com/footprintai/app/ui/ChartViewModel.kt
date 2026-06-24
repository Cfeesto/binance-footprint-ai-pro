package com.footprintai.app.ui

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.footprintai.app.data.BinanceRepository
import com.footprintai.app.data.PaperTradeStore
import com.footprintai.app.inference.FeatureBuilder
import com.footprintai.app.inference.OnnxInferenceEngine
import com.footprintai.app.inference.PaperTradeEngine
import com.footprintai.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.floor

data class ChartState(
    val klines:       List<Kline>           = emptyList(),
    val footprints:   List<FootprintCandle> = emptyList(),
    val lastResult:   InferenceResult?      = null,
    val error:        String?               = null,
    val engineReady:  Boolean               = false,
    val paperAccount: PaperAccount          = PaperAccount(),
)

class ChartViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ChartState())
    val state: StateFlow<ChartState> = _state.asStateFlow()

    private val repo        = BinanceRepository()   // ethusdt / 5m
    private val engine      = OnnxInferenceEngine(app)
    private val paperEngine = PaperTradeEngine(PaperTradeStore(app))
    private val window      = ArrayDeque<Kline>(300)

    // ponytail: single mutex guards all footprint state; upgrade to channel if perf needed
    companion object { const val TICK = 1.0 } // ETH: $1 per level
    private val mu         = Mutex()
    private val liveLevels = HashMap<Double, Pair<Float, Float>>()
    private val fpDeque    = ArrayDeque<FootprintCandle>(22)

    private val nm = app.getSystemService(NotificationManager::class.java).also {
        it.createNotificationChannel(
            NotificationChannel("signals", "Trade Signals", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    init {
        // 先加载历史再开 WS，避免图表空列竞态
        viewModelScope.launch(Dispatchers.IO) {
            initEngine()
            loadHistory()
            collectLiveKlines()
            collectClosedKlines()
            collectAggTrades()
        }
    }

    private suspend fun loadHistory() {
        try {
            repo.fetchHistory().forEach { window.addLast(it) }
            mu.withLock {
                window.takeLast(21).forEach { kline ->
                    fpDeque.addLast(FootprintCandle(
                        openTime = kline.openTime, open = kline.open, high = kline.high,
                        low = kline.low, close = kline.close, delta = 0f,
                        levels = emptyList(), isClosed = true,
                    ))
                }
            }
            _state.value = _state.value.copy(klines = window.toList())
        } catch (_: Exception) {}
    }

    private suspend fun initEngine() {
        try {
            engine.init()
            _state.value = _state.value.copy(engineReady = true, paperAccount = paperEngine.state)
        } catch (e: Exception) { _state.value = _state.value.copy(error = "Engine: ${e.message}") }
    }

    private fun collectLiveKlines() = viewModelScope.launch(Dispatchers.IO) {
        repo.liveKlines.catch { e -> _state.value = _state.value.copy(error = e.message) }
            .collect { kline ->
                if (window.isEmpty() || window.last().openTime != kline.openTime) {
                    if (window.size >= 300) window.removeFirst()
                    window.addLast(kline)
                } else { window[window.size - 1] = kline }
                paperEngine.onLivePrice(kline.close, kline.openTime)
                mu.withLock { emitFootprint(kline) }
                _state.value = _state.value.copy(klines = window.toList(), paperAccount = paperEngine.state)
            }
    }

    private fun collectClosedKlines() = viewModelScope.launch(Dispatchers.IO) {
        repo.closedKlines.catch { e -> _state.value = _state.value.copy(error = e.message) }
            .collect { kline ->
                mu.withLock {
                    fpDeque.addLast(buildFootprint(kline, isClosed = true))
                    if (fpDeque.size > 21) fpDeque.removeFirst()
                    liveLevels.clear()
                }
                if (!_state.value.engineReady) return@collect
                val features = FeatureBuilder.build(window.toList()) ?: return@collect
                val (signal, prob) = engine.infer(features)
                paperEngine.onSignal(signal, kline.close, kline.openTime)
                if (signal != Signal.NEUTRAL) notifySignal(signal, kline.close)
                try { repo.sendTradingSignal(signal.name, kline.close, kline.openTime) } catch (_: Exception) {}
                _state.value = _state.value.copy(
                    lastResult   = InferenceResult(signal, prob, kline),
                    paperAccount = paperEngine.state,
                )
            }
    }

    private fun collectAggTrades() = viewModelScope.launch(Dispatchers.IO) {
        repo.aggTrades.catch { e -> _state.value = _state.value.copy(error = e.message) }
            .collect { trade ->
                val price  = trade.price.toDoubleOrNull() ?: return@collect
                val qty    = trade.qty.toFloatOrNull()    ?: return@collect
                val bucket = floor(price / TICK) * TICK
                mu.withLock {
                    val (b, s) = liveLevels[bucket] ?: (0f to 0f)
                    liveLevels[bucket] = if (trade.isBuyerMaker) b to (s + qty) else (b + qty) to s
                    val liveKline = if (window.isNotEmpty()) window.last() else return@withLock
                    emitFootprint(liveKline)
                }
            }
    }

    private fun emitFootprint(liveKline: Kline) {
        _state.value = _state.value.copy(footprints = fpDeque.toList() + buildFootprint(liveKline, false))
    }

    private fun buildFootprint(kline: Kline, isClosed: Boolean) = FootprintCandle(
        openTime = kline.openTime, open = kline.open, high = kline.high,
        low      = kline.low,     close = kline.close,
        delta    = liveLevels.values.sumOf { (b, s) -> (b - s).toDouble() }.toFloat(),
        levels   = liveLevels.entries.map { (p, v) -> FootprintLevel(p, v.first, v.second) }
                              .sortedByDescending { it.price },
        isClosed = isClosed,
    )

    private fun notifySignal(signal: Signal, price: Double) {
        val ctx = getApplication<Application>()
        val (title, text) = when (signal) {
            Signal.LONG  -> "▲ LONG Signal" to "ETH/USDT \$%.1f".format(price)
            Signal.SHORT -> "▼ SHORT Signal" to "ETH/USDT \$%.1f".format(price)
            else -> return
        }
        nm.notify(signal.ordinal, Notification.Builder(ctx, "signals")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build())
    }

    fun resetPaperAccount() {
        paperEngine.reset()
        _state.value = _state.value.copy(paperAccount = paperEngine.state)
    }

    override fun onCleared() { super.onCleared(); engine.close() }
}
