package com.footprintai.app.data

import android.content.Context
import com.footprintai.app.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * PaperTradeStore
 * ───────────────
 * 将 PaperAccount 序列化为单个 JSON 文件存储在 filesDir。
 * 无 Room，无第三方序列化库。
 *
 * ponytail: 手写 JSON 映射，数据结构稳定，不值得引入 Gson/Moshi。
 */
class PaperTradeStore(ctx: Context) {

    private val file = File(ctx.filesDir, "paper_account.json")

    fun load(): PaperAccount {
        if (!file.exists()) return PaperAccount()
        return try {
            val j = JSONObject(file.readText())
            PaperAccount(
                startBalance   = j.getDouble("startBalance"),
                balance        = j.getDouble("balance"),
                openPosition   = j.optJSONObject("openPosition")?.toPosition(),
                trades         = j.optJSONArray("trades")?.toTradeList() ?: emptyList(),
                dailySnapshots = j.optJSONArray("dailySnapshots")?.toSnapshotList() ?: emptyList(),
                startedAt      = j.optLong("startedAt", System.currentTimeMillis()),
                totalTrades    = j.optInt("totalTrades", 0),
                winTrades      = j.optInt("winTrades", 0),
            )
        } catch (_: Exception) {
            PaperAccount()
        }
    }

    fun save(account: PaperAccount) {
        val j = JSONObject().apply {
            put("startBalance",   account.startBalance)
            put("balance",        account.balance)
            account.openPosition?.let { put("openPosition", it.toJson()) }
            put("trades",         JSONArray().also { arr ->
                account.trades.forEach { arr.put(it.toJson()) }
            })
            put("dailySnapshots", JSONArray().also { arr ->
                account.dailySnapshots.forEach { arr.put(it.toJson()) }
            })
            put("startedAt",   account.startedAt)
            put("totalTrades", account.totalTrades)
            put("winTrades",   account.winTrades)
        }
        file.writeText(j.toString())
    }

    fun reset() {
        file.delete()
    }

    // ── 序列化助手 ────────────────────────────────────────────────────────────

    private fun OpenPosition.toJson() = JSONObject().apply {
        put("direction",  direction.name)
        put("entryPrice", entryPrice)
        put("quantity",   quantity)
        put("openedAt",   openedAt)
        put("stopLoss",   stopLoss)
        put("takeProfit", takeProfit)
    }

    private fun JSONObject.toPosition() = OpenPosition(
        direction  = Signal.valueOf(getString("direction")),
        entryPrice = getDouble("entryPrice"),
        quantity   = getDouble("quantity"),
        openedAt   = getLong("openedAt"),
        stopLoss   = getDouble("stopLoss"),
        takeProfit = getDouble("takeProfit"),
    )

    private fun TradeRecord.toJson() = JSONObject().apply {
        put("id",          id)
        put("direction",   direction.name)
        put("entryPrice",  entryPrice)
        put("exitPrice",   exitPrice)
        put("quantity",    quantity)
        put("pnl",         pnl)
        put("openedAt",    openedAt)
        put("closedAt",    closedAt)
        put("closeReason", closeReason.name)
    }

    private fun JSONObject.toTrade() = TradeRecord(
        id          = getLong("id"),
        direction   = Signal.valueOf(getString("direction")),
        entryPrice  = getDouble("entryPrice"),
        exitPrice   = getDouble("exitPrice"),
        quantity    = getDouble("quantity"),
        pnl         = getDouble("pnl"),
        openedAt    = getLong("openedAt"),
        closedAt    = getLong("closedAt"),
        closeReason = CloseReason.valueOf(getString("closeReason")),
    )

    private fun DailySnapshot.toJson() = JSONObject().apply {
        put("date",    date)
        put("balance", balance)
    }

    private fun JSONObject.toSnapshot() = DailySnapshot(
        date    = getString("date"),
        balance = getDouble("balance"),
    )

    private fun JSONArray.toTradeList()    = (0 until length()).map { getJSONObject(it).toTrade() }
    private fun JSONArray.toSnapshotList() = (0 until length()).map { getJSONObject(it).toSnapshot() }
}
