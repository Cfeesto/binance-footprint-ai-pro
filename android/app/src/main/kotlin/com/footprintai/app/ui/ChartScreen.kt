package com.footprintai.app.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.footprintai.app.model.FootprintCandle
import com.footprintai.app.model.FootprintLevel
import com.footprintai.app.model.InferenceResult
import com.footprintai.app.model.OpenPosition
import com.footprintai.app.model.Signal
import kotlin.math.floor
import kotlin.math.roundToInt

private val BG         = Color(0xFF131722)   // TradingView dark bg
private val GREEN      = Color(0xFF26A69A)   // TradingView teal
private val RED        = Color(0xFFEF5350)   // TradingView salmon
private val GRAY       = Color(0xFF363A45)
private val AXIS_TXT   = Color(0xFFB2B5BE)   // TradingView axis text
private val GOLD       = Color(0xFFFFD700)
private val SUBPLOT_BG = Color(0xFF0E1117)   // 子图背景（比主图略暗）

/** K-abbreviation: -2127.6 → -2.1K */
private fun formatDelta(v: Float): String {
    val abs = kotlin.math.abs(v)
    val sign = if (v >= 0) "+" else "-"
    return if (abs >= 1000f) "$sign${"%.1f".format(abs / 1000f)}K"
    else "$sign${"%.1f".format(abs)}"
}

private data class InspectedLevel(
    val priceFrom:    Double,
    val priceTo:      Double,
    val buyVol:       Float,
    val sellVol:      Float,
    val pctOfCandle:  Float,
    val tapX:         Float,
    val tapY:         Float,
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChartScreen(vm: ChartViewModel = viewModel(), modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showTv by remember { mutableStateOf(false) }  // false = Footprint, true = TradingView

    Column(modifier = modifier.fillMaxSize().background(BG)) {
        // ── 顶部栏 ─────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                // Tab toggle: FP ↔ TV
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ETH/USDT · 5m", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    // ponytail: plain TextButton pair, no TabRow overhead
                    val fpColor = if (!showTv) GREEN else AXIS_TXT
                    val tvColor = if (showTv)  GREEN else AXIS_TXT
                    Text("FP", color = fpColor, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = androidx.compose.ui.Modifier
                            .background(fpColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .pointerInput(Unit) { detectTapGestures { showTv = false } })
                    Spacer(Modifier.width(4.dp))
                    Text("TV", color = tvColor, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = androidx.compose.ui.Modifier
                            .background(tvColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .pointerInput(Unit) { detectTapGestures { showTv = true } })
                }
                val price = state.footprints.lastOrNull()?.close
                if (price != null) {
                    val bull = state.footprints.lastOrNull()?.let { it.close >= it.open } == true
                    Text(
                        text = "\$${"%,.1f".format(price)}",
                        color = if (bull) GREEN else RED,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            SignalGauge(state.lastResult, state.indicators)
        }

        if (!state.engineReady) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 12.dp),
                color = GREEN, trackColor = GRAY,
            )
        }
        state.error?.let {
            Text(it, color = RED, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp))
        }
        state.mtError?.let {
            Text("MT5: $it", color = Color(0xFFFF9800), fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 12.dp))
        }

        if (showTv) {
            // ── TradingView Lightweight Charts WebView ──────────────────────
            TvChartWebView(
                klines      = state.klines,
                position    = state.chartPosition(),
                symbol      = state.appSettings.tradingSymbol,
                modifier    = Modifier.fillMaxSize(),
            )
        } else {
            // ── 足迹图（原有 Canvas 图表）───────────────────────────────────
            val candles = state.footprints.takeLast(13)
            if (candles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = GREEN)
                }
            } else {
                var scale      by remember { mutableFloatStateOf(1f) }
                var offset     by remember { mutableStateOf(Offset.Zero) }
                var autoFollow by remember { mutableStateOf(true) }
                var inspected  by remember { mutableStateOf<InspectedLevel?>(null) }

                LaunchedEffect(autoFollow) {
                    if (autoFollow) { scale = 1f; offset = Offset.Zero }
                }

                val txState = rememberTransformableState { zoom, pan, _ ->
                    autoFollow = false
                    scale  = (scale * zoom).coerceIn(0.3f, 10f)
                    offset = Offset(offset.x + pan.x, offset.y + pan.y)
                }

                Column(Modifier.weight(1f).fillMaxWidth()) {
                    Row(Modifier.weight(3f).fillMaxWidth()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            FootprintChart(
                                candles      = candles,
                                signal       = state.lastResult?.signal,
                                indicators   = state.indicators,
                                openPosition = state.chartPosition(),
                                modifier     = Modifier
                                    .fillMaxSize()
                                    .pointerInput(candles, scale, offset) {
                                        detectTapGestures { tap ->
                                            val pivX = size.width  / 2f
                                            val pivY = size.height / 2f
                                            val cx = (tap.x - pivX - offset.x) / scale + pivX
                                            val cy = (tap.y - pivY - offset.y) / scale + pivY

                                            val axisW      = 68f
                                            val chartW     = size.width  - axisW
                                            val chartH     = size.height - 24f
                                            val tick       = ChartViewModel.TICK
                                            val priceMin   = candles.minOf { it.low  } - tick
                                            val priceMax   = candles.maxOf { it.high } + tick
                                            val priceRange = (priceMax - priceMin).coerceAtLeast(1.0)
                                            val colW       = chartW / candles.size

                                            val idx    = (cx / colW).toInt().coerceIn(0, candles.lastIndex)
                                            val candle = candles[idx]
                                            val tPrice = priceMax - cy / chartH * priceRange
                                            val bucket = floor(tPrice / tick) * tick
                                            val level  = candle.levels.minByOrNull { kotlin.math.abs(it.price - bucket) }

                                            if (level != null && candle.levels.isNotEmpty()) {
                                                val totalVol = candle.levels.sumOf { (it.buyVol + it.sellVol).toDouble() }.toFloat()
                                                val pct = if (totalVol > 0) (level.buyVol + level.sellVol) / totalVol else 0f
                                                inspected = InspectedLevel(
                                                    priceFrom = level.price, priceTo = level.price + tick,
                                                    buyVol = level.buyVol, sellVol = level.sellVol,
                                                    pctOfCandle = pct, tapX = tap.x, tapY = tap.y,
                                                )
                                            } else { inspected = null }
                                        }
                                    }
                                    .transformable(txState)
                                    .graphicsLayer {
                                        scaleX = scale; scaleY = scale
                                        translationX = offset.x; translationY = offset.y
                                    },
                            )

                            if (!autoFollow) {
                                Button(
                                    onClick = { autoFollow = true; inspected = null },
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = RED.copy(alpha = 0.85f)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text("● LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            inspected?.let { InspectOverlay(it, onDismiss = { inspected = null }) }
                        }
                        VolumeProfileBar(candles = candles, modifier = Modifier.width(56.dp).fillMaxHeight())
                    }
                    CvdSubplot(cvd = state.cvd, modifier = Modifier.fillMaxWidth().height(72.dp))
                    AdxSubplot(adxHistory = state.adxHistory, modifier = Modifier.fillMaxWidth().height(70.dp))
                }
            }
        }
    }
}

