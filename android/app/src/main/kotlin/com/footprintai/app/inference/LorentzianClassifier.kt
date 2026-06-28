package com.footprintai.app.inference

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln

/**
 * LorentzianClassifier
 * ────────────────────
 * KNN using Lorentzian distance: d(x,y) = Σ log(1 + |xᵢ - yᵢ|)
 * Ported from jdehorty's TradingView Pine Script algo.
 *
 * Training data loaded from assets/lorentzian.bin (exported by export_lorentzian.py).
 * Binary layout:
 *   [N:i32][F:i32][k:i32]
 *   [scaler_mean: f32×F][scaler_scale: f32×F]
 *   [X_train: f32×N×F]   (already StandardScaled)
 *   [y_train: f32×N]
 */
class LorentzianClassifier(private val ctx: Context) {

    private var k: Int = 8
    private var n: Int = 0
    private var f: Int = 0

    private lateinit var scalerMean:  FloatArray
    private lateinit var scalerScale: FloatArray
    private lateinit var xTrain:      Array<FloatArray>   // [N][F] already scaled
    private lateinit var yTrain:      FloatArray

    // Indices of Lorentzian features inside the 39-feature vector
    // Mirrors LORENTZIAN_COLS ordering from features.py
    // [rsi14_norm=15, wt1_norm=20, cci20_norm=16, adx14_norm=17,
    //  delta_ratio=2, buy_ratio=0, liq_net=28, vol_spike_delta=29,
    //  vwap_dev=33, ema20_slope=34]
    private lateinit var featureIdx: IntArray

    fun init(lorFeatureIndices: IntArray) {
        featureIdx = lorFeatureIndices

        val bytes = ctx.assets.open("lorentzian.bin").use { it.readBytes() }
        val buf   = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        n = buf.int
        f = buf.int
        k = buf.int

        scalerMean  = FloatArray(f) { buf.float }
        scalerScale = FloatArray(f) { buf.float }

        xTrain = Array(n) { FloatArray(f) { buf.float } }
        yTrain = FloatArray(n) { buf.float }
    }

    /**
     * Predict bullish probability for a single 39-feature vector.
     * Returns value in [0,1]; >0.5 = bullish bias, <0.5 = bearish bias.
     */
    fun predict(features39: FloatArray): Float {
        // Extract and StandardScale the 10 Lorentzian features
        val x = FloatArray(f) { i ->
            val raw = features39[featureIdx[i]]
            (raw - scalerMean[i]) / scalerScale[i].coerceAtLeast(1e-8f)
        }

        // Compute Lorentzian distance to all training points
        val dists = FloatArray(n) { i ->
            var d = 0f
            for (j in 0 until f) d += ln(1f + abs(xTrain[i][j] - x[j]))
            d
        }

        // Pick k nearest neighbours (partial sort via simple selection)
        val kNeighbours = dists.indices.sortedBy { dists[it] }.take(k)
        return kNeighbours.sumOf { yTrain[it].toDouble() }.toFloat() / k
    }
}
