package com.footprintai.app.inference

import com.footprintai.app.data.MetaApiClient
import com.footprintai.app.data.MtSymbolSpec
import com.footprintai.app.data.MtTradeRequest
import com.footprintai.app.model.Signal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * LiveTradeEngine
 * ───────────────
 * Bridges ML signals (from OnnxInferenceEngine) → MetaApi MT5 execution.
 * One open position at a time per symbol.
 *
 * ponytail: fire-and-forget coroutines; errors surfaced via lastError, not throws.
 */
class LiveTradeEngine(private val client: MetaApiClient) {

    // ── Runtime-adjustable params (synced from AppSettings by ViewModel) ────
    var enableLong:    Boolean = true
    var riskPct:       Double  = 0.02
    var slAtrMult:     Float   = 1.5f
    var tradingSymbol: String  = "ETHUSD"

    var lastError: String? = null
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ponytail: single cached spec — refresh only on symbol change
    private var cachedSpec: MtSymbolSpec? = null
    private var cachedSpecSymbol: String  = ""

    // ── Signal handler (called from ViewModel on closed kline) ───────────────

    fun onSignal(signal: Signal, closePrice: Double, atrPct: Float = 0.02f) {
        if (signal == Signal.NEUTRAL) return
        if (signal == Signal.LONG && !enableLong) return
        scope.launch { execute(signal, closePrice, atrPct) }
    }

    private suspend fun execute(signal: Signal, price: Double, atrPct: Float) {
        try {
            lastError = null
            val positions = client.getPositions()
            val existing  = positions.firstOrNull { it.symbol == tradingSymbol }

            // Close opposite position
            if (existing != null) {
                val isOpposite =
                    (signal == Signal.LONG  && existing.type.contains("SELL")) ||
                    (signal == Signal.SHORT && existing.type.contains("BUY"))
                if (isOpposite) {
                    client.placeTrade(MtTradeRequest(
                        actionType = "POSITION_CLOSE_ID",
                        positionId = existing.id,
                    ))
                } else {
                    return  // same direction already open
                }
            }

            val acct = client.getAccountInfo()
            val spec = symbolSpec()
            val slPct = (slAtrMult * atrPct).toDouble().coerceIn(0.005, 0.05)
            val lot   = calcLot(acct.balance, price, slPct, spec)

            val sl = if (signal == Signal.LONG) price * (1 - slPct) else price * (1 + slPct)
            val tp = if (signal == Signal.LONG) price * (1 + slPct * 2) else price * (1 - slPct * 2)

            val result = client.placeTrade(MtTradeRequest(
                actionType = if (signal == Signal.LONG) "ORDER_TYPE_BUY" else "ORDER_TYPE_SELL",
                symbol     = tradingSymbol,
                volume     = lot,
                stopLoss   = sl,
                takeProfit = tp,
            ))
            if (result.error.isNotEmpty()) lastError = result.error
        } catch (e: Exception) {
            lastError = e.message
        }
    }

    // ── Lot-size calculation ─────────────────────────────────────────────────

    private fun calcLot(balance: Double, price: Double, slPct: Double, spec: MtSymbolSpec?): Double {
        val riskUsd      = balance * riskPct
        val slDistPrice  = price * slPct
        val cs           = spec?.contractSize ?: 1.0
        val tickSz       = spec?.tickSize     ?: 0.001
        val tickVal      = spec?.tickValue    ?: 1.0
        val slTicks      = slDistPrice / tickSz
        val riskPerLot   = slTicks * tickVal   // USD loss at SL for 1 standard lot
        return if (riskPerLot > 0) (riskUsd / riskPerLot).coerceIn(0.01, 10.0) else 0.01
    }

    private suspend fun symbolSpec(): MtSymbolSpec? {
        if (tradingSymbol != cachedSpecSymbol) {
            cachedSpec       = runCatching { client.getSymbolSpec(tradingSymbol) }.getOrNull()
            cachedSpecSymbol = tradingSymbol
        }
        return cachedSpec
    }
}