// ── TradingView WebView ──────────────────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TvChartWebView(
    klines:   List<com.footprintai.app.model.Kline>,
    position: OpenPosition?,
    symbol:   String,
    modifier: Modifier,
) {
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    // Push bars to WebView whenever klines update
    LaunchedEffect(klines) {
        val wv = webViewRef.value ?: return@LaunchedEffect
        if (klines.isEmpty()) return@LaunchedEffect
        val bars = klines.joinToString(",") { k ->
            "{\"time\":${k.openTime / 1000},\"open\":${k.open},\"high\":${k.high},\"low\":${k.low},\"close\":${k.close}}"
        }
        // Last bar = live update, rest = bulk set
        if (klines.size > 1) {
            val allButLast = klines.dropLast(1).joinToString(",") { k ->
                "{\"time\":${k.openTime / 1000},\"open\":${k.open},\"high\":${k.high},\"low\":${k.low},\"close\":${k.close}}"
            }
            wv.evaluateJavascript("updateBars('[${allButLast}]')", null)
        }
        val last = klines.last()
        wv.evaluateJavascript(
            "updateLastBar('{\"time\":${last.openTime / 1000},\"open\":${last.open},\"high\":${last.high},\"low\":${last.low},\"close\":${last.close}}')",
            null
        )
    }

    // Push SL/TP position overlay
    LaunchedEffect(position) {
        val wv = webViewRef.value ?: return@LaunchedEffect
        val json = if (position != null)
            "{\"stopLoss\":${position.stopLoss},\"takeProfit\":${position.takeProfit}}"
        else "null"
        wv.evaluateJavascript("updatePosition('$json')", null)
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).also { wv ->
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled  = true
                wv.webViewClient = WebViewClient()
                wv.loadUrl("file:///android_asset/chart.html")
                wv.evaluateJavascript("setSymbol('$symbol')", null)
                webViewRef.value = wv
            }
        },
        update = { wv -> webViewRef.value = wv },
    )
}

