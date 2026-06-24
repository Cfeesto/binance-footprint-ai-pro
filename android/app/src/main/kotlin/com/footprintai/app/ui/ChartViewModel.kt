package com.footprintai.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.footprintai.app.data.BinanceRepository
import com.footprintai.app.data.PaperTradeStore
import com.footprintai.app.inference.FeatureBuilder
import com.footprintai.app.inference.OnnxInferenceEngine
import com.footprintai.app.inference.PaperTradeEngine
import com.footprintai.app.model.InferenceResult
import com.footprintai.app.model.Kline
import com.footprintai.app.model.PaperAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ChartState(
    val klines:       List<Kline>        = emptyList(),
    val lastResult:   InferenceResult?   = null,
    val error:        String?            = null,
    val engineReady:  Boolean            = false,
    val paperAccount: PaperAccount       = PaperAccount(),
)

class ChartViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ChartState())
    val state: StateFlow<ChartState> = _state.asStateFlow()

    val accountBalance = MutableStateFlow("-")
    val openPosition = MutableStateFlow("-")
    val dailyWinLoss = MutableStateFlow("-")

    private val repo        = BinanceRepository(symbol = "ethusdt", interval = "5m")

    private val engine      = OnnxInferenceEngine(app)
    private val paperEngine = PaperTradeEngine(PaperTradeStore(app))

    private val window = ArrayDeque<Kline>(200)

    private suspend fun updateAccountStatus() = withContext(Dispatchers.IO) {
        try {
            val statusStr = repo.getAccountStatus()
            val statusJson = JSONObject(statusStr)
        accountBalance.value = String.format("$%.2f", statusJson.getDouble("balance"))

        val openPositionsJson = statusJson.getJSONObject("open_positions")
        if (openPositionsJson.length() > 0) {
            val symbol = openPositionsJson.keys().next()
            val position = openPositionsJson.getJSONObject(symbol)
            openPosition.value = "${position.getString("side")} ${symbol.replace("USDT", "/USDT")}"
        } else {
            openPosition.value = "No Open Position"
        }

        val tradeHistory = statusJson.getJSONArray("trade_history")
        var wins = 0
        var losses = 0
        for (i in 0 until tradeHistory.length()) {
            val trade = tradeHistory.getJSONObject(i)
            if (trade.getString("type") == "EXIT") {
                if (trade.getDouble("pnl") > 0) wins++ else losses++
            }
        }
        dailyWinLoss.value = "Wins: $wins, Losses: $losses"
        } catch (e: Exception) {
            dailyWinLoss.value = "Status Unavailable"
        }
    }

    init {
        initEngine()
        loadHistory()
        collectLiveKlines()
        collectClosedKlines()
        viewModelScope.launch { updateAccountStatus() }
    }

    private fun loadHistory() = viewModelScope.launch(Dispatchers.IO) {
        try {
            repo.fetchHistory().forEach { window.addLast(it) }
            _state.value = _state.value.copy(klines = window.toList())
        } catch (_: Exception) { /* WS will fill in */ }
    }

    private fun initEngine() = viewModelScope.launch(Dispatchers.IO) {
        try {
            engine.init()
            _state.value = _state.value.copy(
                engineReady  = true,
                paperAccount = paperEngine.state,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "Engine init failed: ${e.message}")
        }
    }

    private fun collectLiveKlines() = viewModelScope.launch(Dispatchers.IO) {
        repo.liveKlines
            .catch { e -> _state.value = _state.value.copy(error = e.message) }
            .collect { kline ->
                if (window.isEmpty() || window.last().openTime != kline.openTime) {
                    if (window.size >= 200) window.removeFirst()
                    window.addLast(kline)
                } else {
                    window[window.size - 1] = kline
                }

                // 止盈止损检查（每 tick）
                paperEngine.onLivePrice(kline.close, kline.openTime)

                _state.value = _state.value.copy(
                    klines       = window.toList(),
                    paperAccount = paperEngine.state,
                )
            }
    }

    private fun collectClosedKlines() = viewModelScope.launch(Dispatchers.IO) {
        repo.closedKlines
            .catch { e -> _state.value = _state.value.copy(error = e.message) }
            .collect { kline ->
                if (!_state.value.engineReady) return@collect
                val features = FeatureBuilder.build(window.toList()) ?: return@collect
                val (signal, prob) = engine.infer(features)

                // Send signal to backend for live trading
                repo.sendTradingSignal(signal.name, kline.close, kline.openTime)
                updateAccountStatus()

                _state.value = _state.value.copy(
                    lastResult   = InferenceResult(signal, prob, kline),
                )
            }
    }

    fun resetPaperAccount() {
        paperEngine.reset()
        _state.value = _state.value.copy(paperAccount = paperEngine.state)
    }

    override fun onCleared() {
        super.onCleared()
        engine.close()
    }
}
