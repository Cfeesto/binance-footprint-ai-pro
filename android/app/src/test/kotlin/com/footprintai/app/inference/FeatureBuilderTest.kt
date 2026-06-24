package com.footprintai.app.inference

import com.footprintai.app.model.Kline
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import org.json.JSONObject

/**
 * FeatureBuilderTest
 * ------------------
 * Loads ground-truth Python feature vectors from backtest/results/feature_parity_samples.json
 * and asserts Kotlin FeatureBuilder.build() output matches within tolerance.
 *
 * Run with: ./gradlew :app:test
 *
 * Generate samples first: cd backtest && python3 validate_features.py
 */
@RunWith(JUnit4::class)
class FeatureBuilderTest {

    companion object {
        // Path relative to project root when tests run via Gradle
        private val SAMPLES_PATH = listOf(
            "src/test/resources/feature_parity_samples.json",
            "backtest/results/feature_parity_samples.json",
            "../backtest/results/feature_parity_samples.json",
            "../../backtest/results/feature_parity_samples.json",
        )

        // Features driven by EWM with short window — allow larger tolerance
        // (Python EWM starts from index 0 of the full dataset; Kotlin from window start)
        private val LOOSE_FEATURES = setOf(
            "rsi14_norm", "cci20_norm",
            "adx14_norm", "plus_di", "minus_di",
            "wt1_norm", "wt_diff",
            "macd_hist_norm", "atr14_pct",
        )

        private const val STRICT_TOL = 1e-3   // deterministic features
        private const val LOOSE_TOL  = 0.10   // EWM-warmed features
    }

    @Test
    fun featureParity() {
        val file = SAMPLES_PATH.map(::File).firstOrNull { it.exists() }
            ?: run {
                println("SKIP: feature_parity_samples.json not found — run backtest/validate_features.py first")
                return
            }

        val root = JSONObject(file.readText())
        val featureCols = (0 until root.getJSONArray("feature_cols").length())
            .map { root.getJSONArray("feature_cols").getString(it) }
        val samples = root.getJSONArray("samples")

        var passed = 0; var failed = 0

        for (i in 0 until samples.length()) {
            val sample  = samples.getJSONObject(i)
            val ts      = sample.getString("ts")
            val klinesJ = sample.getJSONArray("klines")
            val featJ   = sample.getJSONObject("features")

            val bars = (0 until klinesJ.length()).map { j ->
                val k = klinesJ.getJSONObject(j)
                Kline(
                    openTime  = k.getLong("openTime"),
                    open      = k.getDouble("open"),
                    high      = k.getDouble("high"),
                    low       = k.getDouble("low"),
                    close     = k.getDouble("close"),
                    volume    = k.getDouble("volume"),
                    buyVolume = k.getDouble("buyVolume"),
                    isClosed  = true,
                )
            }

            val result = FeatureBuilder.build(bars)
            assertNotNull("sample[$i] ($ts): build() returned null", result)
            result!!

            assertEquals("feature count mismatch", featureCols.size, result.size)

            for ((idx, col) in featureCols.withIndex()) {
                val py = featJ.getDouble(col)
                val kt = result[idx].toDouble()
                val tol = if (col in LOOSE_FEATURES) LOOSE_TOL else STRICT_TOL

                if (py.isNaN() || py.isInfinite()) continue  // Python produced NaN — skip

                val diff = Math.abs(py - kt)
                if (diff > tol) {
                    System.err.println("FAIL sample[$i] $col: py=$py kt=$kt diff=$diff (tol=$tol)")
                    failed++
                } else {
                    passed++
                }
            }
        }

        println("Feature parity: $passed passed, $failed failed out of ${samples.length() * featureCols.size} checks")
        assertEquals("Feature parity failures (see stderr for details)", 0, failed)
    }
}
