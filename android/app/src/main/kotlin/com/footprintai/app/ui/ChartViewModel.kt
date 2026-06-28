package com.footprintai.app.ui

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.footprintai.app.data.AppSettings
import com.footprintai.app.data.BinanceRepository
import com.footprintai.app.data.MetaApiClient
import com.footprintai.app.data.MtAccountInfo
import com.footprintai.app.data.MtDeal
import com.footprintai.app.data.MtPosition
import com.footprintai.app.data.PaperTradeStore
import com.footprintai.app.inference.FeatureBuilder
import com.footprintai.app.inference.LiveTradeEngine
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
    val shortThresh: Float = 0.25f,
    val longThresh:  Float = 0.64f,
)

data class AppSettingsState(
    val shortThresh:       Float   = 0.25f,
    val longThresh:        Float   = 0.64f,
    val riskPct:           Float   = 0.02f,
    val slAtrMult:         Float   = 1.5f,
    val enableLong:        Boolean = true,
    val maxDrawdownPct:    Float   = 0.30f,
    val metaApiToken:      String  = "",
    val metaApiAccountId:  String  = "",
    val metaApiRegion:     String  = "new-york",
    val tradingSymbol:     String  = "ETHUSD",
    val tvWebhookKey:      String  = "",
)

data class ChartState(
    val klines:       List<Kline>           = emptyList(),
    val footprints:   List<FootprintCandle> = emptyList(),
    val lastResult:   InferenceResult?      = null,
    val error:        String?               = null,
    val engineReady:  Boolean               = false,
    // ── Paper trading (TradingView webhook) ──────────────────────────────────
    val paperAccount: PaperAccount          = PaperAccount(),
    // ── Live MT5 (MetaApi) ───────────────────────────────────────────────────
    val mtAccount:    MtAccountInfo?        = null,
    val mtPositions:  List<MtPosition>      = emptyList(),
    val mtDeals:      List<MtDeal>          = emptyList(),
    val mtConnected:  Boolean               = false,
    val mtError:      String?               = null,
    // ── Chart indicators ─────────────────────────────────────────────────────
    val cvd:          List<Float>           = emptyList(),
    val adxHistory:   List<AdxPoint>        = emptyList(),
    val indicators:   IndicatorState?       = null,
    val signalLog:    List<InferenceResult> = emptyList(),
    val appSettings:  AppSettingsState      = AppSettingsState(),
)

/** Converts the first MT5 position to OpenPosition for chart SL/TP overlay */
fun ChartState.chartPosition(): OpenPosition? {
    val pos = mtPositions.firstOrNull() ?: return null
    val dir = when {
        pos.type.contains("BUY")  -> Signal.LONG
        pos.type.contains("SELL") -> Signal.SHORT
        else -> return null
    }
    val sl = pos.stopLoss  ?: if (dir == Signal.LONG) pos.openPrice * 0.97 else pos.openPrice * 1.03
    val tp = pos.takeProfit ?: if (dir == Signal.LONG) pos.openPrice * 1.06 else pos.openPrice * 0.94
    return OpenPosition(dir, pos.openPrice, pos.volume, 0L, sl, tp)
}

class ChartViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ChartState())
    val state: StateFlow<ChartState> = _state.asStateFlow()

    private val settings     = AppSettings(app)
    private val repo         = BinanceRepository()
    private val engine       = OnnxInferenceEngine(app)
    private val paperEngine  = PaperTradeEngine(PaperTradeStore(app))

    // ponytail: both nullable — only set when credentials are present
    private var mtClient:   MetaApiClient?   = null
    private var liveEngine: LiveTradeEngine? = null

    // ponytail: receivedAt dedup — ignore signal we've already processed
    private var lastTvReceivedAt: Long = 0L

    private val window     = ArrayDeque<Kline>(300)
    companion object { const val TICK = 1.0 }
    private val mu         = Mutex()
    private val liveLevels = HashMap<Double, Pair<Float, Float>>(512)
    private val adxDeque   = ArrayDeque<AdxPoint>(22)
    private val fpDeque    = ArrayDeque<FootprintCandle>(22)
    private val cvdDeque   = ArrayDeque<Float>(22)

    private val nm = app.getSystemService(NotificationManager::class.java).also {
        it.createNotificationChannel(
            NotificationChannel("signals", "Trade Signals", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    init {
        applySettingsToEngines()
        _state.update { it.copy(
            appSettings  = currentSettingsState(),
            paperAccount = paperEngine.state,
        ) }
        connectMetaApi()
        viewModelScope.launch(Dispatchers.IO) {
            initEngine()
            loadHistory()
            collectLiveKlines()
            collectClosedKlines()
            collectAggTrades()
        }
        startMtPoller()
        startTvPoller()
    }

    // ── MetaApi connection ────────────────────────────────────────────────────

    private fun connectMetaApi() {
        val token = settings.metaApiToken
        val id    = settings.metaApiAccountId
        if (token.isBlank() || id.isBlank()) {
            mtClient   = null
            liveEngine = null
            _state.update { it.copy(mtConnected = false) }
            return
        }
        val client = MetaApiClient(token, id, settings.metaApiRegion)
        mtClient   = client
        liveEngine = LiveTradeEngine(client).also { applyLiveEngineSettings(it) }
        _state.update { it.copy(mtConnected = true, mtError = null) }
    }

    private fun startMtPoller() = viewModelScope.launch(Dispatchers.IO) {
        while (true) {
            delay(10_000)
            refreshMtAccount()
        }
    }

    private fun startTvPoller() = viewModelScope.launch(Dispatchers.IO) {
        while (true) {
            delay(5_000)
            val key = settings.tvWebhookKey.trim()
            if (key.isBlank()) continue
            try {
                val sig = repo.fetchTvSignal(key) ?: continue
                if (sig.receivedAt <= lastTvReceivedAt) continue   // already processed
                lastTvReceivedAt = sig.receivedAt
                paperEngine.onTvSignal(
                    action    = sig.action,
                    price     = sig.price,
                    timestamp = sig.receivedAt,
                    strategy  = sig.symbol,
                )
                _state.update { it.copy(paperAccount = paperEngine.state) }
            } catch (_: Exception) {}
        }
    }

    fun resetPaperAccount() { paperEngine.reset(); _state.update { it.copy(paperAccount = paperEngine.state) } }

    private suspend fun refreshMtAccount() {
        val client = mtClient ?: return
        try {
            val acct      = client.getAccountInfo()
            val positions = client.getPositions()
            val deals     = client.getDeals()
            _state.update { it.copy(
                mtAccount   = acct,
                mtPositions = positions,
                mtDeals     = deals,
                mtError     = liveEngine?.lastError,
            ) }
        } catch (e: Exception) {
            _state.update { it.copy(mtError = e.message) }
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    private fun applySettingsToEngines() {
        engine.setThresholds(settings.shortThresh, settings.longThresh)
        liveEngine?.let { applyLiveEngineSettings(it) }
    }

    private fun applyLiveEngineSettings(e: LiveTradeEngine) {
        e.enableLong    = settings.enableLong
        e.riskPct       = settings.riskPct.toDouble()
        e.slAtrMult     = settings.slAtrMult
        e.tradingSymbol = settings.tradingSymbol
    }

    private fun currentSettingsState() = AppSettingsState(
        shortThresh      = settings.shortThresh,
        longThresh       = settings.longThresh,
        riskPct          = settings.riskPct,
        slAtrMult        = settings.slAtrMult,
        enableLong       = settings.enableLong,
        maxDrawdownPct   = settings.maxDrawdownPct,
        metaApiToken     = settings.metaApiToken,
        metaApiAccountId = settings.metaApiAccountId,
        metaApiRegion    = settings.metaApiRegion,
        tradingSymbol    = settings.tradingSymbol,
        tvWebhookKey     = settings.tvWebhookKey,
    )

    fun updateSettings(s: AppSettingsState) {
        settings.shortThresh      = s.shortThresh
        settings.longThresh       = s.longThresh
        settings.riskPct          = s.riskPct
        settings.slAtrMult        = s.slAtrMult
        settings.enableLong       = s.enableLong
        settings.maxDrawdownPct   = s.maxDrawdownPct
        settings.metaApiToken     = s.metaApiToken
        settings.metaApiAccountId = s.metaApiAccountId
        settings.metaApiRegion    = s.metaApiRegion
        settings.tradingSymbol    = s.tradingSymbol
        settings.tvWebhookKey     = s.tvWebhookKey
        connectMetaApi()
        applySettingsToEngines()
        _state.update { it.copy(appSettings = s) }
    }

    // ── Data loading ──────────────────────────────────────────────────────────

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
                    cvdDeque.addLast(cvdDeque.lastOrNull() ?: 0f)
                }
            }
            _state.update { it.copy(klines = window.toList()) }
        } catch (_: Exception) {}
    }

    private suspend fun initEngine() {
        try {
            engine.init()
            _state.update { it.copy(engineReady = true) }
        } catch (e: Exception) { _state.update { it.copy(error = "Engine: ${e.message}") } }
    }

    private fun collectLiveKlines() = viewModelScope.launch(Dispatchers.IO) {
        repo.liveKlines.catch { e -> _state.update { it.copy(error = e.message) } }
            .collect { kline ->
                if (window.isEmpty() || window.last().openTime != kline.openTime) {
                    if (window.size >= 300) window.removeFirst()
                    window.addLast(kline)
                } else { window[window.size - 1] = kline }
                _state.update { it.copy(klines = window.toList()) }
            }
    }

    private fun collectClosedKlines() = viewModelScope.launch(Dispatchers.IO) {
        repo.closedKlines.catch { e -> _state.update { it.copy(error = e.message) } }
            .collect { kline ->
                mu.withLock {
                    val closed = buildFootprint(kline, isClosed = true)
                    fpDeque.addLast(closed)
                    if (fpDeque.size > 21) fpDeque.removeFirst()
                    val runningCvd = (cvdDeque.lastOrNull() ?: 0f) + closed.delta
                    cvdDeque.addLast(runningCvd)
                    if (cvdDeque.size > 21) cvdDeque.removeFirst()
                    liveLevels.clear()
                }
                if (!_state.value.engineReady) return@collect
                val features = FeatureBuilder.build(window.toList()) ?: return@collect
                val out = engine.infer(features)

                val atrPct = features[23]
                liveEngine?.onSignal(out.signal, kline.close, atrPct)
                if (out.signal != Signal.NEUTRAL) notifySignal(out.signal, kline.close)
                try { repo.sendTradingSignal(out.signal.name, kline.close, kline.openTime) } catch (_: Exception) {}

                val adxVal   = features[17] * 100f
                val plusDi   = features[18]
                val minusDi  = features[19]
                mu.withLock {
                    adxDeque.addLast(AdxPoint(adxVal, plusDi, minusDi))
                    if (adxDeque.size > 21) adxDeque.removeFirst()
                }

                val closes   = window.takeLast(20).map { it.close }
                val bbMid    = closes.average()
                val variance = closes.sumOf { (it - bbMid) * (it - bbMid) } / closes.size
                val bbUpper  = bbMid + 2.0 * sqrt(variance)
                val bbLower  = bbMid - 2.0 * sqrt(variance)

                val result = InferenceResult(out.signal, out.ensemble, out.probLor, out.probCat, out.probXgb, out.probRf, kline)
                _state.update { st ->
                    val newLog = if (out.signal != Signal.NEUTRAL)
                        (st.signalLog + result).takeLast(100)
                    else st.signalLog
                    st.copy(
                        lastResult = result,
                        adxHistory = adxDeque.toList(),
                        indicators = IndicatorState(
                            bbUpper, bbLower, bbMid,
                            adxVal, plusDi, minusDi, atrPct,
                            engine.shortThresh, engine.longThresh,
                        ),
                        signalLog  = newLog,
                    )
                }
            }
    }

    private fun collectAggTrades() = viewModelScope.launch(Dispatchers.IO) {
        launch {
            while (true) {
                delay(100)
                mu.withLock {
                    val liveKline = window.lastOrNull() ?: return@withLock
                    val live = buildFootprint(liveKline, false)
                    val prevCvd = cvdDeque.lastOrNull() ?: 0f
                    _state.update { it.copy(
                        footprints = fpDeque.toList() + live,
                        cvd        = cvdDeque.toList() + (prevCvd + live.delta),
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
            Signal.LONG  -> "▲ LONG Signal"  to "ETH/USDT \$%.1f".format(price)
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

    /** Force-refresh MT5 account (Portfolio screen) */
    fun refreshMt() = viewModelScope.launch(Dispatchers.IO) { refreshMtAccount() }

    override fun onCleared() { super.onCleared(); engine.close() }
}
