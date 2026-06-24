package com.footprintai.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
            var scale  by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            val txState = rememberTransformableState { zoom, pan, _ ->
                scale  = (scale * zoom).coerceIn(0.3f, 10f)
                offset = Offset(offset.x + pan.x, offset.y + pan.y)
            }
            FootprintChart(
                candles  = candles,
                signal   = state.lastResult?.signal,
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(txState)
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    },
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
            val x       = i * colW
            val isLive  = !candle.isClosed
            val isBull  = candle.close >= candle.open
            val bodyClr = if (isBull) GREEN else RED

            val highY = py(candle.high)
            val lowY  = py(candle.low)

            // SHORT-only: only highlight live candle on SHORT signal
            if (isLive && signal == Signal.SHORT) {
                drawRect(RED.copy(alpha = 0.08f), Offset(x, 0f), Size(colW, chartH))
            }

            // Wick
            drawLine(bodyClr.copy(alpha = 0.6f), Offset(x + colW / 2, highY), Offset(x + colW / 2, lowY), 1.5f)

            if (candle.levels.isNotEmpty()) {
                // POC = level with highest total volume
                val pocPrice  = candle.levels.maxByOrNull { it.buyVol + it.sellVol }?.price
                val maxLvlVol = candle.levels.maxOf { it.buyVol + it.sellVol }.coerceAtLeast(0.0001f)

                candle.levels.forEach level@{ level ->
                    if (level.price < candle.low - tickSize || level.price > candle.high + tickSize) return@level

                    val rowTop = py(level.price + tickSize)
                    val rowBot = py(level.price)
                    val rH = (rowBot - rowTop).coerceAtLeast(1f)

                    // 成交量强度决定透明度（低量=淡，高量=实）
                    val intensity = ((level.buyVol + level.sellVol) / maxLvlVol).coerceIn(0.15f, 1f)
                    val bgAlpha   = 0.25f + 0.6f * intensity
                    val bgClr     = if (level.isBuyDominant) GREEN.copy(alpha = bgAlpha) else RED.copy(alpha = bgAlpha)
                    drawRect(bgClr, Offset(x + 1f, rowTop), Size(colW - 2f, rH))

                    // 失衡高亮：一侧 ≥3× 另一侧 → 金色边框
                    val s = level.sellVol; val b = level.buyVol
                    val isImbalance = (s > 0 && b > 0) && (b / s >= 3f || s / b >= 3f)
                    if (isImbalance) {
                        drawRect(Color(0xFFFFD700).copy(alpha = 0.55f), Offset(x + 1f, rowTop), Size(colW - 2f, rH))
                    }

                    // POC 黄线
                    if (level.price == pocPrice) {
                        drawLine(Color(0xFFFFD700), Offset(x, rowTop), Offset(x + colW, rowTop), 1.5f)
                    }

                    // Bid×Ask 文字："sell × buy"
                    if (rH >= 9f) {
                        val txt = "%.1f×%.1f".format(s, b)
                        val tm  = textMeasurer.measure(
                            txt, TextStyle(fontSize = 7.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                        )
                        if (tm.size.width < colW - 4 && tm.size.height <= rH) {
                            drawText(tm, topLeft = Offset(
                                x + (colW - tm.size.width) / 2,
                                rowTop + (rH - tm.size.height) / 2,
                            ))
                        }
                    }
                }
            } else {
                // 历史 candle 无档位 — 普通实体
                val openY   = py(candle.open)
                val closeY  = py(candle.close)
                val bodyTop = minOf(openY, closeY)
                val bodyH   = (maxOf(openY, closeY) - bodyTop).coerceAtLeast(2f)
                drawRect(bodyClr.copy(alpha = 0.85f), Offset(x + 2f, bodyTop), Size(colW - 4f, bodyH))
            }

            // Delta 标签（candle 顶部）
            if (candle.delta != 0f) {
                val dClr = if (candle.delta >= 0) GREEN else RED
                val dm = textMeasurer.measure(
                    "${if (candle.delta >= 0) "+" else ""}${"%.1f".format(candle.delta)}",
                    TextStyle(fontSize = 7.sp, color = dClr, fontWeight = FontWeight.Bold)
                )
                drawText(dm, topLeft = Offset(x + (colW - dm.size.width) / 2, highY - dm.size.height - 2))
            }

            // SHORT 信号标签（仅实时 candle）
            if (isLive && signal == Signal.SHORT) {
                val sm = textMeasurer.measure("▼ SHORT", TextStyle(
                    fontSize = 11.sp, color = RED, fontWeight = FontWeight.Bold))
                drawText(sm, topLeft = Offset(
                    x + (colW - sm.size.width) / 2, chartH / 2 - sm.size.height / 2))
            }

            // 时间标签（最后 candle）
            if (i == candles.lastIndex) {
                val ts = candle.openTime
                val hh = (ts / 3_600_000 % 24).toString().padStart(2, '0')
                val mm = (ts / 60_000    % 60).toString().padStart(2, '0')
                val tm = textMeasurer.measure("$hh:$mm", TextStyle(fontSize = 9.sp, color = AXIS_TXT))
                drawText(tm, topLeft = Offset(x + (colW - tm.size.width) / 2, chartH + 4))
            }
        }
    }
}

@Composable
private fun SignalBadge(signal: Signal?, prob: Float?) {
    // ponytail: LONG filtered out — strategy is SHORT-only
    val (label, color) = when (signal) {
        Signal.SHORT   -> "▼ SHORT" to RED
        else           -> "─ WAIT"  to Color(0xFF9E9E9E)
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
