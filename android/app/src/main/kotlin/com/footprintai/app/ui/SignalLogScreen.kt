package com.footprintai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.footprintai.app.model.InferenceResult
import com.footprintai.app.model.Signal
import java.text.SimpleDateFormat
import java.util.*

private val SIG_GREEN = Color(0xFF26A69A)
private val SIG_RED   = Color(0xFFEF5350)

@Composable
fun SignalLogScreen(vm: ChartViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val log = state.signalLog.reversed()

    if (log.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No signals yet", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Text("Signals fire on every closed 5m candle", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text("Signal Log", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("${log.size} signals", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        LazyColumn {
            items(log) { r -> SignalRow(r) }
        }
    }
}

@Composable
private fun SignalRow(r: InferenceResult) {
    val dateFmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.US) }
    val isLong  = r.signal == Signal.LONG
    val color   = if (isLong) SIG_GREEN else SIG_RED
    val arrow   = if (isLong) "▲" else "▼"
    val label   = if (isLong) "LONG" else "SHORT"

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // 方向标识
        Box(
            Modifier.width(56.dp).background(color.copy(alpha = 0.15f),
                androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("$arrow $label", color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }

        // 时间 + 价格
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(dateFmt.format(Date(r.kline.openTime)), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text("\$${"%,.1f".format(r.kline.close)}", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        }

        // 概率分解
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "Ens ${(r.prob * 100).toInt()}%",
                fontWeight = FontWeight.Bold, fontSize = 12.sp, color = color,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "LOR ${(r.probLor*100).toInt()}  CB ${(r.probCat*100).toInt()}  XGB ${(r.probXgb*100).toInt()}  RF ${(r.probRf*100).toInt()}",
                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
    HorizontalDivider(thickness = 0.5.dp)
}
