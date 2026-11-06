package com.dillway.agent.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.FloatBuffer
import java.util.Locale

/**
 * A loaded ONNX specialist.
 *
 * The artifact is self-describing: `dw.features`, `dw.window_seconds` and
 * `dw.cheat` come from the model metadata that `dw-engine` embeds at export
 * time. [predict] feeds the model's own feature order, so a plugin build and
 * a model version cannot silently disagree about column positions.
 */
class OnnxModel private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    val cheat: String,
    val version: String,
    val windowSeconds: Double,
    val featureNames: List<String>,
    private val cheatIndex: Int,
) : AutoCloseable {

    /** Cheat probability for each feature row, in row order. */
    fun predict(rows: List<FloatArray>): DoubleArray {
        if (rows.isEmpty()) return DoubleArray(0)

        val width = featureNames.size
        val flat = FloatArray(rows.size * width)
        rows.forEachIndexed { index, row ->
            require(row.size == width) {
                "feature row has ${row.size} values, model expects $width"
            }
            row.copyInto(flat, index * width)
        }

        val shape = longArrayOf(rows.size.toLong(), width.toLong())
        val input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(flat), shape)
        try {
            val inputName = session.inputNames.single()
            val result = session.run(mapOf(inputName to input))
            try {
                val output = (result.get("probabilities").orElse(null) ?: result.get(1)) as OnnxTensor
                val info = output.info as TensorInfo
                val stride = info.shape.last().toInt()
                val buffer = output.floatBuffer
                return DoubleArray(rows.size) { index ->
                    buffer.get(index * stride + cheatIndex).toDouble()
                }
            } finally {
                result.close()
            }
        } finally {
            input.close()
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        private val GSON = Gson()

        /**
         * Loads [file] and verifies that this plugin can build the features
         * the model asks for. [expectedCheat] is the directory name the model
         * came from; a mismatch with metadata is treated as a corrupt cache.
         */
        fun load(file: File, expectedCheat: String): OnnxModel {
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            val session = try {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                environment.createSession(file.absolutePath, options)
            } finally {
                options.close()
            }

            try {
                val metadata = session.metadata.customMetadata
                val cheat = metadata["dw.cheat"] ?: expectedCheat
                if (!cheat.equals(expectedCheat, ignoreCase = true)) {
                    error("model metadata says cheat '$cheat', expected '$expectedCheat'")
                }

                val featureJson = metadata["dw.features"]
                    ?: error("model has no dw.features metadata")
                val features: List<String> = GSON.fromJson(
                    featureJson,
                    object : TypeToken<List<String>>() {}.type,
                )
                if (!FeatureExtractor.supports(features)) {
                    val unknown = features.filterNot { it in FeatureExtractor.FEATURE_NAMES }
                    error("model needs unsupported features: $unknown")
                }

                val windowSeconds = metadata["dw.window_seconds"]?.toDoubleOrNull()
                    ?: error("model has no valid dw.window_seconds metadata")
                if (windowSeconds <= 0.0) {
                    error("model window_seconds must be positive, got $windowSeconds")
                }

                val labelsJson = metadata["dw.labels"]
                val labels: List<String> = if (labelsJson != null) {
                    GSON.fromJson(labelsJson, object : TypeToken<List<String>>() {}.type)
                } else {
                    listOf("LEGIT", cheat.uppercase(Locale.ROOT))
                }
                val cheatIndex = labels.indexOfFirst { it.equals(cheat, ignoreCase = true) }
                    .takeIf { it >= 0 }
                    ?: if (labels.size > 1) 1 else 0

                return OnnxModel(
                    environment = environment,
                    session = session,
                    cheat = cheat,
                    version = metadata["dw.version"] ?: "unknown",
                    windowSeconds = windowSeconds,
                    featureNames = features,
                    cheatIndex = cheatIndex,
                )
            } catch (e: Exception) {
                session.close()
                throw e
            }
        }
    }
}
