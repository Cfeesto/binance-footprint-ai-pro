package com.footprintai.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: ChartViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.appSettings

    // 本地编辑状态（滑动期间不立即保存）
    var shortThresh    by remember(s) { mutableFloatStateOf(s.shortThresh) }
    var longThresh     by remember(s) { mutableFloatStateOf(s.longThresh) }
    var riskPct        by remember(s) { mutableFloatStateOf(s.riskPct * 100f) }     // 显示为 %
    var slAtrMult      by remember(s) { mutableFloatStateOf(s.slAtrMult) }
    var enableLong     by remember(s) { mutableStateOf(s.enableLong) }
    var maxDrawdownPct by remember(s) { mutableFloatStateOf(s.maxDrawdownPct * 100f) } // 显示为 %

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Strategy Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Changes apply immediately. Thresholds recalibrated for 3-model ensemble (no Lorentzian).",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp,
            )
        }

        // SHORT 阈值
        item {
            SettingsSlider(
                label    = "SHORT Threshold",
                value    = shortThresh,
                valueStr = "%.2f".format(shortThresh),
                range    = 0.15f..0.45f,
                onValue  = { shortThresh = it },
                onDone   = { vm.updateSettings(s.copy(shortThresh = shortThresh)) },
                hint     = "Signal fires when ensemble ≤ this value",
            )
        }

        // LONG 阈值
        item {
            SettingsSlider(
                label    = "LONG Threshold",
                value    = longThresh,
                valueStr = "%.2f".format(longThresh),
                range    = 0.55f..0.85f,
                onValue  = { longThresh = it },
                onDone   = { vm.updateSettings(s.copy(longThresh = longThresh)) },
                hint     = "Signal fires when ensemble ≥ this value",
            )
        }

        // 风险比例
        item {
            SettingsSlider(
                label    = "Risk per Trade",
                value    = riskPct,
                valueStr = "${"%.1f".format(riskPct)}%",
                range    = 1f..5f,
                onValue  = { riskPct = it },
                onDone   = { vm.updateSettings(s.copy(riskPct = riskPct / 100f)) },
                hint     = "Max balance % lost per trade (Kelly-inspired sizing)",
            )
        }

        // SL ATR 乘数
        item {
            SettingsSlider(
                label    = "SL ATR Multiplier",
                value    = slAtrMult,
                valueStr = "%.1f×".format(slAtrMult),
                range    = 1.0f..3.0f,
                onValue  = { slAtrMult = it },
                onDone   = { vm.updateSettings(s.copy(slAtrMult = slAtrMult)) },
                hint     = "Stop Loss = ATR14 × multiplier (clamped 1.5%-5%)",
            )
        }

        // 最大回撤
        item {
            SettingsSlider(
                label    = "Max Drawdown Kill",
                value    = maxDrawdownPct,
                valueStr = "${"%.0f".format(maxDrawdownPct)}%",
                range    = 10f..50f,
                onValue  = { maxDrawdownPct = it },
                onDone   = { vm.updateSettings(s.copy(maxDrawdownPct = maxDrawdownPct / 100f)) },
                hint     = "Stop new trades if balance drops this much from start",
            )
        }

        // LONG 开关
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Enable LONG Trades", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "LONG signals re-enabled after threshold recalibration",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked         = enableLong,
                        onCheckedChange = { v ->
                            enableLong = v
                            vm.updateSettings(s.copy(enableLong = v))
                        },
                    )
                }
            }
        }

        // 版本信息
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "Model: Lorentzian 35% · CatBoost 30% · XGBoost 25% · RF 10%\n" +
                "Feature window: 500 bars  ·  Training window: 2000 bars",
                fontSize = 11.sp,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun SettingsSlider(
    label:    String,
    value:    Float,
    valueStr: String,
    range:    ClosedFloatingPointRange<Float>,
    onValue:  (Float) -> Unit,
    onDone:   () -> Unit,
    hint:     String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(valueStr, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value         = value,
                onValueChange = onValue,
                onValueChangeFinished = onDone,
                valueRange    = range,
                modifier      = Modifier.fillMaxWidth(),
            )
            Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
