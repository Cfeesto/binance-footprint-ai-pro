package com.footprintai.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SettingsScreen(vm: ChartViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.appSettings

    // Local edit state (don't save on every keystroke for text fields)
    var shortThresh    by remember(s) { mutableFloatStateOf(s.shortThresh) }
    var longThresh     by remember(s) { mutableFloatStateOf(s.longThresh) }
    var riskPct        by remember(s) { mutableFloatStateOf(s.riskPct * 100f) }
    var slAtrMult      by remember(s) { mutableFloatStateOf(s.slAtrMult) }
    var enableLong     by remember(s) { mutableStateOf(s.enableLong) }
    var maxDrawdownPct by remember(s) { mutableFloatStateOf(s.maxDrawdownPct * 100f) }
    var metaToken      by remember(s) { mutableStateOf(s.metaApiToken) }
    var metaAccountId  by remember(s) { mutableStateOf(s.metaApiAccountId) }
    var metaRegion     by remember(s) { mutableStateOf(s.metaApiRegion) }
    var tradingSymbol  by remember(s) { mutableStateOf(s.tradingSymbol) }
    var showToken      by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── MetaApi credentials ───────────────────────────────────────────────
        item {
            Text("MetaApi (MT5 Gateway)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Get free API token at metaapi.cloud. Supports any MT4/MT5 broker.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp,
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Token
                    OutlinedTextField(
                        value         = metaToken,
                        onValueChange = { metaToken = it },
                        label         = { Text("API Token") },
                        singleLine    = true,
                        visualTransformation = if (showToken) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        trailingIcon  = {
                            TextButton(onClick = { showToken = !showToken },
                                contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Text(if (showToken) "Hide" else "Show", fontSize = 11.sp)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Account ID
                    OutlinedTextField(
                        value         = metaAccountId,
                        onValueChange = { metaAccountId = it },
                        label         = { Text("MT5 Account ID") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                    )

                    // Region
                    OutlinedTextField(
                        value         = metaRegion,
                        onValueChange = { metaRegion = it },
                        label         = { Text("Region (e.g. new-york)") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                    )

                    // Trading symbol
                    OutlinedTextField(
                        value         = tradingSymbol,
                        onValueChange = { tradingSymbol = it.uppercase() },
                        label         = { Text("MT5 Symbol (e.g. ETHUSD, XAUUSD, EURUSD)") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                    )

                    Button(
                        onClick  = {
                            vm.updateSettings(s.copy(
                                metaApiToken     = metaToken.trim(),
                                metaApiAccountId = metaAccountId.trim(),
                                metaApiRegion    = metaRegion.trim().ifBlank { "new-york" },
                                tradingSymbol    = tradingSymbol.trim().ifBlank { "ETHUSD" },
                            ))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Connect MetaApi")
                    }

                    if (state.mtConnected) {
                        Text("✓ Connected", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }
                    state.mtError?.let { err ->
                        Text(err, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }
                }
            }
        }

        // ── Strategy settings ─────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(4.dp))
            Text("Strategy Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Thresholds recalibrated for 4-model ensemble (Lorentzian 35% · CB 30% · XGB 25% · RF 10%).",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp,
            )
        }

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

        item {
            SettingsSlider(
                label    = "Risk per Trade",
                value    = riskPct,
                valueStr = "${"%.1f".format(riskPct)}%",
                range    = 0.5f..5f,
                onValue  = { riskPct = it },
                onDone   = { vm.updateSettings(s.copy(riskPct = riskPct / 100f)) },
                hint     = "Max balance % risked per trade (Kelly-inspired sizing)",
            )
        }

        item {
            SettingsSlider(
                label    = "SL ATR Multiplier",
                value    = slAtrMult,
                valueStr = "%.1f×".format(slAtrMult),
                range    = 1.0f..3.0f,
                onValue  = { slAtrMult = it },
                onDone   = { vm.updateSettings(s.copy(slAtrMult = slAtrMult)) },
                hint     = "Stop Loss = ATR14 × multiplier (clamped 0.5%-5%)",
            )
        }

        item {
            SettingsSlider(
                label    = "Max Drawdown Kill",
                value    = maxDrawdownPct,
                valueStr = "${"%.0f".format(maxDrawdownPct)}%",
                range    = 10f..50f,
                onValue  = { maxDrawdownPct = it },
                onDone   = { vm.updateSettings(s.copy(maxDrawdownPct = maxDrawdownPct / 100f)) },
                hint     = "Stop new trades if account drops this much from high",
            )
        }

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
                            "Buy MT5 orders on LONG signals",
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
                value                = value,
                onValueChange        = onValue,
                onValueChangeFinished = onDone,
                valueRange           = range,
                modifier             = Modifier.fillMaxWidth(),
            )
            Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
