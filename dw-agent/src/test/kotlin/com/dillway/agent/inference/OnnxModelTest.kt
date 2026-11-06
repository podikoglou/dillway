package com.dillway.agent.inference

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Runs against a tiny exported specialist (committed fixture, 10 trees),
 * which proves the ONNX runtime loads in-process, the `dw.*` metadata is
 * read, and the cheat probability column is picked correctly.
 */
class OnnxModelTest {

    @Test
    fun `loads metadata and predicts cheat probabilities`() {
        load().use { model ->
            assertEquals("speed", model.cheat)
            assertEquals("smoke-0", model.version)
            assertEquals(3.5, model.windowSeconds, 1e-9)
            assertEquals(FeatureExtractor.FEATURE_NAMES.size, model.featureNames.size)

            val probabilities = model.predict(
                listOf(
                    FloatArray(model.featureNames.size) { 0.5f },
                    FloatArray(model.featureNames.size) { -2.0f },
                ),
            )

            assertEquals(2, probabilities.size)
            assertTrue(probabilities.all { value -> value in 0.0..1.0 })
        }
    }

    @Test
    fun `rejects a model whose cheat does not match its source`() {
        assertFailsWith<IllegalStateException> {
            OnnxModel.load(modelFile, "killaura")
        }
    }

    @Test
    fun `rejects rows with a wrong feature count`() {
        load().use { model ->
            assertFailsWith<IllegalArgumentException> {
                model.predict(listOf(FloatArray(3)))
            }
        }
    }

    private fun load(): OnnxModel {
        val file = tempModelFile()
        return OnnxModel.load(file, "speed")
    }

    /** Copies the classpath fixture to a temp file, as a downloaded model lands. */
    private fun tempModelFile(): File {
        val bytes = javaClass.classLoader
            .getResourceAsStream("models/speed.onnx")
            ?.readBytes()
            ?: error("test fixture models/speed.onnx missing")
        val file = File.createTempFile("dillway-smoke-", ".onnx")
        file.writeBytes(bytes)
        file.deleteOnExit()
        return file
    }

    private val modelFile: File
        get() = tempModelFile()
}
