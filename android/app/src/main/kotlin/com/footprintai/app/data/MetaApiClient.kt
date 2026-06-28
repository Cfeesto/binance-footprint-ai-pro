package com.footprintai.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * MetaApiClient
 * ─────────────
 * REST client for MetaApi cloud MT5 gateway.
 * Base: https://mt-client-api-v1.{region}.agiliumtrade.ai
 * Auth: auth-token header.
 *
 * ponytail: no retry / interceptor — errors bubble to ViewModel; simple is fine for now.
 */
class MetaApiClient(
    val token: String,
    val accountId: String,
    val region: String = "new-york",
) {
    private val base = "https://mt-client-api-v1.$region.agiliumtrade.ai" +
                       "/users/current/accounts/$accountId"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private fun get(path: String): String {
        val req = Request.Builder()
            .url("$base$path")
            .header("auth-token", token)
            .build()
        return http.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: "{}"
            if (!r.isSuccessful) throw RuntimeException("MetaApi GET $path → ${r.code}: $body")
            body
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("$base$path")
            .header("auth-token", token)
            .post(body.toString().toRequestBody(JSON))
            .build()
        return http.newCall(req).execute().use { r ->
            val bodyStr = r.body?.string() ?: "{}"
            if (r.code !in 200..299) throw RuntimeException("MetaApi POST $path → ${r.code}: $bodyStr")
            JSONObject(bodyStr)
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    suspend fun getAccountInfo(): MtAccountInfo = withContext(Dispatchers.IO) {
        val j = JSONObject(get("/accountInformation"))
        MtAccountInfo(
            balance     = j.getDouble("balance"),
            equity      = j.getDouble("equity"),
            margin      = j.optDouble("margin", 0.0),
            freeMargin  = j.optDouble("freeMargin", 0.0),
            leverage    = j.optInt("leverage", 100),
            currency    = j.optString("currency", "USD"),
            broker      = j.optString("broker", ""),
            server      = j.optString("server", ""),
        )
    }

    suspend fun getPositions(): List<MtPosition> = withContext(Dispatchers.IO) {
        val arr = JSONArray(get("/positions"))
        (0 until arr.length()).map { i ->
            val j = arr.getJSONObject(i)
            MtPosition(
                id           = j.optString("id", ""),
                symbol       = j.optString("symbol", ""),
                type         = j.optString("type", ""),
                volume       = j.optDouble("volume", 0.0),
                openPrice    = j.optDouble("openPrice", 0.0),
                currentPrice = j.optDouble("currentPrice", j.optDouble("openPrice", 0.0)),
                stopLoss     = if (j.has("stopLoss")) j.getDouble("stopLoss") else null,
                takeProfit   = if (j.has("takeProfit")) j.getDouble("takeProfit") else null,
                profit       = j.optDouble("profit", 0.0),
                comment      = j.optString("comment", ""),
                time         = j.optString("time", ""),
            )
        }
    }

    suspend fun getCurrentPrice(symbol: String): MtPrice = withContext(Dispatchers.IO) {
        val j = JSONObject(get("/symbols/$symbol/currentPrice"))
        MtPrice(
            symbol = symbol,
            bid    = j.getDouble("bid"),
            ask    = j.getDouble("ask"),
            time   = j.optString("time", ""),
        )
    }

    suspend fun getSymbolSpec(symbol: String): MtSymbolSpec = withContext(Dispatchers.IO) {
        val j = JSONObject(get("/symbols/$symbol"))
        MtSymbolSpec(
            symbol         = symbol,
            contractSize   = j.optDouble("contractSize", 100000.0),
            digits         = j.optInt("digits", 5),
            tickSize       = j.optDouble("tickSize", 0.00001),
            tickValue      = j.optDouble("tickValue", 1.0),
            marginCurrency = j.optString("marginCurrency", "USD"),
        )
    }

    suspend fun placeTrade(req: MtTradeRequest): MtTradeResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("actionType", req.actionType)
            req.symbol?.let     { put("symbol", it) }
            req.volume?.let     { put("volume", it) }
            req.stopLoss?.let   { put("stopLoss", it) }
            req.takeProfit?.let { put("takeProfit", it) }
            req.positionId?.let { put("positionId", it) }
            put("comment", req.comment)
        }
        val j = post("/trade", body)
        MtTradeResult(
            numericCode = j.optInt("numericCode", 0),
            stringCode  = j.optString("stringCode", ""),
            message     = j.optString("message", ""),
            orderId     = j.optString("orderId", ""),
            positionId  = j.optString("positionId", ""),
            error       = j.optString("error", ""),
        )
    }

    /** Recent closed deals (last 7 days). */
    suspend fun getDeals(): List<MtDeal> = withContext(Dispatchers.IO) {
        val endMs   = System.currentTimeMillis()
        val startMs = endMs - 7L * 86_400_000L
        val path    = "/history-deals/time/${startMs / 1000}/${endMs / 1000}"
        val arr = runCatching { JSONArray(get(path)) }.getOrNull() ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val j = arr.getJSONObject(i)
            // Skip balance/credit ops, only BUY/SELL
            val type = j.optString("type", "")
            if (!type.contains("BUY") && !type.contains("SELL")) return@mapNotNull null
            MtDeal(
                id         = j.optString("id", ""),
                symbol     = j.optString("symbol", ""),
                type       = type,
                volume     = j.optDouble("volume", 0.0),
                price      = j.optDouble("price", 0.0),
                profit     = j.optDouble("profit", 0.0),
                commission = j.optDouble("commission", 0.0),
                swap       = j.optDouble("swap", 0.0),
                comment    = j.optString("comment", ""),
                time       = j.optString("time", ""),
            )
        }
    }
}
