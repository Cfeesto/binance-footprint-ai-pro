package com.footprintai.app.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.footprintai.app.data.Ticker24h
import com.footprintai.app.model.FootprintCandle
import com.footprintai.app.model.InferenceResult
import com.footprintai.app.model.OpenPosition
import com.footprintai.app.model.Signal
import kotlin.math.floor
import kotlin.math.roundToInt

// ── Color palette ────────────────────────────────────────────────────────────────
private val BG       = Color(0xFF0D1117)
private val SURFACE  = Color(0xFF161B22)
private val GREEN    = Color(0xFF00E676)
private val RED      = Color(0xFFEF5350)
private val BLUE     = Color(0xFF2979FF)
private val GRAY     = Color(0xFF30363D)
private val AXIS_TXT = Color(0xFF8B949E)
private val GOLD     = Color(0xFFFFD700)

/** K-abbreviation: -2127.6 → -2.1K */
private fun formatDelta(v: Float): String {
    val abs = kotlin.math.abs(v)
    val sign = if (v >= 0) "+" else "-"
    return if (abs >= 1000f) "$sign${"%.1f".format(abs / 1000f)}K"
    else "$sign${"%.1f".format(abs)}"
}

private fun formatVolume(v: Double): String = when {
    v >= 1_000_000_000 -> "${"%.2f".format(v / 1_000_000_000)}B"
    v >= 1_000_000     -> "${"%.2f".format(v / 1_000_000)}M"
    v >= 1_000         -> "${"%.2f".format(v / 1_000)}K"
    else               -> "%.2f".format(v)
}

private fun String.toDisplaySymbol(): String =
    if (endsWith("USDT")) "${removeSuffix("USDT")}/USDT" else this

private data class InspectedLevel(
    val priceFrom: Double, val priceTo: Double,
    val buyVol: Float, val sellVol: Float,
    val pctOfCandle: Float,
    val tapX: Float, val tapY: Float,
)

private val ALL_SYMBOLS = listOf(
    "BTCUSDT","ETHUSDT","BNBUSDT","SOLUSDT","XRPUSDT",
    "ADAUSDT","DOGEUSDT","AVAXUSDT","DOTUSDT","LINKUSDT",
    "LTCUSDT","UNIUSDT","AAVEUSDT","ATOMUSDT","NEARUSDT",
    "MATICUSDT","APTUSDT","SUIUSDT","ARBUSDT","OPUSDT",
)
private val ALL_TIMEFRAMES = listOf("1m", "5m", "15m", "1h", "4h", "1D")

