package com.footprintai.app.inference

import com.footprintai.app.model.Kline
import kotlin.math.*

/**
 * FeatureBuilder
 * ─────────────
 * 将滑动窗口的 K 线列表转换为模型输入向量（Float32）。
 * 字段顺序必须与 Python 端 FEATURE_COLS 完全一致。
 *
 * 最少需要 20 根 K 线（用于 rolling-20 指标）。
 */
object FeatureBuilder {

    const val MIN_BARS = 20

    /**
     * @param bars  最新 N 根 K 线，最后一根 = 当前 bar
     * @return FloatArray(39)，或 null（数据不足）
     */
    fun build(bars: List<Kline>): FloatArray? {
        if (bars.size < MIN_BARS) return null

        val cur = bars.last()

        // ── 成交量基础 ──────────────────────────────────────────────────────
        val vol       = cur.volume.takeIf { it > 0.0 } ?: return null
        val sellVol   = vol - cur.buyVolume
        val delta     = cur.buyVolume - sellVol

        val buyRatio   = (cur.buyVolume / vol).toFloat().coerceIn(0f, 1f)
        val sellRatio  = (sellVol / vol).toFloat().coerceIn(0f, 1f)
        val deltaRatio = (delta / vol).toFloat().coerceIn(-1f, 1f)

        // ── CVD（累积成交量差值）──────────────────────────────────────────────
        val deltas = bars.map { it.buyVolume - (it.volume - it.buyVolume) }
        val vols20 = bars.takeLast(20).map { it.volume }
        val cvd20  = deltas.takeLast(20).sum()
        val rollVol20 = vols20.sum().takeIf { it > 0.0 } ?: 1.0
        val cvdRatio20 = (cvd20 / rollVol20).toFloat().coerceIn(-1f, 1f)

        // ── 成交量 spike ────────────────────────────────────────────────────
        val volMa20    = vols20.average()
        val volumeSpike = (vol / volMa20.takeIf { it > 0 }!!).toFloat()

        val pocPosition = buyRatio

        // ── Delta 背离 ──────────────────────────────────────────────────────
        val priceUp      = cur.close > cur.open
        val priceDn      = cur.close < cur.open
        val deltaDivBear = if (priceUp && delta < 0) 1f else 0f
        val deltaDivBull = if (priceDn && delta > 0) 1f else 0f

        // ── K 线结构 ─────────────────────────────────────────────────────────
        val range     = (cur.high - cur.low).takeIf { it > 0 } ?: 1e-8
        val candleRangePct = (range / cur.close).toFloat()
        val bodyRatio      = ((cur.close - cur.open).absoluteValue / range).toFloat()
        val upperWick      = ((cur.high - maxOf(cur.open, cur.close)) / range).toFloat()
        val lowerWick      = ((minOf(cur.open, cur.close) - cur.low) / range).toFloat()

        // ── Stacked imbalance（连续同向 delta）────────────────────────────────
        val signs = bars.takeLast(3).map { sign(it.buyVolume - (it.volume - it.buyVolume)) }
        val stackedBull = if (signs.all { it > 0 }) 1f else 0f
        val stackedBear = if (signs.all { it < 0 }) 1f else 0f

        // 买压动量：匹配 Python (buy_roll5 - sell_roll5) / (buy_roll5 + sell_roll5)
        val buyRoll5  = bars.takeLast(5).map { it.buyVolume }.average()
        val sellRoll5 = bars.takeLast(5).map { it.volume - it.buyVolume }.average()
        val denom5    = buyRoll5 + sellRoll5
        val buyMomentum = if (denom5 > 0) ((buyRoll5 - sellRoll5) / denom5).toFloat() else 0f

        // ── RSI-14（Wilder's EWM，匹配 Python ewm(com=period-1)）────────────
        val rsi14 = rsi(bars, 14)
        val rsi14Norm = ((rsi14 - 50f) / 50f).coerceIn(-1f, 1f)

        // ── CCI-20（使用标准差，匹配 Python rolling.std() ddof=1）────────────
        val cci20 = cci(bars, 20)
        val cci20Norm = (cci20 / 200f).coerceIn(-1f, 1f)

        // ── ADX-14（EWM，匹配 Python ewm(span=period)）──────────────────────
        val (adx14, plusDi, minusDi) = adx(bars, 14)
        val adx14Norm = (adx14 / 100f).coerceIn(0f, 1f)

        // ── WaveTrend (WT1)（滚动 EMA，匹配 Python）─────────────────────────
        val (wt1, wt2) = waveTrend(bars)
        val wt1Norm = (wt1 / 100f).coerceIn(-1f, 1f)
        val wtDiff  = wt1 - wt2   // raw diff, matches Python FEATURE_COLS "wt_diff" (not normalised)

        // ── MACD histogram（正确 EWM 信号线，匹配 Python）────────────────────
        val macdHist = macdHistogram(bars)
        val macdHistNorm = (macdHist / cur.close).toFloat().coerceIn(-1f, 1f)

        // ── ATR-14（EWM span=14，匹配 Python ewm(span=period)）──────────────
        val atr14Pct = (atr(bars, 14) / cur.close).toFloat()

        // ── Bollinger Bands（rolling std ddof=1，匹配 pandas）────────────────
        val closes20  = bars.takeLast(20).map { it.close }
        val ma20      = closes20.average()
        val std20     = closes20.stdSample()
        val bbUpper   = ma20 + 2 * std20
        val bbLower   = ma20 - 2 * std20
        val bbWidth   = bbUpper - bbLower
        val bbPos     = if (bbWidth > 0) ((cur.close - bbLower) / bbWidth).toFloat() else 0.5f
        val bbWidthPct = (bbWidth / ma20).toFloat()

        // ── 清算代理（匹配 Python 注释：下影线=空头清算，上影线=多头清算）───────
        val liqShortProxy = lowerWick * volumeSpike   // lower wick × spike
        val liqLongProxy  = upperWick * volumeSpike   // upper wick × spike
        val liqNet        = liqShortProxy - liqLongProxy
        val volSpikeDelta = volumeSpike * deltaRatio

        // ── CVD 加速度 ───────────────────────────────────────────────────────
        val cvd5Prev  = deltas.takeLast(10).take(5).sum()
        val cvd5Cur   = deltas.takeLast(5).sum()
        val cvdAccel  = (cvd5Cur - cvd5Prev).toFloat()

        // ── 价格动量（fraction，匹配 Python pct_change，无 ×100）────────────
        val closes = bars.map { it.close }
        val priceMom5  = if (closes.size >= 6)  ((closes.last() / closes[closes.size - 6]  - 1)).toFloat() else 0f
        val priceMom20 = if (closes.size >= 21) ((closes.last() / closes[closes.size - 21] - 1)).toFloat() else 0f

        // ── VWAP 偏差（fraction，无 ×100）────────────────────────────────────
        val recent = bars.takeLast(20)
        val vwapNum = recent.sumOf { ((it.high + it.low + it.close) / 3) * it.volume }
        val vwapDen = recent.sumOf { it.volume }.coerceAtLeast(1e-8)
        val vwap    = vwapNum / vwapDen
        val vwapDev = ((cur.close - vwap) / vwap).toFloat()

        // ── EMA-20 斜率（shift 5 bars，除以 close，无 ×100）──────────────────
        val ema20     = emaClose(bars, 20)
        val ema20Prev = emaClose(bars.dropLast(5), 20)
        val ema20Slope = ((ema20 - ema20Prev) / cur.close).toFloat()

        // ── 区间位置（20 bar high/low，fraction，无 ×100）────────────────────
        val highs20 = bars.takeLast(20).map { it.high }
        val lows20  = bars.takeLast(20).map { it.low }
        val high20  = highs20.max()
        val low20   = lows20.min()
        val rangePos20     = if (high20 > low20) ((cur.close - low20) / (high20 - low20)).toFloat().coerceIn(0f, 1f) else 0.5f
        val distFromHigh20 = ((high20 - cur.close) / cur.close).toFloat()
        val distFromLow20  = ((cur.close - low20)  / cur.close).toFloat()

        // ── 买压一致性（std of buy_ratio over 10 bars，ddof=1）───────────────
        val buyRatios10 = bars.takeLast(10).map { (it.buyVolume / it.volume.coerceAtLeast(1e-8)).toDouble() }
        val buyRatioStd10 = buyRatios10.stdSample().toFloat()

        // 返回顺序必须与 Python FEATURE_COLS 完全一致（39 个特征）
        return floatArrayOf(
            // Footprint
            buyRatio, sellRatio, deltaRatio,
            cvdRatio20, volumeSpike, pocPosition,
            deltaDivBear, deltaDivBull,
            candleRangePct, bodyRatio, upperWick, lowerWick,
            stackedBull, stackedBear, buyMomentum,
            // Technical
            rsi14Norm, cci20Norm, adx14Norm, plusDi, minusDi,
            wt1Norm, wtDiff,
            macdHistNorm, atr14Pct,
            bbPos, bbWidthPct,
            // 清算
            liqShortProxy, liqLongProxy, liqNet,
            volSpikeDelta, cvdAccel,
            priceMom5, priceMom20,
            // VWAP + 趋势 + 区间
            vwapDev, ema20Slope, rangePos20,
            distFromHigh20, distFromLow20,
            // 买压一致性
            buyRatioStd10,
        )
    }