// ── 足迹主图 Canvas ─────────────────────────────────────────────────────────────

@Composable
private fun FootprintChart(
    candles:      List<FootprintCandle>,
    signal:       Signal?,
    indicators:   IndicatorState?,
    openPosition: OpenPosition?,
    modifier:     Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val tickSize = ChartViewModel.TICK

    Canvas(modifier = modifier) {
        if (candles.isEmpty()) return@Canvas

        val axisWidth = 68f
        val chartW    = size.width - axisWidth
        val chartH    = size.height - 24f
        val colW      = chartW / candles.size

        val priceMin   = candles.minOf { it.low  } - tickSize
        val priceMax   = candles.maxOf { it.high } + tickSize
        val priceRange = (priceMax - priceMin).coerceAtLeast(1.0)

        fun py(price: Double): Float = ((priceMax - price) / priceRange * chartH).toFloat()

        // 价格轴标签（无网格线，保持图表整洁）
        for (i in 0..5) {
            val p = priceMin + (priceMax - priceMin) * i / 5
            val y = py(p)
            val lbl = textMeasurer.measure(
                "\$${"%,.0f".format(p)}",
                TextStyle(fontSize = 9.sp, color = AXIS_TXT, fontFamily = FontFamily.Monospace),
            )
            drawText(lbl, topLeft = Offset(chartW + 2, y - lbl.size.height / 2f))
        }

        // Bollinger Bands（紫色虚线）
        indicators?.let { ind ->
            val bbClr  = Color(0xFFBA68C8).copy(alpha = 0.65f)
            val bbDash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
            listOf(ind.bbUpper, ind.bbLower).forEach { price ->
                val y = py(price)
                if (y in 0f..chartH) drawLine(bbClr, Offset(0f, y), Offset(chartW, y), 1f, pathEffect = bbDash)
            }
            val midY = py(ind.bbMid)
            if (midY in 0f..chartH)
                drawLine(bbClr.copy(alpha = 0.25f), Offset(0f, midY), Offset(chartW, midY), 0.5f)
        }

        // 实时价格水平虚线
        candles.lastOrNull()?.close?.let { livePrice ->
            val liveY = py(livePrice)
            var x = 0f
            while (x < chartW) {
                drawLine(Color.White.copy(alpha = 0.55f), Offset(x, liveY), Offset((x + 6f).coerceAtMost(chartW), liveY), 1f)
                x += 10f
            }
            val pLbl = textMeasurer.measure(
                "\$${"%,.1f".format(livePrice)}",
                TextStyle(fontSize = 9.sp, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            )
            drawRect(Color(0xFF1E2D40), Offset(chartW, liveY - pLbl.size.height / 2f - 2), Size(axisWidth, pLbl.size.height + 4f))
            drawText(pLbl, topLeft = Offset(chartW + 3f, liveY - pLbl.size.height / 2f))
        }

        // K线绘制
        candles.forEachIndexed { i, candle ->
            val x      = i * colW
            val isLive = !candle.isClosed
            val isBull = candle.close >= candle.open
            val bodyClr = if (isBull) GREEN else RED

            val highY = py(candle.high)
            val lowY  = py(candle.low)

            // 高 ATR 波动扩张辉光（K线 range > 1.5× atrPct）
            indicators?.let { ind ->
                val candleRangePct = (candle.high - candle.low) / candle.close.coerceAtLeast(1.0)
                if (candleRangePct > ind.atrPct * 1.5f) {
                    drawRect(GOLD.copy(alpha = 0.07f), Offset(x, 0f), Size(colW, chartH))
                }
            }

            // 影线
            drawLine(bodyClr.copy(alpha = 0.6f), Offset(x + colW / 2, highY), Offset(x + colW / 2, lowY), 1.5f)

            if (candle.levels.isNotEmpty()) {
                val pocPrice   = candle.levels.maxByOrNull { it.buyVol + it.sellVol }?.price
                val maxLvlVol  = candle.levels.maxOf { it.buyVol + it.sellVol }.coerceAtLeast(0.0001f)
                val stackedImb = computeStackedImbalances(candle.levels)

                candle.levels.forEach level@{ level ->
                    if (level.price < candle.low - tickSize || level.price > candle.high + tickSize) return@level

                    val rowTop = py(level.price + tickSize)
                    val rowBot = py(level.price)
                    val rH     = (rowBot - rowTop).coerceAtLeast(1f)

                    // 成交量强度着色
                    val intensity = ((level.buyVol + level.sellVol) / maxLvlVol).coerceIn(0.15f, 1f)
                    val bgAlpha   = 0.12f + 0.45f * intensity
                    val bgClr     = if (level.isBuyDominant) GREEN.copy(alpha = bgAlpha) else RED.copy(alpha = bgAlpha)
                    drawRect(bgClr, Offset(x + 1f, rowTop), Size(colW - 2f, rH))

                    // 堆叠失衡高亮（≥3 连续同向 3:1 → 金色）
                    if (level.price in stackedImb) {
                        drawRect(GOLD.copy(alpha = 0.40f), Offset(x + 1f, rowTop), Size(colW - 2f, rH))
                    }

                    // POC 横线（最大成交量价位）
                    if (level.price == pocPrice) {
                        drawLine(GOLD, Offset(x, rowTop), Offset(x + colW, rowTop), 1.5f)
                    }

                    // Sell × Buy 文字
                    if (rH >= 9f) {
                        val txt = "%.1f×%.1f".format(level.sellVol, level.buyVol)
                        val tm  = textMeasurer.measure(
                            txt, TextStyle(fontSize = 7.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                        )
                        if (tm.size.width < colW - 4 && tm.size.height <= rH) {
                            drawText(tm, topLeft = Offset(x + (colW - tm.size.width) / 2, rowTop + (rH - tm.size.height) / 2))
                        }
                    }
                }
            } else {
                // 历史K线：无层级数据 → 低透明度实体 + 轮廓，与实时足迹K线区分
                val openY   = py(candle.open)
                val closeY  = py(candle.close)
                val bodyTop = minOf(openY, closeY)
                val bodyH   = (maxOf(openY, closeY) - bodyTop).coerceAtLeast(2f)
                drawRect(bodyClr.copy(alpha = 0.28f), Offset(x + 2f, bodyTop), Size(colW - 4f, bodyH))
                drawRect(bodyClr.copy(alpha = 0.60f), Offset(x + 2f, bodyTop), Size(colW - 4f, bodyH),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f))
            }

            // Delta 标签（K线顶部，K缩写）
            if (candle.delta != 0f) {
                val dClr = if (candle.delta >= 0) GREEN else RED
                val dm = textMeasurer.measure(
                    formatDelta(candle.delta),
                    TextStyle(fontSize = 7.sp, color = dClr, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                )
                val dmX = (x + (colW - dm.size.width) / 2).coerceIn(0f, size.width - dm.size.width)
                drawText(dm, topLeft = Offset(dmX, highY - dm.size.height - 2))
            }

            // 时间轴标签：每 5 根一次 + 最后一根
            if (i % 5 == 0 || i == candles.lastIndex) {
                val ts = candle.openTime
                val hh = (ts / 3_600_000 % 24).toString().padStart(2, '0')
                val mm = (ts / 60_000    % 60).toString().padStart(2, '0')
                val tm = textMeasurer.measure("$hh:$mm", TextStyle(fontSize = 8.sp, color = AXIS_TXT))
                drawText(tm, topLeft = Offset(x + (colW - tm.size.width) / 2, chartH + 4))
            }
        }

        // SL / TP 虚线（有持仓时）
        openPosition?.let { pos ->
            val posDash = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
            val slY = py(pos.stopLoss)
            if (slY in 0f..chartH) {
                drawLine(RED.copy(alpha = 0.85f), Offset(0f, slY), Offset(chartW, slY), 1.5f, pathEffect = posDash)
                val slLbl = textMeasurer.measure("SL ${"%.0f".format(pos.stopLoss)}", TextStyle(fontSize = 8.sp, color = RED, fontFamily = FontFamily.Monospace))
                drawText(slLbl, topLeft = Offset(chartW + 2f, slY - slLbl.size.height / 2f))
            }
            val tpY = py(pos.takeProfit)
            if (tpY in 0f..chartH) {
                drawLine(GREEN.copy(alpha = 0.85f), Offset(0f, tpY), Offset(chartW, tpY), 1.5f, pathEffect = posDash)
                val tpLbl = textMeasurer.measure("TP ${"%.0f".format(pos.takeProfit)}", TextStyle(fontSize = 8.sp, color = GREEN, fontFamily = FontFamily.Monospace))
                drawText(tpLbl, topLeft = Offset(chartW + 2f, tpY - tpLbl.size.height / 2f))
            }
        }
    }
}

/** 堆叠失衡计算：连续 ≥3 层同向 3:1 失衡才标记，过滤单层噪音 */
private fun computeStackedImbalances(levels: List<FootprintLevel>): Set<Double> {
    val result = mutableSetOf<Double>()
    if (levels.size < 3) return result
    var runStart = 0
    var runDir   = 0  // +1=买主导, -1=卖主导, 0=无
    levels.forEachIndexed { i, lvl ->
        val s = lvl.sellVol; val b = lvl.buyVol
        val dir = if (s > 0 && b > 0) {
            when { b / s >= 3f -> 1; s / b >= 3f -> -1; else -> 0 }
        } else 0
        if (dir != 0 && dir == runDir) {
            if (i - runStart + 1 >= 3) {
                for (j in runStart..i) result.add(levels[j].price)
            }
        } else { runDir = dir; runStart = i }
    }
    return result
}

// ── 成交量纵向分布侧栏 ──────────────────────────────────────────────────────────

@Composable
private fun VolumeProfileBar(candles: List<FootprintCandle>, modifier: Modifier) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier.background(SUBPLOT_BG)) {
        val agg = HashMap<Double, Pair<Float, Float>>()  // price → (buyVol, sellVol)
        candles.forEach { c ->
            c.levels.forEach { lvl ->
                val (b, s) = agg[lvl.price] ?: (0f to 0f)
                agg[lvl.price] = (b + lvl.buyVol) to (s + lvl.sellVol)
            }
        }
        if (agg.isEmpty()) return@Canvas

        val tick       = ChartViewModel.TICK
        val priceMin   = candles.minOf { it.low  } - tick
        val priceMax   = candles.maxOf { it.high } + tick
        val priceRange = (priceMax - priceMin).coerceAtLeast(1.0)
        val maxVol     = agg.values.maxOf { (b, s) -> b + s }.coerceAtLeast(0.001f)
        val w = size.width - 2f; val h = size.height

        agg.forEach { (price, vols) ->
            val rowTop = ((priceMax - price - tick) / priceRange * h).toFloat()
            val rowBot = ((priceMax - price)         / priceRange * h).toFloat()
            val rH     = (rowBot - rowTop).coerceAtLeast(1f)
            val barW   = ((vols.first + vols.second) / maxVol * w).coerceAtLeast(1f)
            val clr    = (if (vols.first >= vols.second) GREEN else RED).copy(alpha = 0.60f)
            drawRect(clr, Offset(0f, rowTop), Size(barW, rH))
        }

        val lbl = textMeasurer.measure("VP", TextStyle(fontSize = 7.sp, color = AXIS_TXT))
        drawText(lbl, topLeft = Offset(2f, 2f))
    }
}

