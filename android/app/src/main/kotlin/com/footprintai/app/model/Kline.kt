package com.footprintai.app.model

/** 单根 K 线，字段与 Binance WS kline stream 对齐 */
data class Kline(
    val openTime:    Long,
    val open:        Double,
    val high:        Double,
    val low:         Double,
    val close:       Double,
    val volume:      Double,
    val buyVolume:   Double,   // taker buy base asset volume
    val isClosed:    Boolean,
)

/** 模型推断结果 */
enum class Signal { LONG, SHORT, NEUTRAL }

data class InferenceResult(
    val signal:   Signal,
    val prob:     Float,   // ensemble 概率 [0,1]
    val probLor:  Float,   // Lorentzian KNN 单模型概率
    val probCat:  Float,   // CatBoost 单模型概率
    val probXgb:  Float,   // XGBoost 单模型概率
    val probRf:   Float,   // RandomForest 单模型概率
    val kline:    Kline,
)
