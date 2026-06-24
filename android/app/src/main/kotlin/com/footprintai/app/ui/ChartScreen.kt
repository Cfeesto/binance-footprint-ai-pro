package com.footprintai.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.footprintai.app.model.Signal
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottomAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStartAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberCandlestickCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.candlestickSeries
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries

@Composable
fun ChartScreen(vm: ChartViewModel = viewModel(), modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()

    // Vico model producer — 更新时重新 build
    val producer = remember { CartesianChartModelProducer() }

    LaunchedEffect(state.klines) {
        val klines = state.klines
        if (klines.size < 2) return@LaunchedEffect
        producer.runTransaction {
            candlestickSeries(
                opening = klines.map { it.open.toFloat() },
                closing = klines.map { it.close.toFloat() },
                low     = klines.map { it.low.toFloat() },
                high    = klines.map { it.high.toFloat() },
            )
            columnSeries {
                series(klines.map { it.volume.toFloat() })
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 8.dp, vertical = 16.dp)
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Text(
                text  = "ETH/USDT · 5m",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            SignalBadge(state.lastResult?.signal, state.lastResult?.prob)
        }

        // ── Candlestick + Volume Chart ───────────────────────────────────────
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberCandlestickCartesianLayer(),
                rememberColumnCartesianLayer(),
                startAxis = rememberStartAxis(),
                bottomAxis = rememberBottomAxis(),
            ),
            modelProducer = producer,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        // ── Status / error ───────────────────────────────────────────────────
        state.error?.let { err ->
            Text(
                text  = err,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (!state.engineReady) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@Composable
private fun SignalBadge(signal: Signal?, prob: Float?) {
    val (label, color) = when (signal) {
        Signal.LONG    -> "▲ LONG"  to Color(0xFF00C853)
        Signal.SHORT   -> "▼ SHORT" to Color(0xFFD50000)
        Signal.NEUTRAL -> "─ WAIT"  to Color(0xFF9E9E9E)
        null           -> "…"       to Color(0xFF9E9E9E)
    }
    val animColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(400),
        label = "signal-color",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .background(animColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        val text = if (prob != null) "$label  ${(prob * 100).toInt()}%" else label
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