// ── CVD 子图 ────────────────────────────────────────────────────────────────────

@Composable
private fun CvdSubplot(cvd: List<Float>, modifier: Modifier) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier.background(SUBPLOT_BG)) {
        if (cvd.isEmpty()) return@Canvas
        val w = size.width; val h = size.height
        val n = cvd.size

        val minVal = cvd.minOrNull() ?: 0f
        val maxVal = cvd.maxOrNull() ?: 0f
        val range  = (maxVal - minVal).coerceAtLeast(0.1f)
        val inner  = h - 16f  // 上下各留 8px

        fun cy(v: Float): Float = 8f + (maxVal - v) / range * inner

        // 零线
        val zeroY = cy(0f).coerceIn(0f, h)
        drawLine(GRAY.copy(alpha = 0.4f), Offset(0f, zeroY), Offset(w, zeroY), 0.5f)

        // CVD 折线（上涨段绿色，下跌段红色）
        val colW = w / n
        for (i in 0 until n - 1) {
            val x1 = i * colW + colW / 2
            val x2 = (i + 1) * colW + colW / 2
            val y1 = cy(cvd[i])
            val y2 = cy(cvd[i + 1])
            drawLine(if (cvd[i + 1] >= cvd[i]) GREEN else RED, Offset(x1, y1), Offset(x2, y2), 1.5f)
        }

        // 当前值标签 + "CVD" 标题（K缩写避免截断）
        val lastV   = cvd.last()
        val absV    = kotlin.math.abs(lastV)
        val cvdStr  = if (absV >= 1000f) "${if (lastV >= 0) "+" else "-"}${"%.1f".format(absV / 1000f)}K"
                      else "${if (lastV >= 0) "+" else ""}${"%.0f".format(lastV)}"
        val valLbl = textMeasurer.measure(
            cvdStr,
            TextStyle(fontSize = 8.sp, color = if (lastV >= 0) GREEN else RED, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
        )
        val cvdLbl = textMeasurer.measure("CVD", TextStyle(fontSize = 7.sp, color = AXIS_TXT))
        drawText(cvdLbl, topLeft = Offset(4f, 2f))
        drawText(valLbl, topLeft = Offset((w - valLbl.size.width - 4f).coerceAtLeast(0f), 2f))
    }
}

