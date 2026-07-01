package com.footprintai.app.data

import android.content.Context

/**
 * AppSettings — SharedPreferences 包装器
 * 运行时可调整推理阈值、风险参数、LONG 开关
 */
class AppSettings(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var shortThresh: Float
        get() = prefs.getFloat("shortThresh", 0.46f)
        set(v) { prefs.edit().putFloat("shortThresh", v).apply() }

    var longThresh: Float
        get() = prefs.getFloat("longThresh", 0.54f)
        set(v) { prefs.edit().putFloat("longThresh", v).apply() }

    var riskPct: Float
        get() = prefs.getFloat("riskPct", 0.02f)
        set(v) { prefs.edit().putFloat("riskPct", v).apply() }

    var slAtrMult: Float
        get() = prefs.getFloat("slAtrMult", 1.5f)
        set(v) { prefs.edit().putFloat("slAtrMult", v).apply() }

    var enableLong: Boolean
        get() = prefs.getBoolean("enableLong", true)
        set(v) { prefs.edit().putBoolean("enableLong", v).apply() }

    var maxDrawdownPct: Float
        get() = prefs.getFloat("maxDrawdownPct", 0.30f)
        set(v) { prefs.edit().putFloat("maxDrawdownPct", v).apply() }

    // ── TradingView webhook ────────────────────────────────────────────────────
    /** User-chosen key for the TV webhook — matches ?key= in the URL */
    var tvWebhookKey: String
        get() = prefs.getString("tvWebhookKey", "") ?: ""
        set(v) { prefs.edit().putString("tvWebhookKey", v).apply() }

    // ── VPS Signal Server ─────────────────────────────────────────────────────
    /** WebSocket URL, e.g. ws://1.2.3.4:8001/ws */
    var vpsWsUrl: String
        get() = prefs.getString("vpsWsUrl", "") ?: ""
        set(v) { prefs.edit().putString("vpsWsUrl", v).apply() }

    /** REST base URL, e.g. http://1.2.3.4:8001 */
    var vpsApiUrl: String
        get() = prefs.getString("vpsApiUrl", "") ?: ""
        set(v) { prefs.edit().putString("vpsApiUrl", v).apply() }

    // ── Exchange for live trading ─────────────────────────────────────────────
    /** binance | hyperliquid | bybit | okx */
    var liveExchange: String
        get() = prefs.getString("liveExchange", "binance") ?: "binance"
        set(v) { prefs.edit().putString("liveExchange", v).apply() }

    // Binance Futures
    var binanceApiKey: String
        get() = prefs.getString("binanceApiKey", "") ?: ""
        set(v) { prefs.edit().putString("binanceApiKey", v).apply() }
    var binanceApiSecret: String
        get() = prefs.getString("binanceApiSecret", "") ?: ""
        set(v) { prefs.edit().putString("binanceApiSecret", v).apply() }

    // Hyperliquid
    var hlPrivateKey: String
        get() = prefs.getString("hlPrivateKey", "") ?: ""
        set(v) { prefs.edit().putString("hlPrivateKey", v).apply() }

    // Bybit V5
    var bybitApiKey: String
        get() = prefs.getString("bybitApiKey", "") ?: ""
        set(v) { prefs.edit().putString("bybitApiKey", v).apply() }
    var bybitApiSecret: String
        get() = prefs.getString("bybitApiSecret", "") ?: ""
        set(v) { prefs.edit().putString("bybitApiSecret", v).apply() }

    // OKX
    var okxApiKey: String
        get() = prefs.getString("okxApiKey", "") ?: ""
        set(v) { prefs.edit().putString("okxApiKey", v).apply() }
    var okxApiSecret: String
        get() = prefs.getString("okxApiSecret", "") ?: ""
        set(v) { prefs.edit().putString("okxApiSecret", v).apply() }
    var okxPassphrase: String
        get() = prefs.getString("okxPassphrase", "") ?: ""
        set(v) { prefs.edit().putString("okxPassphrase", v).apply() }
}
