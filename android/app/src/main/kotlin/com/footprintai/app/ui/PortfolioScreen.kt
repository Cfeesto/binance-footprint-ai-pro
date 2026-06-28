package com.footprintai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.footprintai.app.data.MtDeal
import com.footprintai.app.data.MtPosition
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun PortfolioScreen(vm: ChartViewModel = viewModel()) {
    val state   by vm.state.collectAsStateWithLifecycle()
    val acct     = state.mtAccount
    val settings = state.appSettings

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Connection status ─────────────────────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors   = CardDefaults.cardColors(
                    containerColor = if (state.mtConnected)
                        MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                ),
            ) {
                Row(
                    Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            if (state.mtConnected) "MT5 Connected" else "MT5 Not Connected",
                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        )
                        if (!state.mtConnected) {
                            Text("Add MetaApi token & account ID in Settings",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                        } else {
                            Text(
                                "${settings.tradingSymbol}  ·  ${settings.metaApiRegion}",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    if (state.mtConnected) {
                        TextButton(onClick = { vm.refreshMt() }) {
                            Text("Refresh", fontSize = 12.sp)
                        }
                    }
                }
                state.mtError?.let { err ->
                    Text(
                        err, fontSize = 10.sp, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp),
                    )
                }
            }
        }

        // ── Account info ──────────────────────────────────────────────────────
        if (acct != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Live Account", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(acct.currency, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                        }
                        Row(
                            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Text(
                                "${"%.2f".format(acct.balance)} ${acct.currency}",
                                fontWeight = FontWeight.Bold, fontSize = 26.sp,
                            )
                            val pnl = acct.equity - acct.balance
                            Text(
                                "${if (pnl >= 0) "+" else ""}${"%.2f".format(pnl)} unrealized",
                                color = if (pnl >= 0) Color(0xFF00C853) else Color(0xFFD50000),
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            AccountStat("Equity",      "${"%.2f".format(acct.equity)} ${acct.currency}")
                            AccountStat("Free Margin", "${"%.2f".format(acct.freeMargin)} ${acct.currency}")
                            AccountStat("Leverage",    "1:${acct.leverage}")
                        }
                        if (acct.broker.isNotEmpty()) {
                            Text("${acct.broker}  ·  ${acct.server}", fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // ── Open positions ────────────────────────────────────────────────────
        if (state.mtPositions.isNotEmpty()) {
            item { Text("Open Positions", fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
            items(state.mtPositions) { pos -> MtPositionCard(pos) }
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

        // ── Recent deals ──────────────────────────────────────────────────────
        if (state.mtDeals.isNotEmpty()) {
            item { Text("Recent Deals (7d)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
            items(state.mtDeals.takeLast(30).reversed()) { deal -> DealRow(deal) }
        } else if (state.mtConnected && acct != null) {
            item {
                Text("No deals in the past 7 days",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun MtPositionCard(pos: MtPosition) {
    val isBuy  = pos.type.contains("BUY")
    val color  = if (isBuy) Color(0xFF00C853) else Color(0xFFD50000)
    val pnlClr = if (pos.profit >= 0) Color(0xFF00C853) else Color(0xFFD50000)
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Column {
                Text(if (isBuy) "▲ LONG" else "▼ SHORT", color = color,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(pos.symbol, fontSize = 12.sp)
                Text("Entry  ${"%.5f".format(pos.openPrice)}", fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${if (pos.profit >= 0) "+" else ""}${"%.2f".format(pos.profit)}",
                    color = pnlClr, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                pos.stopLoss?.let {
                    Text("SL  ${"%.5f".format(it)}", color = Color(0xFFD50000), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                }
                pos.takeProfit?.let {
                    Text("TP  ${"%.5f".format(it)}", color = Color(0xFF00C853), fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                }
                Text("${pos.volume} lot", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DealRow(deal: MtDeal) {
    val isBuy  = deal.type.contains("BUY")
    val pnlClr = if (deal.profit >= 0) Color(0xFF00C853) else Color(0xFFD50000)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                "${if (isBuy) "▲" else "▼"} ${deal.symbol}",
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            )
            Text(
                "Price: ${"%.5f".format(deal.price)}  Vol: ${deal.volume}",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${if (deal.profit >= 0) "+" else ""}${"%.2f".format(deal.profit)}",
                color = pnlClr, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            )
            if (deal.commission != 0.0 || deal.swap != 0.0) {
                Text(
                    "fee ${"%.2f".format(deal.commission + deal.swap)}",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    HorizontalDivider(thickness = 0.5.dp)
}
