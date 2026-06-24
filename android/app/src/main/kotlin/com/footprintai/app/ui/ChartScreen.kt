package com.footprintai.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.footprintai.app.model.FootprintCandle
import com.footprintai.app.model.Signal

private val BG       = Color(0xFF0D1117)
private val GREEN    = Color(0xFF00C853)
private val RED      = Color(0xFFD50000)
private val GRAY     = Color(0xFF4A4A4A)
private val AXIS_TXT = Color(0xFF888888)

@Composable
fun ChartScreen(vm: ChartViewModel = viewModel(), modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BG)
    ) {
        // ── 顶部栏 ─────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "ETH/USDT · 5m",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
                val price = state.footprints.lastOrNull()?.close
                if (price != null) {
                    Text(
                        text = "$${"%,.1f".format(price)}",
                        color = if ((state.footprints.lastOrNull()?.let { it.close >= it.open }) == true) GREEN else RED,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            SignalBadge(state.lastResult?.signal, state.lastResult?.prob)
        }

        if (!state.engineReady) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 12.dp),
                color = GREEN,
                trackColor = GRAY,
            )
        }

        state.error?.let {
            Text(it, color = RED, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp))
        }

        // ── Footprint 图表 ──────────────────────────────────────────────────────
        val candles = state.footprints.takeLast(20)
        if (candles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GREEN)
            }
        } else {
            FootprintChart(
                candles  = candles,
                signal   = state.lastResult?.signal,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun FootprintChart(
    candles: List<FootprintCandle>,
    signal: Signal?,
    modifier: Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val tickSize = ChartViewModel.TICK

    Canvas(modifier = modifier) {
        if (candles.isEmpty()) return@Canvas

        val axisWidth = 68f
        val chartW    = size.width - axisWidth
        val chartH    = size.height - 24f

        val colW = chartW / candles.size

        val priceMin  = candles.minOf { it.low  } - tickSize
        val priceMax  = candles.maxOf { it.high } + tickSize
        val priceRange = (priceMax - priceMin).coerceAtLeast(1.0)

        fun py(price: Double): Float = ((priceMax - price) / priceRange * chartH).toFloat()

        // 价格网格线
        for (i in 0..5) {
            val p = priceMin + (priceMax - priceMin) * i / 5
            val y = py(p)
            drawLine(GRAY.copy(alpha = 0.3f), Offset(0f, y), Offset(chartW, y), 0.5f)
            val label = textMeasurer.measure(
                "\$${"%,.0f".format(p)}",
                TextStyle(fontSize = 9.sp, color = AXIS_TXT, fontFamily = FontFamily.Monospace),
            )
            drawText(label, topLeft = Offset(chartW + 2, y - label.size.height / 2f))
        }

        candles.forEachIndexed { i, candle ->
            val x      = i * colW
            val isLive = !candle.isClosed
            val isBull = candle.close >= candle.open
            val bodyClr = if (isBull) GREEN else RED

            val openY  = py(candle.open)
            val closeY = py(candle.close)
            val highY  = py(candle.high)
            val lowY   = py(candle.low)

            // 信号背景高亮（仅实时 candle）
            if (isLive && signal != null) {
                val sigClr = when (signal) {
                    Signal.LONG    -> GREEN.copy(alpha = 0.08f)
                    Signal.SHORT   -> RED.copy(alpha = 0.08f)
                    Signal.NEUTRAL -> Color.White.copy(alpha = 0.04f)
                }
                drawRect(sigClr, Offset(x, 0f), Size(colW, chartH))
            }

            // Wick
            drawLine(bodyClr.copy(alpha = 0.6f), Offset(x + colW / 2, highY), Offset(x + colW / 2, lowY), 1.5f)

            val levelRowH = (tickSize / priceRange * chartH).toFloat().coerceAtLeast(1f)

            if (candle.levels.isNotEmpty()) {
                candle.levels.forEach level@{ level ->
                    if (level.price < candle.low - tickSize || level.price > candle.high + tickSize) return@level

                    val rowTop = py(level.price + tickSize)
                    val rowBot = py(level.price)
                    val rH = (rowBot - rowTop).coerceAtLeast(1f)

                    val bgClr = if (level.isBuyDominant) GREEN.copy(alpha = 0.75f) else RED.copy(alpha = 0.75f)
                    drawRect(bgClr, Offset(x + 1f, rowTop), Size(colW - 2f, rH))

                    if (rH >= 8f) {
                        val vol = if (level.isBuyDominant) level.buyVol else level.sellVol
                        val measured = textMeasurer.measure(
                            "%.2f".format(vol),
                            TextStyle(fontSize = 7.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                        )
                        if (measured.size.width < colW - 4 && measured.size.height <= rH) {
                            drawText(measured, topLeft = Offset(
                                x + (colW - measured.size.width) / 2,
                                rowTop + (rH - measured.size.height) / 2,
                            ))
                        }
                    }
                }
            } else {
                // 历史 candle 无档位数据 — 画普通实体
                val bodyTop = minOf(openY, closeY)
                val bodyH   = (maxOf(openY, closeY) - bodyTop).coerceAtLeast(2f)
                drawRect(bodyClr.copy(alpha = 0.9f), Offset(x + 2f, bodyTop), Size(colW - 4f, bodyH))
            }

            // Delta 标签
            if (candle.delta != 0f) {
                val dClr = if (candle.delta >= 0) GREEN else RED
                val dm = textMeasurer.measure(
                    "${if (candle.delta >= 0) "+" else ""}${"%.2f".format(candle.delta)}",
                    TextStyle(fontSize = 8.sp, color = dClr, fontWeight = FontWeight.Bold)
                )
                drawText(dm, topLeft = Offset(x + (colW - dm.size.width) / 2, highY - dm.size.height - 2))
            }

            // 实时 candle 信号标签
            if (isLive && signal != null && signal != Signal.NEUTRAL) {
                val sigClr  = if (signal == Signal.LONG) GREEN else RED
                val sigText = if (signal == Signal.LONG) "▲ LONG" else "▼ SHORT"
                val sm = textMeasurer.measure(sigText, TextStyle(
                    fontSize = 11.sp, color = sigClr, fontWeight = FontWeight.Bold))
                drawText(sm, topLeft = Offset(
                    x + (colW - sm.size.width) / 2,
                    chartH / 2 - sm.size.height / 2,
                ))
            }

            // 时间标签（最后 candle）
            if (i == candles.lastIndex) {
                val ts  = candle.openTime
                val hh  = (ts / 3_600_000 % 24).toString().padStart(2, '0')
                val mm  = (ts / 60_000 % 60).toString().padStart(2, '0')
                val tm  = textMeasurer.measure("$hh:$mm", TextStyle(fontSize = 9.sp, color = AXIS_TXT))
                drawText(tm, topLeft = Offset(x + (colW - tm.size.width) / 2, chartH + 4))
            }
        }
    }
}

@Composable
private fun SignalBadge(signal: Signal?, prob: Float?) {
    val (label, color) = when (signal) {
        Signal.LONG    -> "▲ LONG"  to GREEN
        Signal.SHORT   -> "▼ SHORT" to RED
        Signal.NEUTRAL -> "─ WAIT"  to Color(0xFF9E9E9E)
        null           -> "…"       to Color(0xFF9E9E9E)
    }
    val animColor by animateColorAsState(color, tween(400), label = "sig")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .background(animColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = if (prob != null) "$label  ${(prob * 100).toInt()}%" else label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}