// ── 点击检查弹窗 ────────────────────────────────────────────────────────────────

@Composable
private fun InspectOverlay(level: InspectedLevel, onDismiss: () -> Unit) {
    val delta = level.buyVol - level.sellVol
    val ratio = if (level.sellVol > 0 && level.buyVol > 0) {
        if (level.buyVol > level.sellVol) level.buyVol / level.sellVol
        else level.sellVol / level.buyVol
    } else null

    Box(
        modifier = Modifier.offset {
            IntOffset(
                level.tapX.roundToInt().coerceAtLeast(0),
                level.tapY.roundToInt().coerceAtLeast(0),
            )
        }
    ) {
        Card(
            modifier = Modifier.widthIn(min = 120.dp, max = 160.dp),
            shape = RoundedCornerShape(6.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xEE0F1923)),
            elevation = CardDefaults.cardElevation(6.dp),
            onClick = onDismiss,
        ) {
            Column(
                Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    "\$%.0f – \$%.0f".format(level.priceFrom, level.priceTo),
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                )
                HorizontalDivider(color = GRAY.copy(alpha = 0.4f), thickness = 0.5.dp)
                InspectRow("Sell (Bid)", "%.2f".format(level.sellVol), RED)
                InspectRow("Buy (Ask)",  "%.2f".format(level.buyVol),  GREEN)
                InspectRow("Delta",
                    "${if (delta >= 0) "+" else ""}${"%.2f".format(delta)}",
                    if (delta >= 0) GREEN else RED,
                )
                if (ratio != null) {
                    InspectRow("Ratio", "%.1f:1".format(ratio), GOLD)
                }
                InspectRow("Vol %", "${(level.pctOfCandle * 100).toInt()}%", AXIS_TXT)
            }
        }
    }
}

