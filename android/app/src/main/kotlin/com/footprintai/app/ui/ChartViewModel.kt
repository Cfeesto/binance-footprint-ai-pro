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
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.floor

data class AdxPoint(val adx: Float, val plusDi: Float, val minusDi: Float)

data class IndicatorState(
    val bbUpper:     Double,
    val bbLower:     Double,
    val bbMid:       Double,
    val adx:         Float,
    val plusDi:      Float,
    val minusDi:     Float,
    val atrPct:      Float,
    val shortThresh: Float = 0.211f,
    val longThresh:  Float = 0.680f,
)

data class ChartState(
    val klines:       List<Kline>           = emptyList(),
    val footprints:   List<FootprintCandle> = emptyList(),
    val lastResult:   InferenceResult?      = null,
    val error:        String?               = null,
    val engineReady:  Boolean               = false,
    val paperAccount: PaperAccount          = PaperAccount(),
    val cvd:          List<Float>           = emptyList(),  // 累积delta，每根K线一个值（含实时）
    val adxHistory:   List<AdxPoint>        = emptyList(),  // 每根收盘K线一个点
    val indicators:   IndicatorState?       = null,         // 最新指标快照
)

class ChartViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ChartState())
    val state: StateFlow<ChartState> = _state.asStateFlow()

    private val repo        = BinanceRepository()
    private val engine      = OnnxInferenceEngine(app)
    private val paperEngine = PaperTradeEngine(PaperTradeStore(app))
    private val window      = ArrayDeque<Kline>(300)

    // ponytail: single mutex guards all footprint state
    companion object { const val TICK = 1.0 } // ETH: $1 per level
    private val mu         = Mutex()
    private val liveLevels = HashMap<Double, Pair<Float, Float>>()
    private val adxDeque   = ArrayDeque<AdxPoint>(22)
    private val fpDeque    = ArrayDeque<FootprintCandle>(22)
    private val cvdDeque   = ArrayDeque<Float>(22)  // 已收盘K线的累积delta序列

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
                    cvdDeque.addLast(cvdDeque.lastOrNull() ?: 0f)  // 历史无交易数据，delta=0
                }
            }
            _state.update { it.copy(klines = window.toList()) }
        } catch (_: Exception) {}
    }

    private suspend fun initEngine() {
        try {
            engine.init()
            _state.update { it.copy(engineReady = true, paperAccount = paperEngine.state) }
        } catch (e: Exception) { _state.update { it.copy(error = "Engine: ${e.message}") } }
    }

    private fun collectLiveKlines() = viewModelScope.launch(Dispatchers.IO) {
        repo.liveKlines.catch { e -> _state.update { it.copy(error = e.message) } }
            .collect { kline ->
                if (window.isEmpty() || window.last().openTime != kline.openTime) {
                    if (window.size >= 300) window.removeFirst()
                    window.addLast(kline)
                } else { window[window.size - 1] = kline }
                paperEngine.onLivePrice(kline.close, kline.openTime)
                _state.update { it.copy(klines = window.toList(), paperAccount = paperEngine.state) }
                // 足迹状态由 collectAggTrades 的 100ms ticker 刷新，不在此触发
            }
    }

    private fun collectClosedKlines() = viewModelScope.launch(Dispatchers.IO) {
        repo.closedKlines.catch { e -> _state.update { it.copy(error = e.message) } }
            .collect { kline ->
                mu.withLock {
                    val closed = buildFootprint(kline, isClosed = true)
                    fpDeque.addLast(closed)
                    if (fpDeque.size > 21) fpDeque.removeFirst()
                    // 更新累积 CVD：上根收盘累积值 + 本根 delta
                    val runningCvd = (cvdDeque.lastOrNull() ?: 0f) + closed.delta
                    cvdDeque.addLast(runningCvd)
                    if (cvdDeque.size > 21) cvdDeque.removeFirst()
                    liveLevels.clear()
                }
                if (!_state.value.engineReady) return@collect
                val features = FeatureBuilder.build(window.toList()) ?: return@collect
                val out = engine.infer(features)
                paperEngine.onSignal(out.signal, kline.close, kline.openTime)
                if (out.signal == Signal.SHORT) notifySignal(out.signal, kline.close)
                try { repo.sendTradingSignal(out.signal.name, kline.close, kline.openTime) } catch (_: Exception) {}

                // 从特征向量中提取指标值（索引与 FeatureBuilder.kt 一致）
                // [17]=adx14Norm(×100→0-100), [18]=plusDi, [19]=minusDi, [23]=atr14Pct, [24]=bbPos
                val adxVal    = features[17] * 100f
                val plusDi    = features[18]
                val minusDi   = features[19]
                val atrPct    = features[23]
                val adxPoint  = AdxPoint(adxVal, plusDi, minusDi)
                mu.withLock {
                    adxDeque.addLast(adxPoint)
                    if (adxDeque.size > 21) adxDeque.removeFirst()
                }

                // 计算 Bollinger Bands（20根收盘价，stddev）
                val closes    = window.takeLast(20).map { it.close }
                val bbMid     = closes.average()
                val variance  = closes.sumOf { (it - bbMid) * (it - bbMid) } / closes.size
                val bbStd     = sqrt(variance)
                val bbUpper   = bbMid + 2.0 * bbStd
                val bbLower   = bbMid - 2.0 * bbStd

                val indicators = IndicatorState(
                    bbUpper     = bbUpper,
                    bbLower     = bbLower,
                    bbMid       = bbMid,
                    adx         = adxVal,
                    plusDi      = plusDi,
                    minusDi     = minusDi,
                    atrPct      = atrPct,
                    shortThresh = engine.shortThresh,
                    longThresh  = engine.longThresh,
                )

                _state.update { it.copy(
                    lastResult   = InferenceResult(out.signal, out.ensemble, out.probCat, out.probXgb, out.probRf, kline),
                    paperAccount = paperEngine.state,
                    adxHistory   = adxDeque.toList(),
                    indicators   = indicators,
                ) }
            }
    }

    private fun collectAggTrades() = viewModelScope.launch(Dispatchers.IO) {
        // 100ms 节流 ticker：aggTrade 仅更新 liveLevels，ticker 统一刷新图表状态
        // 原每笔触发 → ~500-1000次/分钟重绘；节流后 → 10次/秒
        launch {
            while (true) {
                delay(100)
                mu.withLock {
                    val liveKline = window.lastOrNull() ?: return@withLock
                    val liveCandle = buildFootprint(liveKline, false)
                    val prevCvd = cvdDeque.lastOrNull() ?: 0f
                    _state.update { it.copy(
                        footprints = fpDeque.toList() + liveCandle,
                        cvd        = cvdDeque.toList() + (prevCvd + liveCandle.delta),
                    ) }
                }
            }
        }
        repo.aggTrades.catch { e -> _state.update { it.copy(error = e.message) } }
            .collect { trade ->
                val price  = trade.price.toDoubleOrNull() ?: return@collect
                val qty    = trade.qty.toFloatOrNull()    ?: return@collect
                val bucket = floor(price / TICK) * TICK
                mu.withLock {
                    val (b, s) = liveLevels[bucket] ?: (0f to 0f)
                    liveLevels[bucket] = if (trade.isBuyerMaker) b to (s + qty) else (b + qty) to s
                    // 不在此 emit，由 ticker 统一刷新
                }
            }
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
        _state.update { it.copy(paperAccount = paperEngine.state) }
    }

    override fun onCleared() { super.onCleared(); engine.close() }
}
