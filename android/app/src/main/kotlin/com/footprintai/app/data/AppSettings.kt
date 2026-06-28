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

    // ── MetaApi credentials ───────────────────────────────────────────────────
    var metaApiToken: String
        get() = prefs.getString("metaApiToken", "") ?: ""
        set(v) { prefs.edit().putString("metaApiToken", v).apply() }

    var metaApiAccountId: String
        get() = prefs.getString("metaApiAccountId", "") ?: ""
        set(v) { prefs.edit().putString("metaApiAccountId", v).apply() }

    var metaApiRegion: String
        get() = prefs.getString("metaApiRegion", "new-york") ?: "new-york"
        set(v) { prefs.edit().putString("metaApiRegion", v).apply() }

    /** MT5 symbol to execute on (e.g. ETHUSD, XAUUSD, EURUSD) */
    var tradingSymbol: String
        get() = prefs.getString("tradingSymbol", "ETHUSD") ?: "ETHUSD"
        set(v) { prefs.edit().putString("tradingSymbol", v).apply() }

    // ── TradingView webhook ────────────────────────────────────────────────────
    /** User-chosen key for the TV webhook — matches ?key= in the URL */
    var tvWebhookKey: String
        get() = prefs.getString("tvWebhookKey", "") ?: ""
        set(v) { prefs.edit().putString("tvWebhookKey", v).apply() }
}
