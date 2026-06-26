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
    val probCat:  Float,
    val probXgb:  Float,
    val probRf:   Float,
)

/**
 * OnnxInferenceEngine
 * ───────────────────
 * 加载 3 个 ONNX 模型（CatBoost / XGBoost / RandomForest），
 * 加权平均概率，与阈值比对输出 LONG / SHORT / NEUTRAL。
 *
 * 模型文件放在 assets/ 下，首次调用 init() 时懒加载。
 */
class OnnxInferenceEngine(private val ctx: Context) {

    private lateinit var env:       OrtEnvironment
    private lateinit var catSess:   OrtSession
    private lateinit var xgbSess:   OrtSession
    private lateinit var rfSess:    OrtSession

    var longThresh:  Float = 0.680f
    var shortThresh: Float = 0.211f

    // 默认权重（Lorentzian 权重已重新分配，见 export_onnx.py 注释）
    private var wCat: Float = 0.45f
    private var wXgb: Float = 0.35f
    private var wRf:  Float = 0.20f

    /** 必须在后台线程调用 */
    fun init() {
        try {
            env = OrtEnvironment.getEnvironment()

        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        }

        catSess = env.createSession(loadAsset("catboost.onnx"), opts)
        xgbSess = env.createSession(loadAsset("xgboost.onnx"), opts)
        rfSess  = env.createSession(loadAsset("rf.onnx"), opts)

        // 读阈值
        ctx.assets.open("thresholds.json").bufferedReader().use { r ->
            val j = JSONObject(r.readText())
            longThresh  = j.getDouble("long_thresh").toFloat()
            shortThresh = j.getDouble("short_thresh").toFloat()
            val w = j.optJSONObject("weights")
            if (w != null) {
                wCat = w.getDouble("catboost").toFloat()
                wXgb = w.getDouble("xgboost").toFloat()
                wRf  = w.getDouble("rf").toFloat()
            }
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
        val shape    = longArrayOf(1, features.size.toLong())
        val buf      = FloatBuffer.wrap(features)
        val tensor   = OnnxTensor.createTensor(env, buf, shape)

        // ponytail: CatBoost ONNX 输出 label + probabilities，取 probabilities[0][1]
        val pCat = runSession(catSess, tensor, isCatboost = true)
        val pXgb = runSession(xgbSess, tensor, isCatboost = false)
        val pRf  = runSession(rfSess,  tensor, isCatboost = false)

        tensor.close()

        val ensemble = wCat * pCat + wXgb * pXgb + wRf * pRf

        val signal = when {
            ensemble >= longThresh  -> Signal.LONG
            ensemble <= shortThresh -> Signal.SHORT
            else                    -> Signal.NEUTRAL
        }
        return InferenceOutput(signal, ensemble, pCat, pXgb, pRf)
    }

    private fun runSession(sess: OrtSession, tensor: OnnxTensor, isCatboost: Boolean): Float {
        val inputName = sess.inputNames.iterator().next()
        val out = sess.run(mapOf(inputName to tensor))

        return try {
            if (isCatboost) {
                // CatBoost: output[1] = probabilities float[1][2]
                val probs = out[1].value as Array<FloatArray>
                probs[0][1]
            } else {
                // XGBoost / RF skl2onnx: output[1] = probabilities float[1][2]
                val probs = out[1].value as Array<FloatArray>
                probs[0][1]
            }
        } catch (e: Exception) {
            // Fallback: output[0] scalar
            (out[0].value as? Float) ?: 0.5f
        } finally {
            out.forEach { it.value.close() }
        }
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