    // ── 指标实现 ───────────────────────────────────────────────────────────────

    /** Wilder's EWM RSI — alpha = 1/period，匹配 Python ewm(com=period-1) */
    private fun rsi(bars: List<Kline>, period: Int): Float {
        if (bars.size < period + 1) return 50f
        val alpha = 1.0 / period
        var avgGain = 0.0; var avgLoss = 0.0
        for (i in 1 until bars.size) {
            val d = bars[i].close - bars[i - 1].close
            val g = if (d > 0) d else 0.0
            val l = if (d < 0) -d else 0.0
            if (i == 1) { avgGain = g; avgLoss = l }
            else {
                avgGain = g * alpha + avgGain * (1 - alpha)
                avgLoss = l * alpha + avgLoss * (1 - alpha)
            }
        }
        if (avgLoss < 1e-10) return 100f
        return (100 - 100 / (1 + avgGain / avgLoss)).toFloat()
    }

    /** CCI — 使用标准差（ddof=1），匹配 Python rolling.std() */
    private fun cci(bars: List<Kline>, period: Int): Float {
        if (bars.size < period) return 0f
        val tps = bars.takeLast(period).map { (it.high + it.low + it.close) / 3.0 }
        val ma  = tps.average()
        val std = tps.stdSample()
        return if (std == 0.0) 0f else ((tps.last() - ma) / (0.015 * std)).toFloat()
    }

