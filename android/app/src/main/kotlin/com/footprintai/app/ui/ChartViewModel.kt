package com.footprintai.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.footprintai.app.data.BinanceRepository
import com.footprintai.app.inference.FeatureBuilder
import com.footprintai.app.inference.OnnxInferenceEngine
import com.footprintai.app.model.InferenceResult
import com.footprintai.app.model.Kline
import com.footprintai.app.model.Signal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class ChartState(
    val klines:      List<Kline>         = emptyList(),
    val lastResult:  InferenceResult?    = null,
    val error:       String?             = null,
    val engineReady: Boolean             = false,
)

class ChartViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ChartState())
    val state: StateFlow<ChartState> = _state.asStateFlow()

    private val repo   = BinanceRepository(symbol = "ethusdt", interval = "5m")
    private val engine = OnnxInferenceEngine(app)

    // 滑动窗口，保留最近 200 根 K 线供图表显示 + 指标计算
    private val window = ArrayDeque<Kline>(200)

    init {
        initEngine()
        collectLiveKlines()
        collectClosedKlines()
    }

    private fun initEngine() = viewModelScope.launch(Dispatchers.IO) {
        try {
            engine.init()
            _state.value = _state.value.copy(engineReady = true)
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "Engine init failed: ${e.message}")
        }
    }

    /** 实时 tick — 更新图表最后一根 bar */
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
                _state.value = _state.value.copy(klines = window.toList())
            }
    }

    /** 已关闭 K 线 — 触发推断 */
    private fun collectClosedKlines() = viewModelScope.launch(Dispatchers.IO) {
        repo.closedKlines
            .catch { e -> _state.value = _state.value.copy(error = e.message) }
            .collect { kline ->
                if (!_state.value.engineReady) return@collect
                val features = FeatureBuilder.build(window.toList()) ?: return@collect
                val (signal, prob) = engine.infer(features)
                _state.value = _state.value.copy(
                    lastResult = InferenceResult(signal, prob, kline)
                )
            }
    }

    override fun onCleared() {
        super.onCleared()
        engine.close()
    }
}
