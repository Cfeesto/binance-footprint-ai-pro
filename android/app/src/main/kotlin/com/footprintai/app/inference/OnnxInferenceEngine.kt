package com.footprintai.app.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.footprintai.app.model.Signal
import org.json.JSONObject
import java.nio.FloatBuffer

data class InferenceOutput(
    val signal:   Signal,
    val ensemble: Float,
    val probLor:  Float,
    val probCat:  Float,
    val probXgb:  Float,
    val probRf:   Float,
)

/**
 * OnnxInferenceEngine
 * ───────────────────
 * 加载 3 个 ONNX 模型（CatBoost / XGBoost / RandomForest）+
 * 原生 Lorentzian KNN 分类器，加权平均概率，与阈值比对输出信号。
 *
 * 权重: Lorentzian 35% · CatBoost 30% · XGBoost 25% · RF 10%
 * 模型文件放在 assets/ 下，首次调用 init() 时懒加载。
 */
class OnnxInferenceEngine(private val ctx: Context) {

    private lateinit var env:       OrtEnvironment
    private lateinit var catSess:   OrtSession
    private lateinit var xgbSess:   OrtSession
    private lateinit var rfSess:    OrtSession

    private val lorentzian = LorentzianClassifier(ctx)

    var longThresh:  Float = 0.64f
    var shortThresh: Float = 0.25f

    private var wLor: Float = 0.35f
    private var wCat: Float = 0.30f
    private var wXgb: Float = 0.25f
    private var wRf:  Float = 0.10f

    /** 必须在后台线程调用 */
    fun init() {
        try {
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }

            catSess = env.createSession(loadAsset("catboost.onnx"), opts)
            xgbSess = env.createSession(loadAsset("xgboost.onnx"), opts)
            rfSess  = env.createSession(loadAsset("rf.onnx"), opts)

            // 读阈值 + Lorentzian 特征索引
            ctx.assets.open("thresholds.json").bufferedReader().use { r ->
                val j = JSONObject(r.readText())
                longThresh  = j.getDouble("long_thresh").toFloat()
                shortThresh = j.getDouble("short_thresh").toFloat()
                val w = j.optJSONObject("weights")
                if (w != null) {
                    wLor = w.optDouble("lorentzian", 0.35).toFloat()
                    wCat = w.getDouble("catboost").toFloat()
                    wXgb = w.getDouble("xgboost").toFloat()
                    wRf  = w.getDouble("rf").toFloat()
                }
                val lorIdxArr = j.optJSONArray("lorentzian_feature_indices")
                val lorIdx = if (lorIdxArr != null) {
                    IntArray(lorIdxArr.length()) { lorIdxArr.getInt(it) }
                } else {
                    intArrayOf(15, 20, 16, 17, 2, 0, 28, 29, 33, 34)
                }
                lorentzian.init(lorIdx)
            }
        } catch (e: Exception) {
            throw RuntimeException("Inference Engine Init Error: ${e.message}")
        }
    }

    /**
     * 推断单个特征向量。
     * @return InferenceOutput  信号 + ensemble 概率 + 各模型概率
     */
    fun infer(features: FloatArray): InferenceOutput {
        val shape  = longArrayOf(1, features.size.toLong())
        val buf    = FloatBuffer.wrap(features)
        val tensor = OnnxTensor.createTensor(env, buf, shape)

        val pCat = runSession(catSess, tensor)
        val pXgb = runSession(xgbSess, tensor)
        val pRf  = runSession(rfSess,  tensor)
        tensor.close()

        val pLor = lorentzian.predict(features)

        val ensemble = wLor * pLor + wCat * pCat + wXgb * pXgb + wRf * pRf

        val signal = when {
            ensemble >= longThresh  -> Signal.LONG
            ensemble <= shortThresh -> Signal.SHORT
            else                    -> Signal.NEUTRAL
        }
        return InferenceOutput(signal, ensemble, pLor, pCat, pXgb, pRf)
    }

    private fun runSession(sess: OrtSession, tensor: OnnxTensor): Float {
        val inputName = sess.inputNames.iterator().next()
        val out = sess.run(mapOf(inputName to tensor))
        return try { extractProb(out) } finally { out.forEach { it.value.close() } }
    }

    /**
     * 多策略概率提取，兼容不同 ONNX 导出格式：
     *  S1 — float[N][2]  (all models after zipmap=False fix)
     *  S2 — List<Map>    (legacy sequence format)
     *  S3 — Array<Map>
     *  S4 — flat FloatArray
     *  S5 — scalar fallback
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractProb(out: OrtSession.Result): Float {
        try {
            val probs = out[1].value as? Array<FloatArray>
            if (probs != null) return probs[0][1]
        } catch (_: Exception) {}

        try {
            val raw = out[1].value
            if (raw is List<*>) {
                val m = raw.firstOrNull() as? Map<*, *>
                if (m != null) return (m[1L] as? Float) ?: (m[1] as? Float) ?: 0.5f
            }
        } catch (_: Exception) {}

        try {
            val arr = out[1].value as? Array<*>
            if (arr != null && arr.isNotEmpty()) {
                val m = arr[0] as? Map<*, *>
                if (m != null) return (m[1L] as? Float) ?: (m[1] as? Float) ?: 0.5f
            }
        } catch (_: Exception) {}

        try {
            val flat = out[1].value as? FloatArray
            if (flat != null && flat.size >= 2) return flat[1]
        } catch (_: Exception) {}

        return (out[0].value as? Float) ?: 0.5f
    }

    /** 运行时覆盖阈值（Settings 屏调用） */
    fun setThresholds(shortT: Float, longT: Float) {
        longThresh  = longT
        shortThresh = shortT
    }

    private fun loadAsset(name: String): ByteArray =
        ctx.assets.open(name).use { it.readBytes() }

    fun close() {
        if (::catSess.isInitialized) catSess.close()
        if (::xgbSess.isInitialized) xgbSess.close()
        if (::rfSess.isInitialized)  rfSess.close()
        if (::env.isInitialized)     env.close()
    }
}