    /** ADX — EWM span=period，匹配 Python ewm(span=period, adjust=False) */
    private fun adx(bars: List<Kline>, period: Int): Triple<Float, Float, Float> {
        if (bars.size < period * 2) return Triple(0f, 0f, 0f)
        val alpha = 2.0 / (period + 1)
        var atrE = 0.0; var pDmE = 0.0; var mDmE = 0.0; var adxE = 0.0
        for (i in 1 until bars.size) {
            val h = bars[i].high;  val l = bars[i].low
            val ph = bars[i-1].high; val pl = bars[i-1].low; val pc = bars[i-1].close
            val tr  = maxOf(h - l, abs(h - pc), abs(l - pc))
            val up  = h - ph; val dn = pl - l
            val pdm = if (up > dn && up > 0) up else 0.0
            val mdm = if (dn > up && dn > 0) dn else 0.0
            if (i == 1) { atrE = tr; pDmE = pdm; mDmE = mdm }
            else {
                atrE = tr * alpha + atrE * (1 - alpha)
                pDmE = pdm * alpha + pDmE * (1 - alpha)
                mDmE = mdm * alpha + mDmE * (1 - alpha)
            }
            val pdi = if (atrE > 0) pDmE / atrE * 100 else 0.0
            val mdi = if (atrE > 0) mDmE / atrE * 100 else 0.0
            val dx  = if (pdi + mdi > 0) abs(pdi - mdi) / (pdi + mdi) * 100 else 0.0
            adxE = if (i == 1) dx else dx * alpha + adxE * (1 - alpha)
        }
        if (atrE == 0.0) return Triple(0f, 0f, 0f)
        val pdi = (pDmE / atrE * 100).toFloat()
        val mdi = (mDmE / atrE * 100).toFloat()
        return Triple(adxE.toFloat(), pdi, mdi)
    }

