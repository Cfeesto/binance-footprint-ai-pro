package com.footprintai.app.ui

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.footprintai.app.data.AppSettings
import com.footprintai.app.data.BinanceRepository
import com.footprintai.app.data.PaperTradeStore
import com.footprintai.app.data.Ticker24h
import org.json.JSONArray
import com.footprintai.app.inference.FeatureBuilder
import com.footprintai.app.inference.OnnxInferenceEngine
import com.footprintai.app.inference.PaperTradeEngine
import com.footprintai.app.model.*
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

data class ScannerRow(
    val symbol:        String = "",
    val price:         Double = 0.0,
    val confluencePct: Int    = 0,
    val signal:        String = "NONE",  // LONG | SHORT | NONE
)

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
    val shortThresh:      Float   = 0.25f,
    val longThresh:       Float   = 0.64f,
    val riskPct:          Float   = 0.02f,
    val slAtrMult:        Float   = 1.5f,
    val enableLong:       Boolean = true,
    val maxDrawdownPct:   Float   = 0.30f,
    val tvWebhookKey:     String  = "",
    // ── VPS + Exchange ────────────────────────────────────────────────────────
    val vpsWsUrl:         String  = "",
    val vpsApiUrl:        String  = "",
    val liveExchange:     String  = "binance",
    val binanceApiKey:    String  = "",
    val binanceApiSecret: String  = "",
    val hlPrivateKey:     String  = "",
    val bybitApiKey:      String  = "",
    val bybitApiSecret:   String  = "",
    val okxApiKey:        String  = "",
    val okxApiSecret:     String  = "",
    val okxPassphrase:    String  = "",
)

data class ChartState(
    val klines:          List<Kline>           = emptyList(),
    val footprints:      List<FootprintCandle> = emptyList(),
    val lastResult:      InferenceResult?      = null,
    val error:           String?               = null,
    val engineReady:     Boolean               = false,
    // ── Paper trading (TradingView webhook) ──────────────────────────────────
    val paperAccount:    PaperAccount          = PaperAccount(),
    // ── VPS signal ────────────────────────────────────────────────────────────
    val vpsSignal:       com.footprintai.app.data.VpsSignal? = null,
    val vpsConnected:    Boolean               = false,
    // ── Chart indicators ─────────────────────────────────────────────────────
    val cvd:             List<Float>           = emptyList(),
    val adxHistory:      List<AdxPoint>        = emptyList(),
    val indicators:      IndicatorState?       = null,
    val signalLog:       List<InferenceResult> = emptyList(),
    val appSettings:     AppSettingsState      = AppSettingsState(),
    // ── Symbol / TF selection ─────────────────────────────────────────────────
    val selectedSymbol:  String                = "ETHUSDT",
    val selectedTimeframe: String              = "5m",
    // ── MTF confluence ────────────────────────────────────────────────────────
    val confluenceScore: Int                   = 0,
    val confluenceMax:   Int                   = 5,
    val tfSignals:       Map<String, String>   = emptyMap(),
    // ── Market scanner + 24h stats ────────────────────────────────────────────
    val scannerRows:     List<ScannerRow>      = emptyList(),
    val ticker24h:       Ticker24h?            = null,
)

class ChartViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ChartState())
    val state: StateFlow<ChartState> = _state.asStateFlow()

    private val settings    = AppSettings(app)
    private val engine      = OnnxInferenceEngine(app)
    private val paperEngine = PaperTradeEngine(PaperTradeStore(app))
    private val vpsClient   = com.footprintai.app.data.VpsSignalClient()

    // ponytail: receivedAt dedup — ignore signal we've already processed
    private var lastTvReceivedAt: Long = 0L

    private val mu         = Mutex()
    private val adxDeque   = ArrayDeque<AdxPoint>(22)
    private val fpDeque    = ArrayDeque<FootprintCandle>(22)
    private val cvdDeque   = ArrayDeque<Float>(22)

    // ── Dynamic repo + per-stream Jobs ────────────────────────────────────────
    private val window     = ArrayDeque<Kline>(300)
    private val liveLevels = HashMap<Double, Pair<Float, Float>>(512)
    companion object { const val TICK = 1.0 }

    private var repo: BinanceRepository = BinanceRepository(
        _state.value.selectedSymbol.lowercase(),
        _state.value.selectedTimeframe,
    )
    private var liveKlinesJob:  Job? = null
    private var closedKlinesJob: Job? = null
    private var aggTradesJob:   Job? = null

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
        viewModelScope.launch(Dispatchers.IO) { initEngine() }
        startDataStreams(_state.value.selectedSymbol, _state.value.selectedTimeframe)
        startTvPoller()
        startVpsSignalCollector()
        startScannerPoller()
        if (settings.vpsWsUrl.isNotBlank()) vpsClient.connect(settings.vpsWsUrl)
    }

    // ── Stream restart ────────────────────────────────────────────────────────

    fun selectSymbol(symbol: String) {
        _state.update { it.copy(selectedSymbol = symbol, klines = emptyList(), footprints = emptyList()) }
        startDataStreams(symbol, _state.value.selectedTimeframe)
    }

    fun selectTimeframe(tf: String) {
        _state.update { it.copy(selectedTimeframe = tf, klines = emptyList(), footprints = emptyList()) }
        startDataStreams(_state.value.selectedSymbol, tf)
    }

    private fun startDataStreams(symbol: String, tf: String) {
        liveKlinesJob?.cancel()
        closedKlinesJob?.cancel()
        aggTradesJob?.cancel()
        repo = BinanceRepository(symbol.lowercase(), tf)
        window.clear(); liveLevels.clear()
        fpDeque.clear(); cvdDeque.clear(); adxDeque.clear()
        viewModelScope.launch(Dispatchers.IO) { loadHistory() }
        liveKlinesJob   = collectLiveKlines()
        closedKlinesJob = collectClosedKlines()
        aggTradesJob    = collectAggTrades()
    }

    // ── TV webhook poller ─────────────────────────────────────────────────────

    private fun startTvPoller() = viewModelScope.launch(Dispatchers.IO) {
        while (true) {
            delay(5_000)
            val key = settings.tvWebhookKey.trim()
            if (key.isBlank()) continue
            try {
                val sig = repo.fetchTvSignal(key) ?: continue
                if (sig.receivedAt <= lastTvReceivedAt) continue
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

    private fun startVpsSignalCollector() = viewModelScope.launch(Dispatchers.IO) {
        vpsClient.signals.collect { sig ->
            _state.update { it.copy(
                vpsSignal       = sig,
                vpsConnected    = true,
                confluenceScore = sig.confluenceScore,
                confluenceMax   = sig.confluenceMax,
                tfSignals       = sig.tfSignals,
            ) }
        }
    }

    // ── Scanner / 24h ticker poller ───────────────────────────────────────────

    private val scannerSymbols = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT")

    private fun startScannerPoller() = viewModelScope.launch(Dispatchers.IO) {
        while (true) {
            // 24h ticker for the currently selected symbol
            try {
                val t = repo.fetchTicker24h(_state.value.selectedSymbol)
                _state.update { it.copy(ticker24h = t) }
            } catch (_: Exception) {}

            // Market scanner — prefer VPS /scanner endpoint, fall back to Binance prices
            val apiUrl = settings.vpsApiUrl.trimEnd('/')
            if (apiUrl.isNotBlank()) {
                try {
                    val raw  = repo.vpsGet("$apiUrl/scanner")
                    val arr  = JSONArray(raw)
                    val rows = (0 until arr.length()).map { i ->
                        val j = arr.getJSONObject(i)
                        ScannerRow(
                            symbol        = j.optString("symbol", ""),
                            price         = j.optDouble("price", 0.0),
                            confluencePct = j.optInt("confluence_pct", 0),
                            signal        = j.optString("signal", "NONE"),
                        )
                    }
                    if (rows.isNotEmpty()) _state.update { it.copy(scannerRows = rows) }
                } catch (_: Exception) { refreshScannerFromBinance() }
            } else {
                refreshScannerFromBinance()
            }
            delay(30_000)
        }
    }

    private suspend fun refreshScannerFromBinance() {
        val rows = scannerSymbols.mapNotNull { sym ->
            try {
                val t = repo.fetchTicker24h(sym)
                val sig = when {
                    t.priceChangePercent > 1.5  -> "LONG"
                    t.priceChangePercent < -1.5 -> "SHORT"
                    else                        -> "NONE"
                }
                // ponytail: confluence derived from price-change magnitude; replace with ML score when VPS available
                val conf = minOf(100, (kotlin.math.abs(t.priceChangePercent) * 20).toInt())
                ScannerRow(sym, t.highPrice, conf, sig)
            } catch (_: Exception) { null }
        }
        if (rows.isNotEmpty()) _state.update { it.copy(scannerRows = rows) }
    }

    fun resetPaperAccount() { paperEngine.reset(); _state.update { it.copy(paperAccount = paperEngine.state) } }

    // ── Settings ──────────────────────────────────────────────────────────────

    private fun applySettingsToEngines() {
        engine.setThresholds(settings.shortThresh, settings.longThresh)
    }

    private fun currentSettingsState() = AppSettingsState(
        shortThresh      = settings.shortThresh,
        longThresh       = settings.longThresh,
        riskPct          = settings.riskPct,
        slAtrMult        = settings.slAtrMult,
        enableLong       = settings.enableLong,
        maxDrawdownPct   = settings.maxDrawdownPct,
        tvWebhookKey     = settings.tvWebhookKey,
        vpsWsUrl         = settings.vpsWsUrl,
        vpsApiUrl        = settings.vpsApiUrl,
        liveExchange     = settings.liveExchange,
        binanceApiKey    = settings.binanceApiKey,
        binanceApiSecret = settings.binanceApiSecret,
        hlPrivateKey     = settings.hlPrivateKey,
        bybitApiKey      = settings.bybitApiKey,
        bybitApiSecret   = settings.bybitApiSecret,
        okxApiKey        = settings.okxApiKey,
        okxApiSecret     = settings.okxApiSecret,
        okxPassphrase    = settings.okxPassphrase,
    )

    fun updateSettings(s: AppSettingsState) {
        settings.shortThresh      = s.shortThresh
        settings.longThresh       = s.longThresh
        settings.riskPct          = s.riskPct
        settings.slAtrMult        = s.slAtrMult
        settings.enableLong       = s.enableLong
        settings.maxDrawdownPct   = s.maxDrawdownPct
        settings.tvWebhookKey     = s.tvWebhookKey
        settings.vpsWsUrl         = s.vpsWsUrl
        settings.vpsApiUrl        = s.vpsApiUrl
        settings.liveExchange     = s.liveExchange
        settings.binanceApiKey    = s.binanceApiKey
        settings.binanceApiSecret = s.binanceApiSecret
        settings.hlPrivateKey     = s.hlPrivateKey
        settings.bybitApiKey      = s.bybitApiKey
        settings.bybitApiSecret   = s.bybitApiSecret
        settings.okxApiKey        = s.okxApiKey
        settings.okxApiSecret     = s.okxApiSecret
        settings.okxPassphrase    = s.okxPassphrase
        applySettingsToEngines()
        if (s.vpsWsUrl.isNotBlank()) vpsClient.connect(s.vpsWsUrl)
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
                if (out.signal != Signal.NEUTRAL) notifySignal(out.signal, kline.close)
                try { repo.sendTradingSignal(out.signal.name, kline.close, kline.openTime) } catch (_: Exception) {}

                val adxVal  = features[17] * 100f
                val plusDi  = features[18]
                val minusDi = features[19]
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
        val sym = _state.value.selectedSymbol
        val (title, text) = when (signal) {
            Signal.LONG  -> "▲ LONG Signal"  to "$sym \$%.1f".format(price)
            Signal.SHORT -> "▼ SHORT Signal" to "$sym \$%.1f".format(price)
            else -> return
        }
        nm.notify(signal.ordinal, Notification.Builder(ctx, "signals")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build())
    }

    /** POST /promote?exchange=X to VPS — only succeeds when canPromote=true */
    fun promoteLive() = viewModelScope.launch(Dispatchers.IO) {
        val base = settings.vpsApiUrl.trimEnd('/')
        val exch = settings.liveExchange
        if (base.isBlank()) return@launch
        try {
            val creds = buildString {
                when (exch) {
                    "binance"     -> append("binance_api_key=${settings.binanceApiKey}&binance_api_secret=${settings.binanceApiSecret}")
                    "hyperliquid" -> append("hl_private_key=${settings.hlPrivateKey}")
                    "bybit"       -> append("bybit_api_key=${settings.bybitApiKey}&bybit_api_secret=${settings.bybitApiSecret}")
                    "okx"         -> append("okx_api_key=${settings.okxApiKey}&okx_api_secret=${settings.okxApiSecret}&okx_passphrase=${settings.okxPassphrase}")
                }
            }
            repo.vpsPost("$base/promote?exchange=$exch&$creds")
        } catch (_: Exception) {}
    }

    fun demoteLive() = viewModelScope.launch(Dispatchers.IO) {
        val base = settings.vpsApiUrl.trimEnd('/')
        if (base.isBlank()) return@launch
        try { repo.vpsPost("$base/demote") } catch (_: Exception) {}
    }

    override fun onCleared() { super.onCleared(); engine.close() }
}