// ── Main screen ──────────────────────────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChartScreen(vm: ChartViewModel = viewModel(), modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showTv         by remember { mutableStateOf(false) }
    var symbolExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().background(BG)) {

        TopBar(
            selectedSymbol = state.selectedSymbol,
            ticker24h      = state.ticker24h,
            footprints     = state.footprints,
            symbolExpanded = symbolExpanded,
            onSymbolExpand = { symbolExpanded = it },
            onSymbolSelect = { vm.selectSymbol(it); symbolExpanded = false },
        )

        TfTabRow(
            selectedTf = state.selectedTimeframe,
            showTv     = showTv,
            onSelect   = { tf -> showTv = false; vm.selectTimeframe(if (tf == "1D") "1d" else tf) },
            onShowTv   = { showTv = it },
        )

        if (!state.engineReady) {
            LinearProgressIndicator(
                modifier   = Modifier.fillMaxWidth().height(2.dp),
                color      = BLUE, trackColor = GRAY,
            )
        }
        state.error?.let {
            Text(it, color = RED, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp))
        }

        if (showTv) {
            TvChartWebView(
                klines   = state.klines,
                position = null,
                symbol   = state.selectedSymbol,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val candles = state.footprints.takeLast(13)
            if (candles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BLUE)
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
                    // ── Chart + VP sidebar ───────────────────────────────────────
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            FootprintChart(
                                candles      = candles,
                                signal       = state.lastResult?.signal,
                                indicators   = state.indicators,
                                openPosition = null,
                                modifier     = Modifier
                                    .fillMaxSize()
                                    .pointerInput(candles, scale, offset) {
                                        detectTapGestures { tap ->
                                            val pivX = size.width  / 2f
                                            val pivY = size.height / 2f
                                            val cx   = (tap.x - pivX - offset.x) / scale + pivX
                                            val cy   = (tap.y - pivY - offset.y) / scale + pivY
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
                            // Next candle probability overlay (right side)
                            NextCandleProbability(
                                result   = state.lastResult,
                                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                            )
                            if (!autoFollow) {
                                Button(
                                    onClick = { autoFollow = true; inspected = null },
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                                    colors   = ButtonDefaults.buttonColors(containerColor = RED.copy(alpha = 0.85f)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text("● LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            inspected?.let { InspectOverlay(it, onDismiss = { inspected = null }) }
                        }
                        VolumeProfileBar(candles = candles, modifier = Modifier.width(40.dp).fillMaxHeight())
                    }

                    // ── MTF confluence strip ─────────────────────────────────────
                    MtfScannerPanel(
                        tfSignals       = state.tfSignals,
                        confluenceScore = state.confluenceScore,
                        confluenceMax   = state.confluenceMax,
                    )

                    // ── Ensemble ML confluence ───────────────────────────────────
                    EnsembleConfluencePanel(result = state.lastResult)

                    // ── Market scanner ───────────────────────────────────────────
                    MarketScannerPanel(rows = state.scannerRows)
                }
            }
        }
    }
}

// ── Top bar ──────────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    selectedSymbol: String,
    ticker24h:      Ticker24h?,
    footprints:     List<FootprintCandle>,
    symbolExpanded: Boolean,
    onSymbolExpand: (Boolean) -> Unit,
    onSymbolSelect: (String) -> Unit,
) {
    val livePrice  = footprints.lastOrNull()?.close
    val isBull     = footprints.lastOrNull()?.let { it.close >= it.open } == true
    val changePct  = ticker24h?.priceChangePercent ?: 0.0
    val change     = ticker24h?.priceChange ?: 0.0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SURFACE)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(GRAY, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .pointerInput(Unit) { detectTapGestures { onSymbolExpand(true) } },
                ) {
                    Text(selectedSymbol.toDisplaySymbol(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.width(4.dp))
                    Text("▼", color = AXIS_TXT, fontSize = 9.sp)
                }
                DropdownMenu(
                    expanded          = symbolExpanded,
                    onDismissRequest  = { onSymbolExpand(false) },
                    modifier          = Modifier.background(SURFACE),
                ) {
                    ALL_SYMBOLS.forEach { sym ->
                        DropdownMenuItem(
                            text    = { Text(sym.toDisplaySymbol(), color = if (sym == selectedSymbol) GREEN else Color.White, fontSize = 13.sp) },
                            onClick = { onSymbolSelect(sym) },
                        )
                    }
                }
            }

            if (ticker24h != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Stat24h("24H HIGH", "%.2f".format(ticker24h.highPrice), GREEN.copy(alpha = 0.85f))
                    Stat24h("24H LOW",  "%.2f".format(ticker24h.lowPrice),  RED.copy(alpha = 0.85f))
                    Stat24h("24H VOL",  formatVolume(ticker24h.quoteVolume), AXIS_TXT)
                }
            }
        }

        if (livePrice != null) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${"$%,.2f".format(livePrice)}",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                )
                Text(
                    "${if (changePct >= 0) "+" else ""}${"%.2f".format(changePct)}% (${if (change >= 0) "+" else ""}${"%.2f".format(change)})",
                    color = if (changePct >= 0) GREEN else RED,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun Stat24h(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text(label, color = AXIS_TXT, fontSize = 7.sp)
        Text(value, color = valueColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

// ── TF tab row ───────────────────────────────────────────────────────────────────

@Composable
private fun TfTabRow(
    selectedTf: String,
    showTv:     Boolean,
    onSelect:   (String) -> Unit,
    onShowTv:   (Boolean) -> Unit,
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .background(SURFACE)
            .padding(horizontal = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        ALL_TIMEFRAMES.forEach { tf ->
            val tfKey    = if (tf == "1D") "1d" else tf
            val selected = tfKey == selectedTf && !showTv
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.pointerInput(tf) { detectTapGestures { onSelect(tf) } },
            ) {
                Text(
                    tf,
                    color      = if (selected) BLUE else AXIS_TXT,
                    fontSize   = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier   = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
                Box(
                    Modifier
                        .height(2.dp)
                        .width(20.dp)
                        .background(if (selected) BLUE else Color.Transparent, RoundedCornerShape(1.dp)),
                )
                Spacer(Modifier.height(2.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        // TV toggle
        Text(
            "TV",
            color    = if (showTv) GOLD else AXIS_TXT,
            fontSize = 12.sp,
            fontWeight = if (showTv) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier
                .background(if (showTv) GOLD.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .pointerInput(Unit) { detectTapGestures { onShowTv(!showTv) } },
        )
    }
}

// ── MTF Scanner strip ────────────────────────────────────────────────────────────

@Composable
private fun MtfScannerPanel(
    tfSignals:       Map<String, String>,
    confluenceScore: Int,
    confluenceMax:   Int,
    modifier:        Modifier = Modifier,
) {
    val direction = when {
        tfSignals.values.count { it == "LONG"  } >= 3 -> "LONG"
        tfSignals.values.count { it == "SHORT" } >= 3 -> "SHORT"
        else -> "NONE"
    }
    val dirColor = when (direction) { "LONG" -> GREEN; "SHORT" -> RED; else -> AXIS_TXT }
    Row(
        modifier              = modifier.fillMaxWidth().background(Color(0xFF111820)).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("MTF", color = AXIS_TXT, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        listOf("1m","5m","15m","1h","4h").forEach { tf ->
            val sig   = tfSignals[tf] ?: "—"
            val c     = when (sig) { "LONG" -> GREEN; "SHORT" -> RED; else -> AXIS_TXT }
            val arrow = when (sig) { "LONG" -> "▲"; "SHORT" -> "▼"; else -> "─" }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(arrow, color = c,        fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(tf,    color = AXIS_TXT, fontSize = 7.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            "$confluenceScore/$confluenceMax $direction",
            color    = dirColor,
            fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(dirColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        )
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

    LaunchedEffect(klines) {
        val wv = webViewRef.value ?: return@LaunchedEffect
        if (klines.isEmpty()) return@LaunchedEffect
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

    LaunchedEffect(position) {
        val wv = webViewRef.value ?: return@LaunchedEffect
        val json = if (position != null)
            "{\"stopLoss\":${position.stopLoss},\"takeProfit\":${position.takeProfit}}"
        else "null"
        wv.evaluateJavascript("updatePosition('$json')", null)
    }

    AndroidView(
        modifier = modifier,
        factory  = { ctx ->
            WebView(ctx).also { wv ->
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled  = true
                wv.webViewClient = WebViewClient()
                wv.loadUrl("file:///android_asset/chart.html")
                wv.evaluateJavascript("setSymbol('$symbol')", null)
                webViewRef.value = wv
            }
        },
        update   = { wv -> webViewRef.value = wv },
    )
}

// ── Footprint chart canvas ───────────────────────────────────────────────────────

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

        // Price axis labels
        for (i in 0..5) {
            val p   = priceMin + (priceMax - priceMin) * i / 5
            val y   = py(p)
            val lbl = textMeasurer.measure(
                "\$${"%,.0f".format(p)}",
                TextStyle(fontSize = 9.sp, color = AXIS_TXT, fontFamily = FontFamily.Monospace),
            )
            drawText(lbl, topLeft = Offset(chartW + 2, y - lbl.size.height / 2f))
        }

        // Bollinger Bands
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

        // Live price dashed line
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

        // Candles
        candles.forEachIndexed { i, candle ->
            val x      = i * colW
            val isBull = candle.close >= candle.open
            val bodyClr = if (isBull) GREEN else RED
            val highY   = py(candle.high)
            val lowY    = py(candle.low)

            // High ATR glow
            indicators?.let { ind ->
                val rangePct = (candle.high - candle.low) / candle.close.coerceAtLeast(1.0)
                if (rangePct > ind.atrPct * 1.5f)
                    drawRect(GOLD.copy(alpha = 0.07f), Offset(x, 0f), Size(colW, chartH))
            }

            // Wick
            drawLine(bodyClr.copy(alpha = 0.6f), Offset(x + colW / 2, highY), Offset(x + colW / 2, lowY), 1.5f)

            if (candle.levels.isNotEmpty()) {
                val pocPrice   = candle.levels.maxByOrNull { it.buyVol + it.sellVol }?.price
                val maxLvlVol  = candle.levels.maxOf { it.buyVol + it.sellVol }.coerceAtLeast(0.0001f)
                val stackedImb = computeStackedImbalances(candle.levels)

                candle.levels.forEach level@{ level ->
                    if (level.price < candle.low - tickSize || level.price > candle.high + tickSize) return@level
                    val rowTop  = py(level.price + tickSize)
                    val rowBot  = py(level.price)
                    val rH      = (rowBot - rowTop).coerceAtLeast(1f)
                    val intensity = ((level.buyVol + level.sellVol) / maxLvlVol).coerceIn(0.15f, 1f)
                    val bgAlpha   = 0.12f + 0.45f * intensity
                    val bgClr     = if (level.isBuyDominant) GREEN.copy(alpha = bgAlpha) else RED.copy(alpha = bgAlpha)
                    drawRect(bgClr, Offset(x + 1f, rowTop), Size(colW - 2f, rH))
                    if (level.price in stackedImb)
                        drawRect(GOLD.copy(alpha = 0.40f), Offset(x + 1f, rowTop), Size(colW - 2f, rH))
                    if (level.price == pocPrice)
                        drawLine(GOLD, Offset(x, rowTop), Offset(x + colW, rowTop), 1.5f)
                    if (rH >= 9f) {
                        val txt = "%.1f×%.1f".format(level.sellVol, level.buyVol)
                        val tm  = textMeasurer.measure(
                            txt, TextStyle(fontSize = 7.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                        )
                        if (tm.size.width < colW - 4 && tm.size.height <= rH)
                            drawText(tm, topLeft = Offset(x + (colW - tm.size.width) / 2, rowTop + (rH - tm.size.height) / 2))
                    }
                }
            } else {
                val openY   = py(candle.open)
                val closeY  = py(candle.close)
                val bodyTop = minOf(openY, closeY)
                val bodyH   = (maxOf(openY, closeY) - bodyTop).coerceAtLeast(2f)
                drawRect(bodyClr.copy(alpha = 0.28f), Offset(x + 2f, bodyTop), Size(colW - 4f, bodyH))
                drawRect(
                    bodyClr.copy(alpha = 0.60f), Offset(x + 2f, bodyTop), Size(colW - 4f, bodyH),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f),
                )
            }

            // Delta label
            if (candle.delta != 0f) {
                val dClr = if (candle.delta >= 0) GREEN else RED
                val dm   = textMeasurer.measure(
                    formatDelta(candle.delta),
                    TextStyle(fontSize = 7.sp, color = dClr, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                )
                val dmX = (x + (colW - dm.size.width) / 2).coerceIn(0f, size.width - dm.size.width)
                drawText(dm, topLeft = Offset(dmX, highY - dm.size.height - 2))
            }

            // BUYERS WON / SELLERS WON badge (closed candles with footprint data)
            if (candle.isClosed && candle.levels.isNotEmpty() && candle.delta != 0f) {
                val won    = if (candle.delta > 0) "BUYERS WON" else "SELLERS WON"
                val wonClr = if (candle.delta > 0) GREEN else RED
                val wonTm  = textMeasurer.measure(
                    won,
                    TextStyle(fontSize = 5.5.sp, color = wonClr, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                )
                val bgW = wonTm.size.width + 4f
                val bgH = wonTm.size.height + 2f
                val bx  = x + (colW - bgW) / 2
                val by  = py(candle.low) + 4f
                if (by + bgH <= chartH) {
                    drawRect(wonClr.copy(alpha = 0.18f), Offset(bx, by), Size(bgW, bgH))
                    drawText(wonTm, topLeft = Offset(bx + 2f, by + 1f))
                }
            }

            // Time axis label
            if (i % 5 == 0 || i == candles.lastIndex) {
                val ts = candle.openTime
                val hh = (ts / 3_600_000 % 24).toString().padStart(2, '0')
                val mm = (ts / 60_000    % 60).toString().padStart(2, '0')
                val tm = textMeasurer.measure("$hh:$mm", TextStyle(fontSize = 8.sp, color = AXIS_TXT))
                drawText(tm, topLeft = Offset(x + (colW - tm.size.width) / 2, chartH + 4))
            }
        }

        // SL / TP lines
        openPosition?.let { pos ->
            val posDash = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
            val slY = py(pos.stopLoss)
            if (slY in 0f..chartH) {
                drawLine(RED.copy(alpha = 0.85f), Offset(0f, slY), Offset(chartW, slY), 1.5f, pathEffect = posDash)
                val lbl = textMeasurer.measure("SL ${"%.0f".format(pos.stopLoss)}", TextStyle(fontSize = 8.sp, color = RED, fontFamily = FontFamily.Monospace))
                drawText(lbl, topLeft = Offset(chartW + 2f, slY - lbl.size.height / 2f))
            }
            val tpY = py(pos.takeProfit)
            if (tpY in 0f..chartH) {
                drawLine(GREEN.copy(alpha = 0.85f), Offset(0f, tpY), Offset(chartW, tpY), 1.5f, pathEffect = posDash)
                val lbl = textMeasurer.measure("TP ${"%.0f".format(pos.takeProfit)}", TextStyle(fontSize = 8.sp, color = GREEN, fontFamily = FontFamily.Monospace))
                drawText(lbl, topLeft = Offset(chartW + 2f, tpY - lbl.size.height / 2f))
            }
        }
    }
}

/** Stacked imbalance detection: ≥3 consecutive same-direction 3:1 levels */
private fun computeStackedImbalances(levels: List<com.footprintai.app.model.FootprintLevel>): Set<Double> {
    val result = mutableSetOf<Double>()
    if (levels.size < 3) return result
    var runStart = 0; var runDir = 0
    levels.forEachIndexed { i, lvl ->
        val s = lvl.sellVol; val b = lvl.buyVol
        val dir = if (s > 0 && b > 0) when { b / s >= 3f -> 1; s / b >= 3f -> -1; else -> 0 } else 0
        if (dir != 0 && dir == runDir) {
            if (i - runStart + 1 >= 3) for (j in runStart..i) result.add(levels[j].price)
        } else { runDir = dir; runStart = i }
    }
    return result
}

// ── Volume Profile sidebar ────────────────────────────────────────────────────────

@Composable
private fun VolumeProfileBar(candles: List<FootprintCandle>, modifier: Modifier) {
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = modifier.background(Color(0xFF0E1117))) {
        val agg = HashMap<Double, Pair<Float, Float>>()
        candles.forEach { c -> c.levels.forEach { lvl ->
            val (b, s) = agg[lvl.price] ?: (0f to 0f)
            agg[lvl.price] = (b + lvl.buyVol) to (s + lvl.sellVol)
        } }
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

// ── Next Candle Probability overlay ──────────────────────────────────────────────

@Composable
private fun NextCandleProbability(result: InferenceResult?, modifier: Modifier = Modifier) {
    val signal = result?.signal ?: Signal.NEUTRAL
    if (signal == Signal.NEUTRAL) return
    val isLong = signal == Signal.LONG
    val color  = if (isLong) GREEN else RED
    val arrow  = if (isLong) "▲" else "▼"
    val label  = if (isLong) "LONG" else "SHORT"
    val prob   = result?.prob ?: 0.5f

    Box(
        modifier = modifier
            .background(Color(0xCC0D1117), RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text("NEXT CANDLE",    color = AXIS_TXT, fontSize = 6.5.sp, letterSpacing = 0.5.sp)
            Text("PROBABILITY",   color = AXIS_TXT, fontSize = 6.5.sp, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(2.dp))
            Text(arrow,           color = color,      fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("${(prob * 100).toInt()}%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(label,           color = color,      fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
        }
    }
}

// ── Ensemble ML Confluence panel ──────────────────────────────────────────────────

@Composable
private fun EnsembleConfluencePanel(result: InferenceResult?, modifier: Modifier = Modifier) {
    val ensemble = result?.prob ?: 0.5f
    fun modelSig(p: Float) = if (p >= 0.55f) "BUY" else if (p <= 0.45f) "SELL" else "HOLD"

    val gaugeLabel = when {
        ensemble >= 0.75f -> "STRONG BUY"
        ensemble >= 0.60f -> "BUY"
        ensemble <= 0.25f -> "STRONG SELL"
        ensemble <= 0.40f -> "SELL"
        else              -> "NEUTRAL"
    }
    val gaugeColor = when {
        ensemble >= 0.60f -> GREEN
        ensemble <= 0.40f -> RED
        else              -> AXIS_TXT
    }

    Surface(modifier = modifier.fillMaxWidth(), color = SURFACE) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "ENSEMBLE ML CONFLUENCE",
                fontWeight = FontWeight.Bold, fontSize = 10.sp, color = AXIS_TXT, letterSpacing = 1.sp,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    Triple("Lorentzian", result?.probLor ?: 0.5f, "35%"),
                    Triple("CatBoost",   result?.probCat ?: 0.5f, "30%"),
                    Triple("RF",         result?.probRf  ?: 0.5f, "10%"),
                    Triple("XGBoost",    result?.probXgb ?: 0.5f, "25%"),
                ).forEach { (name, prob, weight) ->
                    val sig = modelSig(prob)
                    val clr = if (sig == "BUY") GREEN else if (sig == "SELL") RED else AXIS_TXT
                    Column(
                        modifier            = Modifier
                            .weight(1f)
                            .background(BG, RoundedCornerShape(8.dp))
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Canvas(Modifier.size(7.dp)) { drawCircle(clr) }
                        Text("${(prob * 100).toInt()}%",
                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(sig,    color = clr,      fontSize = 8.sp,  fontWeight = FontWeight.SemiBold)
                        Text(name,   color = AXIS_TXT, fontSize = 7.5.sp)
                        Text(weight, color = AXIS_TXT, fontSize = 7.sp)
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SpeedometerGauge(ensemble, Modifier.size(width = 90.dp, height = 55.dp))
                Column {
                    Text("SIGNAL STRENGTH", color = AXIS_TXT, fontSize = 8.sp, letterSpacing = 1.sp)
                    Text(gaugeLabel, color = gaugeColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("${(ensemble * 100).toInt()}% confidence", color = AXIS_TXT, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun SpeedometerGauge(prob: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx     = size.width / 2
        val cy     = size.height * 0.88f
        val radius = size.width * 0.40f
        val strokeW = radius * 0.22f
        val tl     = Offset(cx - radius, cy - radius)
        val arcSz  = Size(radius * 2, radius * 2)
        // 5 colored segments from left (strong sell) to right (strong buy)
        val segments = listOf(
            0.00f to RED,
            0.20f to Color(0xFFFF7043),
            0.40f to Color(0xFFFFEB3B),
            0.60f to Color(0xFF66BB6A),
            0.80f to GREEN,
        )
        segments.forEachIndexed { idx, (pct, clr) ->
            val nextPct = if (idx < segments.lastIndex) segments[idx + 1].first else 1.0f
            drawArc(
                color      = clr,
                startAngle = 180f + 180f * pct,
                sweepAngle = 180f * (nextPct - pct),
                useCenter  = false,
                topLeft    = tl, size = arcSz,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = strokeW,
                    cap   = androidx.compose.ui.graphics.StrokeCap.Butt,
                ),
            )
        }
        // Needle
        val angle = (180f + 180f * prob.coerceIn(0f, 1f)) * (kotlin.math.PI / 180.0)
        val nl    = radius * 0.72f
        val nx    = (cx + nl * kotlin.math.cos(angle)).toFloat()
        val ny    = (cy + nl * kotlin.math.sin(angle)).toFloat()
        drawLine(
            Color.White, Offset(cx, cy), Offset(nx, ny), 2.5f,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawCircle(Color.White, radius = 3.5f, center = Offset(cx, cy))
    }
}

// ── Market Scanner panel ──────────────────────────────────────────────────────────

@Composable
private fun MarketScannerPanel(rows: List<ScannerRow>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    Surface(modifier = modifier.fillMaxWidth(), color = SURFACE) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "MARKET SCANNER",
                fontWeight = FontWeight.Bold, fontSize = 10.sp, color = AXIS_TXT, letterSpacing = 1.sp,
            )
            Row(Modifier.fillMaxWidth()) {
                Text("Pair",        color = AXIS_TXT, fontSize = 8.sp, modifier = Modifier.weight(1.2f))
                Text("Price",       color = AXIS_TXT, fontSize = 8.sp, modifier = Modifier.weight(1.5f))
                Text("Confluence",  color = AXIS_TXT, fontSize = 8.sp, modifier = Modifier.weight(2f))
            }
            rows.take(5).forEach { row ->
                val sigClr = when (row.signal) { "LONG" -> GREEN; "SHORT" -> RED; else -> AXIS_TXT }
                val barClr = if (row.confluencePct >= 50) GREEN else RED
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.symbol.toDisplaySymbol(),
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1.2f),
                    )
                    Text(
                        if (row.price > 999) "%,.0f".format(row.price) else "%.4f".format(row.price),
                        color = sigClr, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1.5f),
                    )
                    Row(
                        Modifier.weight(2f),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        LinearProgressIndicator(
                            progress  = { row.confluencePct / 100f },
                            modifier  = Modifier.weight(1f).height(4.dp),
                            color     = barClr,
                            trackColor = GRAY,
                        )
                        Text("${row.confluencePct}%", color = barClr, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

// ── Inspect overlay ───────────────────────────────────────────────────────────────

@Composable
private fun InspectOverlay(level: InspectedLevel, onDismiss: () -> Unit) {
    val delta = level.buyVol - level.sellVol
    val ratio = if (level.sellVol > 0 && level.buyVol > 0) {
        if (level.buyVol > level.sellVol) level.buyVol / level.sellVol
        else level.sellVol / level.buyVol
    } else null
    Box(modifier = Modifier.offset {
        IntOffset(level.tapX.roundToInt().coerceAtLeast(0), level.tapY.roundToInt().coerceAtLeast(0))
    }) {
        Card(
            modifier  = Modifier.widthIn(min = 120.dp, max = 160.dp),
            shape     = RoundedCornerShape(6.dp),
            colors    = CardDefaults.cardColors(containerColor = Color(0xEE0F1923)),
            elevation = CardDefaults.cardElevation(6.dp),
            onClick   = onDismiss,
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("\$%.0f – \$%.0f".format(level.priceFrom, level.priceTo),
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                HorizontalDivider(color = GRAY.copy(alpha = 0.4f), thickness = 0.5.dp)
                InspectRow("Sell (Bid)", "%.2f".format(level.sellVol), RED)
                InspectRow("Buy (Ask)",  "%.2f".format(level.buyVol),  GREEN)
                InspectRow("Delta",
                    "${if (delta >= 0) "+" else ""}${"%.2f".format(delta)}",
                    if (delta >= 0) GREEN else RED)
                if (ratio != null) InspectRow("Ratio", "%.1f:1".format(ratio), GOLD)
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