    /** WaveTrend — 全量滚动 EMA，匹配 Python Series 操作 */
    private fun waveTrend(bars: List<Kline>, n1: Int = 10, n2: Int = 21): Pair<Float, Float> {
        if (bars.size < n2 + 4) return Pair(0f, 0f)
        val hlc3 = bars.map { (it.high + it.low + it.close) / 3.0 }
        val k1 = 2.0 / (n1 + 1)
        val k2 = 2.0 / (n2 + 1)
        val n = hlc3.size
        // 滚动 ESA = EMA(hlc3, n1)
        val esa = DoubleArray(n)
        esa[0] = hlc3[0]
        for (i in 1 until n) esa[i] = hlc3[i] * k1 + esa[i-1] * (1 - k1)
        // 滚动 d = EMA(|hlc3 - esa|, n1)
        val dArr = DoubleArray(n)
        dArr[0] = 0.0
        for (i in 1 until n) dArr[i] = abs(hlc3[i] - esa[i]) * k1 + dArr[i-1] * (1 - k1)
        // ci = (hlc3 - esa) / (0.015 * d)
        val ci = DoubleArray(n) { i -> if (dArr[i] > 0) (hlc3[i] - esa[i]) / (0.015 * dArr[i]) else 0.0 }
        // tci = EMA(ci, n2)
        val tci = DoubleArray(n)
        tci[0] = ci[0]
        for (i in 1 until n) tci[i] = ci[i] * k2 + tci[i-1] * (1 - k2)
        val wt1 = tci[n - 1].toFloat()
        val wt2 = tci.takeLast(4).average().toFloat()
        return Pair(wt1, wt2)
    }

    /** MACD histogram — 完整 EWM 信号线，匹配 Python _ema(macd, 9) */
    private fun macdHistogram(bars: List<Kline>): Double {
        if (bars.size < 35) return 0.0
        val k12 = 2.0 / 13.0; val k26 = 2.0 / 27.0; val k9 = 2.0 / 10.0
        var e12 = bars.first().close; var e26 = bars.first().close; var sig = 0.0
        for ((i, bar) in bars.withIndex()) {
            val c = bar.close
            e12 = c * k12 + e12 * (1 - k12)
            e26 = c * k26 + e26 * (1 - k26)
            val m = e12 - e26
            sig = if (i == 0) m else m * k9 + sig * (1 - k9)
        }
        return (e12 - e26) - sig
    }

    /** ATR — EWM span=period，匹配 Python tr.ewm(span=period, adjust=False) */
    private fun atr(bars: List<Kline>, period: Int): Double {
        if (bars.size < period + 1) return 0.0
        val alpha = 2.0 / (period + 1)
        var atrVal = 0.0
        for (i in 1 until bars.size) {
            val tr = maxOf(
                bars[i].high - bars[i].low,
                abs(bars[i].high - bars[i-1].close),
                abs(bars[i].low  - bars[i-1].close)
            )
            atrVal = if (i == 1) tr else tr * alpha + atrVal * (1 - alpha)
        }
        return atrVal
    }

    /** EMA（标准 alpha = 2/(period+1)）从列表起始值开始 */
    private fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val k = 2.0 / (period + 1)
        var e = values.first()
        for (v in values.drop(1)) e = v * k + e * (1 - k)
        return e
    }

    private fun emaClose(bars: List<Kline>, period: Int): Double = ema(bars.map { it.close }, period)

    private fun sign(x: Double) = when {
        x > 0 -> 1.0
        x < 0 -> -1.0
        else  -> 0.0
    }

    /** 样本标准差（ddof=1），匹配 pandas rolling.std() 默认行为 */
    private fun List<Double>.stdSample(): Double {
        if (size < 2) return 0.0
        val m = average()
        return sqrt(sumOf { (it - m).pow(2) } / (size - 1))
    }
}