@Composable
private fun InspectRow(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AXIS_TXT, fontSize = 9.sp)
        Text(value, color = valueColor, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

// ── 信号概率表盘 ─────────────────────────────────────────────────────────────────

@Composable
private fun SignalGauge(result: InferenceResult?, indicators: IndicatorState?) {
    val prob    = result?.prob ?: 0.5f
    val shortT  = indicators?.shortThresh ?: 0.211f
    val longT   = indicators?.longThresh  ?: 0.680f
    val signal  = result?.signal

    val (label, color) = when (signal) {
        Signal.SHORT -> "▼ SHORT" to RED
        Signal.LONG  -> "▲ LONG"  to GREEN
        else         -> "─ WAIT"  to Color(0xFF9E9E9E)
    }
    val animColor by animateColorAsState(color, tween(400), label = "sig")

    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // 概率进度条（含阈值刻度线）
        Canvas(Modifier.width(116.dp).height(6.dp)) {
            val w = size.width; val h = size.height
            drawRect(Color(0xFF2A2A2A), size = Size(w, h))
            val fillColor = when {
                prob <= shortT -> RED
                prob >= longT  -> GREEN
                else           -> Color(0xFF666666)
            }
            drawRect(fillColor.copy(alpha = 0.85f), size = Size((prob * w).coerceIn(0f, w), h))
            // SHORT / LONG 阈值刻度
            drawLine(Color.White.copy(alpha = 0.75f), Offset(shortT * w, 0f), Offset(shortT * w, h), 1.5f)
            drawLine(Color.White.copy(alpha = 0.75f), Offset(longT  * w, 0f), Offset(longT  * w, h), 1.5f)
        }
        // 信号徽章
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.background(animColor, RoundedCornerShape(6.dp))
                               .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = "$label  ${(prob * 100).toInt()}%",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            )
        }
        // 各模型分解（信号触发时显示）
        if (signal == Signal.SHORT || signal == Signal.LONG) {
            result?.let { r ->
                Text(
                    "CB ${(r.probCat*100).toInt()}% · XGB ${(r.probXgb*100).toInt()}% · RF ${(r.probRf*100).toInt()}%",
                    color = AXIS_TXT, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

// ── ADX 子图 ──────────────────────────────────────────────────────────────────────

@Composable
private fun AdxSubplot(adxHistory: List<AdxPoint>, modifier: Modifier) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier.background(SUBPLOT_BG)) {
        if (adxHistory.isEmpty()) return@Canvas
        val w = size.width; val h = size.height
        val n = adxHistory.size
        val colW = w / n
        val inner = h - 12f  // 上下各留 6px

        fun vy(v: Float): Float = 6f + (100f - v.coerceIn(0f, 100f)) / 100f * inner

        // ADX 25 参考线（强趋势阈值）
        drawLine(GRAY.copy(alpha = 0.4f), Offset(0f, vy(25f)), Offset(w, vy(25f)), 0.5f)

        // ADX(金) / +DI(绿) / -DI(红) 折线
        for (i in 0 until n - 1) {
            val x1 = i * colW + colW / 2
            val x2 = (i + 1) * colW + colW / 2
            val a = adxHistory[i]; val b = adxHistory[i + 1]
            drawLine(GOLD,  Offset(x1, vy(a.adx)),     Offset(x2, vy(b.adx)),     1.5f)
            drawLine(GREEN, Offset(x1, vy(a.plusDi)),   Offset(x2, vy(b.plusDi)),  1.2f)
            drawLine(RED,   Offset(x1, vy(a.minusDi)),  Offset(x2, vy(b.minusDi)), 1.2f)
            // DI 交叉标记
            val crossBull = a.plusDi <= a.minusDi && b.plusDi > b.minusDi
            val crossBear = a.plusDi >= a.minusDi && b.plusDi < b.minusDi
            if (crossBull || crossBear) {
                drawCircle(
                    color  = if (crossBull) GREEN else RED,
                    radius = 3f,
                    center = Offset(x2, vy((b.plusDi + b.minusDi) / 2)),
                )
            }
        }

        val titleLbl = textMeasurer.measure("ADX", TextStyle(fontSize = 7.sp, color = AXIS_TXT))
        drawText(titleLbl, topLeft = Offset(4f, 2f))

        adxHistory.lastOrNull()?.let { last ->
            // 各指标独立着色：ADX金 / +DI绿 / -DI红
            val adxLbl   = textMeasurer.measure("ADX ${last.adx.toInt()}", TextStyle(fontSize = 9.sp, color = GOLD,  fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
            val plusLbl  = textMeasurer.measure("  +DI ${last.plusDi.toInt()}",  TextStyle(fontSize = 9.sp, color = GREEN, fontFamily = FontFamily.Monospace))
            val minusLbl = textMeasurer.measure("  -DI ${last.minusDi.toInt()}", TextStyle(fontSize = 9.sp, color = RED,   fontFamily = FontFamily.Monospace))
            val totalW   = adxLbl.size.width + plusLbl.size.width + minusLbl.size.width
            var xPos     = (w - totalW - 4f).coerceAtLeast(0f)
            drawText(adxLbl,   topLeft = Offset(xPos, 1f)); xPos += adxLbl.size.width
            drawText(plusLbl,  topLeft = Offset(xPos, 1f)); xPos += plusLbl.size.width
            drawText(minusLbl, topLeft = Offset(xPos, 1f))
        }
    }
}
