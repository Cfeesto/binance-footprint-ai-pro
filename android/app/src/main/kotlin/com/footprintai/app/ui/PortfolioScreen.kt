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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.footprintai.app.model.*
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottomAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStartAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun PortfolioScreen(vm: ChartViewModel = viewModel()) {
    val state   by vm.state.collectAsStateWithLifecycle()
    val account  = state.paperAccount
    val pnl      = account.balance - account.startBalance
    val pnlPct   = (pnl / account.startBalance) * 100
    val winRate  = if (account.totalTrades > 0) account.winTrades.toFloat() / account.totalTrades * 100 else 0f
    val daysLeft = 30 - ((System.currentTimeMillis() - account.startedAt) / 86_400_000L).coerceAtLeast(0)

    // 权益曲线 producer
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(account.dailySnapshots) {
        val snaps = account.dailySnapshots
        if (snaps.size >= 2) {
            producer.runTransaction {
                lineSeries { series(snaps.map { it.balance.toFloat() }) }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Walk-Forward 回测参考卡 ───────────────────────────────────────────
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
                    // Live vs. expected WR 对比
                    if (account.totalTrades >= 10) {
                        val wrDiff = winRate - 75.4f
                        val diffColor = if (wrDiff >= 0) Color(0xFF00C853) else Color(0xFFD50000)
                        Text(
                            "Live WR ${"%.0f".format(winRate)}%  ${if (wrDiff >= 0) "+" else ""}${"%.1f".format(wrDiff)}% vs. backtest",
                            color    = diffColor,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                    // Lorentzian 披露
                    HorizontalDivider(thickness = 0.5.dp)
                    Text(
                        "⚠ Lorentzian KNN (35% backtest weight) not deployed on Android — " +
                        "app runs CatBoost 45% · XGBoost 35% · RF 20%",
                        fontSize = 10.sp,
                        color    = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        lineHeight = 14.sp,
                    )
                }
            }
        }

        // ── 余额卡片 ──────────────────────────────────────────────────────────
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Paper Account", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "${daysLeft}d left",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                        Text(
                            "$${String.format("%.2f", account.balance)}",
                            fontWeight = FontWeight.Bold,
                            fontSize   = 28.sp,
                        )
                        Text(
                            "${if (pnl >= 0) "+" else ""}$${String.format("%.2f", pnl)}  (${String.format("%.1f", pnlPct)}%)",
                            color      = if (pnl >= 0) Color(0xFF00C853) else Color(0xFFD50000),
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 14.sp,
                        )
                    }
                    // 进度条（30天）
                    val progress = ((30 - daysLeft) / 30f).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress    = { progress },
                        modifier    = Modifier.fillMaxWidth().height(4.dp),
                        color       = MaterialTheme.colorScheme.primary,
                        trackColor  = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text("${"%.0f".format(progress * 100)}% of 30-day challenge", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ── 统计行 ────────────────────────────────────────────────────────────
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip("Trades",   "${account.totalTrades}", Modifier.weight(1f))
                StatChip("Win Rate", "${String.format("%.0f", winRate)}%", Modifier.weight(1f))
                StatChip("Open Pos", if (account.openPosition != null) account.openPosition.direction.name else "—", Modifier.weight(1f))
            }
        }

        // ── 当前持仓 ──────────────────────────────────────────────────────────
        account.openPosition?.let { pos ->
            item { PositionCard(pos) }
        }

        // ── 权益曲线 ──────────────────────────────────────────────────────────
        if (account.dailySnapshots.size >= 2) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Equity Curve", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                        CartesianChartHost(
                            chart = rememberCartesianChart(
                                rememberLineCartesianLayer(),
                                startAxis  = rememberStartAxis(),
                                bottomAxis = rememberBottomAxis(),
                            ),
                            modelProducer = producer,
                            modifier      = Modifier.fillMaxWidth().height(160.dp),
                        )
                    }
                }
            }
        }

        // ── 交易记录 ──────────────────────────────────────────────────────────
        if (account.trades.isNotEmpty()) {
            item {
                Text("Trade History", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            items(account.trades.reversed()) { trade ->
                TradeRow(trade)
            }
        }

        // ── 重置按钮 ──────────────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick  = { vm.resetPaperAccount() },
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Reset Paper Account  ($200 start)")
            }
        }
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
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            Modifier.padding(10.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PositionCard(pos: OpenPosition) {
    val color = if (pos.direction == Signal.LONG) Color(0xFF00C853) else Color(0xFFD50000)
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Column {
                Text("Open Position", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(pos.direction.name, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Entry  $${String.format("%.2f", pos.entryPrice)}", fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("SL  $${String.format("%.2f", pos.stopLoss)}", color = Color(0xFFD50000), fontSize = 12.sp)
                Text("TP  $${String.format("%.2f", pos.takeProfit)}", color = Color(0xFF00C853), fontSize = 12.sp)
                Text("${String.format("%.4f", pos.quantity)} ETH", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TradeRow(trade: TradeRecord) {
    val pnlColor = if (trade.pnl >= 0) Color(0xFF00C853) else Color(0xFFD50000)
    val dateFmt  = remember { SimpleDateFormat("MM/dd HH:mm", Locale.US) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                "${trade.direction.name}  ${trade.closeReason.name.replace('_', ' ')}",
                fontWeight = FontWeight.SemiBold,
                fontSize   = 13.sp,
            )
            Text(
                "${dateFmt.format(Date(trade.openedAt))} → ${dateFmt.format(Date(trade.closedAt))}",
                fontSize = 11.sp,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${if (trade.pnl >= 0) "+" else ""}$${String.format("%.2f", trade.pnl)}",
            color      = pnlColor,
            fontWeight = FontWeight.Bold,
            fontSize   = 14.sp,
        )
    }
    HorizontalDivider(thickness = 0.5.dp)
}
