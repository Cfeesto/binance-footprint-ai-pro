package com.footprintai.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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

    val ctx = LocalContext.current

    // Local edit state (don't save on every keystroke for text fields)
    var shortThresh    by remember(s) { mutableFloatStateOf(s.shortThresh) }
    var longThresh     by remember(s) { mutableFloatStateOf(s.longThresh) }
    var riskPct        by remember(s) { mutableFloatStateOf(s.riskPct * 100f) }
    var slAtrMult      by remember(s) { mutableFloatStateOf(s.slAtrMult) }
    var enableLong     by remember(s) { mutableStateOf(s.enableLong) }
    var maxDrawdownPct by remember(s) { mutableFloatStateOf(s.maxDrawdownPct * 100f) }
    var tvKey          by remember(s) { mutableStateOf(s.tvWebhookKey) }
    // VPS + exchange
    var vpsWsUrl       by remember(s) { mutableStateOf(s.vpsWsUrl) }
    var vpsApiUrl      by remember(s) { mutableStateOf(s.vpsApiUrl) }
    var liveExchange   by remember(s) { mutableStateOf(s.liveExchange) }
    var binanceKey     by remember(s) { mutableStateOf(s.binanceApiKey) }
    var binanceSecret  by remember(s) { mutableStateOf(s.binanceApiSecret) }
    var hlPrivKey      by remember(s) { mutableStateOf(s.hlPrivateKey) }
    var bybitKey       by remember(s) { mutableStateOf(s.bybitApiKey) }
    var bybitSecret    by remember(s) { mutableStateOf(s.bybitApiSecret) }
    var okxKey         by remember(s) { mutableStateOf(s.okxApiKey) }
    var okxSecret      by remember(s) { mutableStateOf(s.okxApiSecret) }
    var okxPass        by remember(s) { mutableStateOf(s.okxPassphrase) }
    var showSecrets    by remember { mutableStateOf(false) }
    val exchanges      = listOf("binance", "hyperliquid", "bybit", "okx")
    var exchExpanded   by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── TradingView webhook ───────────────────────────────────────────────
        item {
            Text("TradingView Webhook", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Any TradingView strategy can paper trade by posting alerts to your webhook URL.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp,
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value         = tvKey,
                        onValueChange = { tvKey = it },
                        label         = { Text("Webhook Key (≥6 chars)") },
                        singleLine    = true,
                        placeholder   = { Text("my-secret-key") },
                        modifier      = Modifier.fillMaxWidth(),
                    )
                    val webhookUrl = "https://openclawapi.org/api/tv/webhook?key=${tvKey.trim().ifBlank { "<your-key>" }}"
                    Text(
                        "Webhook URL:",
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        webhookUrl,
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick  = {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("webhook_url", webhookUrl))
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Copy URL", fontSize = 12.sp) }
                        Button(
                            onClick  = { vm.updateSettings(s.copy(tvWebhookKey = tvKey.trim())) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Save Key", fontSize = 12.sp) }
                    }
                    Text(
                        "Alert message JSON: {\"action\":\"buy\",\"symbol\":\"ETHUSD\",\"price\":\"{{close}}\",\"interval\":\"{{interval}}\",\"time\":\"{{timenow}}\"}",
                        fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 13.sp,
                    )
                }
            }
        }

        // ── VPS Signal Server ─────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(4.dp))
            Text("VPS Signal Server", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Connect to your VPS running signal_server.py. Paper trading runs on VPS; go live once ≥52% win rate over 50+ trades.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp,
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value         = vpsWsUrl,
                        onValueChange = { vpsWsUrl = it },
                        label         = { Text("WebSocket URL") },
                        placeholder   = { Text("ws://1.2.3.4:8001/ws") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value         = vpsApiUrl,
                        onValueChange = { vpsApiUrl = it },
                        label         = { Text("REST API URL") },
                        placeholder   = { Text("http://1.2.3.4:8001") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                    )

                    // Exchange selector
                    Text("Live Exchange", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Box {
                        OutlinedButton(
                            onClick  = { exchExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(liveExchange.replaceFirstChar { it.uppercase() })
                        }
                        DropdownMenu(expanded = exchExpanded, onDismissRequest = { exchExpanded = false }) {
                            exchanges.forEach { ex ->
                                DropdownMenuItem(
                                    text    = { Text(ex.replaceFirstChar { it.uppercase() }) },
                                    onClick = { liveExchange = ex; exchExpanded = false },
                                )
                            }
                        }
                    }

                    // Exchange-specific API keys
                    val passViz = if (showSecrets) VisualTransformation.None else PasswordVisualTransformation()
                    when (liveExchange) {
                        "binance" -> {
                            OutlinedTextField(value = binanceKey, onValueChange = { binanceKey = it },
                                label = { Text("Binance API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = binanceSecret, onValueChange = { binanceSecret = it },
                                label = { Text("Binance API Secret") }, singleLine = true,
                                visualTransformation = passViz, modifier = Modifier.fillMaxWidth())
                        }
                        "hyperliquid" -> {
                            OutlinedTextField(value = hlPrivKey, onValueChange = { hlPrivKey = it },
                                label = { Text("EVM Private Key (0x…)") }, singleLine = true,
                                visualTransformation = passViz, modifier = Modifier.fillMaxWidth())
                        }
                        "bybit" -> {
                            OutlinedTextField(value = bybitKey, onValueChange = { bybitKey = it },
                                label = { Text("Bybit API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = bybitSecret, onValueChange = { bybitSecret = it },
                                label = { Text("Bybit API Secret") }, singleLine = true,
                                visualTransformation = passViz, modifier = Modifier.fillMaxWidth())
                        }
                        "okx" -> {
                            OutlinedTextField(value = okxKey, onValueChange = { okxKey = it },
                                label = { Text("OKX API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = okxSecret, onValueChange = { okxSecret = it },
                                label = { Text("OKX API Secret") }, singleLine = true,
                                visualTransformation = passViz, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = okxPass, onValueChange = { okxPass = it },
                                label = { Text("OKX Passphrase") }, singleLine = true,
                                visualTransformation = passViz, modifier = Modifier.fillMaxWidth())
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick  = { showSecrets = !showSecrets },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (showSecrets) "Hide Keys" else "Show Keys", fontSize = 12.sp) }
                        Button(
                            onClick  = {
                                vm.updateSettings(s.copy(
                                    vpsWsUrl         = vpsWsUrl.trim(),
                                    vpsApiUrl        = vpsApiUrl.trim(),
                                    liveExchange     = liveExchange,
                                    binanceApiKey    = binanceKey.trim(),
                                    binanceApiSecret = binanceSecret.trim(),
                                    hlPrivateKey     = hlPrivKey.trim(),
                                    bybitApiKey      = bybitKey.trim(),
                                    bybitApiSecret   = bybitSecret.trim(),
                                    okxApiKey        = okxKey.trim(),
                                    okxApiSecret     = okxSecret.trim(),
                                    okxPassphrase    = okxPass.trim(),
                                ))
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Save & Connect", fontSize = 12.sp) }
                    }

                    if (state.vpsConnected) {
                        Text("✓ VPS Connected", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                        state.vpsSignal?.let { sig ->
                            Text(
                                "Paper: ${"%.0f".format(sig.paperWinRate)}% WR · ${sig.paperTrades} trades · ROI ${"%.1f".format(sig.paperRoiPct)}%",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                            "Enable LONG signals",
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
