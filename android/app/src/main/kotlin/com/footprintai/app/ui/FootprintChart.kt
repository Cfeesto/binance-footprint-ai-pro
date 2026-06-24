package com.footprintai.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.footprintai.app.model.Kline
import kotlin.math.max
import kotlin.math.min

@Composable
fun FootprintChart(
    klines: List<Kline>,
    modifier: Modifier = Modifier
) {
    if (klines.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val upColor = Color(0xFF00C853)
    val downColor = Color(0xFFD50000)
    val textColor = Color.White
    
    Box(modifier = modifier.background(Color(0xFF0B0E11))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val padding = 40.dp.toPx()
            val chartWidth = size.width - padding
            val chartHeight = size.height - padding
            
            val maxPrice = klines.maxOf { it.high }
            val minPrice = klines.minOf { it.low }
            val priceRange = maxPrice - minPrice
            
            val barWidth = chartWidth / klines.size
            
            klines.forEachIndexed { index, kline ->
                val x = index * barWidth
                val isUp = kline.close >= kline.open
                val candleColor = if (isUp) upColor else downColor
                
                // Draw Candle Wick
                val highY = ((maxPrice - kline.high) / priceRange * chartHeight).toFloat()
                val lowY = ((maxPrice - kline.low) / priceRange * chartHeight).toFloat()
                drawLine(
                    color = Color.Gray,
                    start = Offset(x + barWidth / 2, highY),
                    end = Offset(x + barWidth / 2, lowY),
                    strokeWidth = 1.dp.toPx()
                )
                
                // Draw Footprint Body (Simulated with Buy/Sell volume labels)
                val openY = ((maxPrice - kline.open) / priceRange * chartHeight).toFloat()
                val closeY = ((maxPrice - kline.close) / priceRange * chartHeight).toFloat()
                val bodyTop = min(openY, closeY)
                val bodyBottom = max(openY, closeY)
                
                drawRect(
                    color = candleColor.copy(alpha = 0.3f),
                    topLeft = Offset(x + 2.dp.toPx(), bodyTop),
                    size = Size(barWidth - 4.dp.toPx(), max(bodyBottom - bodyTop, 1.dp.toPx()))
                )

                // Simplified Footprint Labels (Buy x Sell)
                val buyVol = String.format("%.1f", kline.buyVolume)
                val sellVol = String.format("%.1f", kline.volume - kline.buyVolume)
                val footprintText = "$buyVol x $sellVol"
                
                val textLayoutResult = textMeasurer.measure(
                    text = footprintText,
                    style = TextStyle(color = textColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                )
                
                // Only draw text if it fits
                if (barWidth > textLayoutResult.size.width) {
                    drawText(
                        textLayoutResult = textLayoutResult,
                        topLeft = Offset(x + (barWidth - textLayoutResult.size.width) / 2, bodyTop - 12.dp.toPx())
                    )
                }
            }
        }
    }
}
