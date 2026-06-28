package com.footprintai.app.data

// ─── Account ─────────────────────────────────────────────────────────────────

data class MtAccountInfo(
    val balance:  Double,
    val equity:   Double,
    val margin:   Double,
    val freeMargin: Double,
    val leverage: Int,
    val currency: String,
    val broker:   String  = "",
    val server:   String  = "",
)

// ─── Positions ────────────────────────────────────────────────────────────────

data class MtPosition(
    val id:           String,
    val symbol:       String,
    val type:         String,   // "POSITION_TYPE_BUY" | "POSITION_TYPE_SELL"
    val volume:       Double,
    val openPrice:    Double,
    val currentPrice: Double,
    val stopLoss:     Double?,
    val takeProfit:   Double?,
    val profit:       Double,
    val comment:      String = "",
    val time:         String = "",
)

// ─── Deals (closed trades) ────────────────────────────────────────────────────

data class MtDeal(
    val id:         String,
    val symbol:     String,
    val type:       String,   // "DEAL_TYPE_BUY" | "DEAL_TYPE_SELL"
    val volume:     Double,
    val price:      Double,
    val profit:     Double,
    val commission: Double,
    val swap:       Double,
    val comment:    String = "",
    val time:       String = "",
)

// ─── Price tick ──────────────────────────────────────────────────────────────

data class MtPrice(
    val symbol: String,
    val bid:    Double,
    val ask:    Double,
    val time:   String = "",
)

// ─── OHLCV bar ───────────────────────────────────────────────────────────────

data class MtBar(
    val time:   Long,    // epoch seconds
    val open:   Double,
    val high:   Double,
    val low:    Double,
    val close:  Double,
    val volume: Double,
)

// ─── Trade request ────────────────────────────────────────────────────────────

enum class MtAction {
    ORDER_TYPE_BUY,
    ORDER_TYPE_SELL,
    POSITION_CLOSE_ID,
    POSITION_MODIFY,
}

data class MtTradeRequest(
    val actionType:  String,
    val symbol:      String?  = null,
    val volume:      Double?  = null,
    val stopLoss:    Double?  = null,
    val takeProfit:  Double?  = null,
    val positionId:  String?  = null,
    val comment:     String   = "FootprintAI",
)

data class MtTradeResult(
    val numericCode: Int    = 0,
    val stringCode:  String = "",
    val message:     String = "",
    val orderId:     String = "",
    val positionId:  String = "",
    val error:       String = "",
)

// ─── Symbol spec ─────────────────────────────────────────────────────────────

data class MtSymbolSpec(
    val symbol:         String,
    val contractSize:   Double,   // e.g. 100000 for EURUSD, 100 for XAUUSD
    val digits:         Int,      // decimal places for price display
    val tickSize:       Double,   // minimum price move
    val tickValue:      Double,   // USD value of one tick per lot
    val marginCurrency: String = "USD",
)
