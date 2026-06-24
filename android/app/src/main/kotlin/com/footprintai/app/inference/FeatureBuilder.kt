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

        // 买压动量
        val buyMomentum = bars.takeLast(5).map { (it.buyVolume / it.volume.coerceAtLeast(1e-8)).toFloat() }.average().toFloat() - buyRatio

        // ── RSI-14 ──────────────────────────────────────────────────────────
        val rsi14 = rsi(bars, 14)
        val rsi14Norm = ((rsi14 - 50f) / 50f).coerceIn(-1f, 1f)

        // ── CCI-20 ──────────────────────────────────────────────────────────
        val cci20 = cci(bars, 20)
        val cci20Norm = (cci20 / 200f).coerceIn(-1f, 1f)

        // ── ADX-14 ──────────────────────────────────────────────────────────
        val (adx14, plusDi, minusDi) = adx(bars, 14)
        val adx14Norm = (adx14 / 100f).coerceIn(0f, 1f)

        // ── WaveTrend (WT1) ─────────────────────────────────────────────────
        val (wt1, wt2) = waveTrend(bars)
        val wt1Norm = (wt1 / 100f).coerceIn(-1f, 1f)
        val wtDiff  = ((wt1 - wt2) / 100f).coerceIn(-1f, 1f)

        // ── MACD histogram ──────────────────────────────────────────────────
        val macdHist = macdHistogram(bars)
        val macdHistNorm = (macdHist / cur.close * 100).toFloat().coerceIn(-1f, 1f)

        // ── ATR-14 ──────────────────────────────────────────────────────────
        val atr14Pct = (atr(bars, 14) / cur.close).toFloat()

        // ── Bollinger Bands ─────────────────────────────────────────────────
        val closes20  = bars.takeLast(20).map { it.close }
        val ma20      = closes20.average()
        val std20     = closes20.std()
        val bbUpper   = ma20 + 2 * std20
        val bbLower   = ma20 - 2 * std20
        val bbWidth   = bbUpper - bbLower
        val bbPos     = if (bbWidth > 0) ((cur.close - bbLower) / bbWidth).toFloat().coerceIn(0f, 1f) else 0.5f
        val bbWidthPct = (bbWidth / ma20).toFloat()

        // ── 清算代理 ─────────────────────────────────────────────────────────
        val volSpikeDelta = volumeSpike * deltaRatio
        val liqShortProxy = if (upperWick > 0.3f) upperWick * volumeSpike else 0f
        val liqLongProxy  = if (lowerWick > 0.3f) lowerWick * volumeSpike else 0f
        val liqNet        = liqLongProxy - liqShortProxy

        // ── CVD 加速度 ───────────────────────────────────────────────────────
        val cvd5Prev  = deltas.takeLast(10).take(5).sum()
        val cvd5Cur   = deltas.takeLast(5).sum()
        val cvdAccel  = (cvd5Cur - cvd5Prev).toFloat()

        // ── 价格动量 ─────────────────────────────────────────────────────────
        val closes = bars.map { it.close }
        val priceMom5  = if (closes.size >= 6) ((closes.last() / closes[closes.size - 6] - 1) * 100).toFloat() else 0f
        val priceMom20 = if (closes.size >= 21) ((closes.last() / closes[closes.size - 21] - 1) * 100).toFloat() else 0f

        // ── VWAP 偏差（日内近似：用20根bar的成交量加权均价）──────────────────
        val recent = bars.takeLast(20)
        val vwapNum = recent.sumOf { ((it.high + it.low + it.close) / 3) * it.volume }
        val vwapDen = recent.sumOf { it.volume }.coerceAtLeast(1e-8)
        val vwap    = vwapNum / vwapDen
        val vwapDev = ((cur.close - vwap) / vwap * 100).toFloat()

        // ── EMA-20 斜率 ──────────────────────────────────────────────────────
        val ema20     = ema(bars, 20)
        val ema20Prev = ema(bars.dropLast(1), 20)
        val ema20Slope = ((ema20 - ema20Prev) / ema20Prev * 100).toFloat()

        // ── 区间位置（20 bar high/low）───────────────────────────────────────
        val highs20 = bars.takeLast(20).map { it.high }
        val lows20  = bars.takeLast(20).map { it.low }
        val high20  = highs20.max()
        val low20   = lows20.min()
        val rangePos20     = if (high20 > low20) ((cur.close - low20) / (high20 - low20)).toFloat().coerceIn(0f, 1f) else 0.5f
        val distFromHigh20 = ((high20 - cur.close) / cur.close * 100).toFloat()
        val distFromLow20  = ((cur.close - low20)  / cur.close * 100).toFloat()

        // ── 买压一致性（std of buy_ratio over 10 bars）───────────────────────
        val buyRatios10 = bars.takeLast(10).map { (it.buyVolume / it.volume.coerceAtLeast(1e-8)).toFloat() }
        val buyRatioStd10 = buyRatios10.std().toFloat()

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

    private fun rsi(bars: List<Kline>, period: Int): Float {
        if (bars.size < period + 1) return 50f
        val closes = bars.takeLast(period + 1).map { it.close }
        var gains = 0.0; var losses = 0.0
        for (i in 1..period) {
            val diff = closes[i] - closes[i - 1]
            if (diff > 0) gains += diff else losses -= diff
        }
        val avgGain = gains / period
        val avgLoss = losses / period
        return if (avgLoss == 0.0) 100f else (100 - 100 / (1 + avgGain / avgLoss)).toFloat()
    }

    private fun cci(bars: List<Kline>, period: Int): Float {
        if (bars.size < period) return 0f
        val tps = bars.takeLast(period).map { (it.high + it.low + it.close) / 3.0 }
        val ma  = tps.average()
        val md  = tps.map { abs(it - ma) }.average()
        return if (md == 0.0) 0f else ((tps.last() - ma) / (0.015 * md)).toFloat()
    }

    private fun adx(bars: List<Kline>, period: Int): Triple<Float, Float, Float> {
        if (bars.size < period + 1) return Triple(0f, 0f, 0f)
        val recent = bars.takeLast(period + 1)
        var plusDmSum = 0.0; var minusDmSum = 0.0; var trSum = 0.0
        for (i in 1..period) {
            val h  = recent[i].high;    val l  = recent[i].low
            val ph = recent[i-1].high;  val pl = recent[i-1].low; val pc = recent[i-1].close
            val upMove   = h - ph
            val downMove = pl - l
            if (upMove > downMove && upMove > 0) plusDmSum  += upMove
            if (downMove > upMove && downMove > 0) minusDmSum += downMove
            trSum += maxOf(h - l, abs(h - pc), abs(l - pc))
        }
        if (trSum == 0.0) return Triple(0f, 0f, 0f)
        val plusDi  = (plusDmSum  / trSum * 100).toFloat()
        val minusDi = (minusDmSum / trSum * 100).toFloat()
        val dx = if (plusDi + minusDi > 0) (abs(plusDi - minusDi) / (plusDi + minusDi) * 100) else 0f
        return Triple(dx, plusDi, minusDi)
    }

    private fun waveTrend(bars: List<Kline>, n1: Int = 10, n2: Int = 21): Pair<Float, Float> {
        if (bars.size < n2 + 4) return Pair(0f, 0f)
        val hlc3 = bars.map { (it.high + it.low + it.close) / 3.0 }
        val esa   = ema(hlc3, n1)
        val d     = ema(hlc3.map { abs(it - esa) }, n1)
        val ci    = if (d > 0) hlc3.map { (it - esa) / (0.015 * d) } else hlc3.map { 0.0 }
        val wt1f  = ema(ci, n2).toFloat()
        val wt2f  = ci.takeLast(4).average().toFloat()
        return Pair(wt1f, wt2f)
    }

    private fun macdHistogram(bars: List<Kline>): Double {
        if (bars.size < 35) return 0.0
        val closes = bars.map { it.close }
        val ema12 = ema(closes, 12)
        val ema26 = ema(closes, 26)
        val macdLine = ema12 - ema26
        // 用最近 9 根的 MACD 值近似信号线（简化）
        val signal = macdLine * 0.8   // ponytail: 简化信号线
        return macdLine - signal
    }

    private fun atr(bars: List<Kline>, period: Int): Double {
        if (bars.size < period + 1) return 0.0
        val recent = bars.takeLast(period + 1)
        val trs = (1..period).map { i ->
            maxOf(
                recent[i].high - recent[i].low,
                abs(recent[i].high - recent[i-1].close),
                abs(recent[i].low  - recent[i-1].close)
            )
        }
        return trs.average()
    }

    private fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val k = 2.0 / (period + 1)
        var e = values.first()
        for (v in values.drop(1)) e = v * k + e * (1 - k)
        return e
    }

    private fun ema(bars: List<Kline>, period: Int): Double = ema(bars.map { it.close }, period)

    private fun sign(x: Double) = when {
        x > 0 -> 1.0
        x < 0 -> -1.0
        else  -> 0.0
    }

    private fun List<Double>.std(): Double {
        val m = average(); return map { (it - m).pow(2) }.average().let { sqrt(it) }
    }
    private fun List<Float>.std(): Double {
        val m = average(); return map { (it - m).pow(2) }.average().let { sqrt(it) }
    }
}
