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
import com.footprintai.app.model.CloseReason
import com.footprintai.app.model.Signal
import com.footprintai.app.model.TradeRecord
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun PortfolioScreen(vm: ChartViewModel = viewModel()) {
    val state   by vm.state.collectAsStateWithLifecycle()
    val settings = state.appSettings
    val paper    = state.paperAccount
    val fmt      = remember { SimpleDateFormat("MM/dd HH:mm", Locale.US) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Paper trading account ─────────────────────────────────────────────
        item {
            val roi    = (paper.balance - paper.startBalance) / paper.startBalance * 100.0
            val winPct = if (paper.totalTrades > 0) paper.winTrades * 100.0 / paper.totalTrades else 0.0
            val pnl    = paper.balance - paper.startBalance
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Paper Trading", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        TextButton(onClick = { vm.resetPaperAccount() }) { Text("Reset", fontSize = 12.sp) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom) {
                        Text("${"%.2f".format(paper.balance)} USDT",
                            fontWeight = FontWeight.Bold, fontSize = 26.sp)
                        Text(
                            "${if (pnl >= 0) "+" else ""}${"%.2f".format(pnl)} (${"%.1f".format(roi)}%)",
                            color = if (pnl >= 0) Color(0xFF00C853) else Color(0xFFD50000),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        AccountStat("Start", "${"%.0f".format(paper.startBalance)}")
                        AccountStat("Win Rate", "${"%.0f".format(winPct)}%")
                        AccountStat("Trades", "${paper.totalTrades}")
                    }
                    if (settings.tvWebhookKey.isBlank()) {
                        Text(
                            "Set a TradingView Webhook Key in Settings to start paper trading",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        paper.openPosition?.let { pos ->
                            val dir = if (pos.direction == Signal.LONG) "▲ LONG" else "▼ SHORT"
                            val clr = if (pos.direction == Signal.LONG) Color(0xFF00C853) else Color(0xFFD50000)
                            Text("Open: $dir @ ${"%.4f".format(pos.entryPrice)}  SL ${"%.4f".format(pos.stopLoss)}  TP ${"%.4f".format(pos.takeProfit)}",
                                fontSize = 11.sp, color = clr)
                        }
                    }
                }
            }
        }

        // ── VPS paper trading + Go Live ───────────────────────────────────────
        state.vpsSignal?.let { sig ->
            item {
                val liveColor = if (sig.liveMode) Color(0xFF00C853) else MaterialTheme.colorScheme.secondaryContainer
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors   = CardDefaults.cardColors(containerColor = liveColor.copy(alpha = if (sig.liveMode) 0.15f else 1f)),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (sig.liveMode) "🔴 LIVE — ${sig.exchange.uppercase()}" else "VPS Paper Trading",
                                fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            )
                            if (sig.liveMode) {
                                OutlinedButton(onClick = { vm.demoteLive() }) {
                                    Text("Demote", fontSize = 12.sp)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            AccountStat("Balance",  "${"%.2f".format(sig.paperBalance)}")
                            AccountStat("ROI",      "${if (sig.paperRoiPct >= 0) "+" else ""}${"%.1f".format(sig.paperRoiPct)}%")
                            AccountStat("Win Rate", "${"%.0f".format(sig.paperWinRate)}%")
                            AccountStat("Trades",   "${sig.paperTrades}")
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                        // signal row
                        val sigColor = when (sig.signal) {
                            "LONG"  -> Color(0xFF00C853)
                            "SHORT" -> Color(0xFFD50000)
                            else    -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "Signal: ${sig.signal}  p=${"%.2f".format(sig.prob)}  @ ${"%.2f".format(sig.close)}",
                                fontSize = 12.sp, color = sigColor, fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (!sig.liveMode && sig.canPromote) {
                            Button(
                                onClick  = { vm.promoteLive() },
                                modifier = Modifier.fillMaxWidth(),
                                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                            ) {
                                Text("🚀 Go Live — ${settings.liveExchange.replaceFirstChar { it.uppercase() }}", fontWeight = FontWeight.Bold)
                            }
                            Text(
                                "Strategy proven: ${sig.paperTrades} trades, ${"%.0f".format(sig.paperWinRate)}% win rate",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (!sig.liveMode) {
                            val needed = maxOf(0, 50 - sig.paperTrades)
                            Text(
                                "Need ≥52% WR over 50 trades to go live. ($needed more trades needed)",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ── Recent paper trades ───────────────────────────────────────────────
        if (paper.trades.isNotEmpty()) {
            item { Text("Paper Trades", fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
            items(paper.trades.takeLast(30).reversed()) { trade -> PaperTradeRow(trade, fmt) }
        }

        // ── Walk-Forward backtest reference ───────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Walk-Forward Backtest (reference)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        BacktestStat("Win Rate",  "75.4%")
                        BacktestStat("Profit F.", "3.06×")
                        BacktestStat("Signals",   "4,952")
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                    Text(
                        "✓ Full 4-model ensemble: Lorentzian 35% · CatBoost 30% · XGBoost 25% · RF 10%",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        lineHeight = 14.sp,
                    )
                }
            }
        }

    }
}

@Composable
private fun AccountStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BacktestStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
    }
}

@Composable
private fun PaperTradeRow(trade: TradeRecord, fmt: SimpleDateFormat) {
    val isBuy  = trade.direction == Signal.LONG
    val pnlClr = if (trade.pnl >= 0) Color(0xFF00C853) else Color(0xFFD50000)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                "${if (isBuy) "▲" else "▼"} ${trade.strategy.ifBlank { if (isBuy) "LONG" else "SHORT" }}",
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            )
            Text(
                "${fmt.format(Date(trade.openedAt))} → ${fmt.format(Date(trade.closedAt))}",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${trade.closeReason.name}  ${"%.4f".format(trade.entryPrice)}→${"%.4f".format(trade.exitPrice)}",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            "${if (trade.pnl >= 0) "+" else ""}${"%.2f".format(trade.pnl)}",
            color = pnlClr, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        )
    }
    HorizontalDivider(thickness = 0.5.dp)
}

