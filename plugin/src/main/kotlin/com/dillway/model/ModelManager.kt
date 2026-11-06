package com.dillway.model

import com.dillway.DillPlugin
import com.dillway.inference.FeatureExtractor
import com.dillway.inference.InferenceWindows
import com.dillway.inference.OnnxModel
import com.dillway.recording.Recording
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantReadWriteLock

/** One loaded specialist, as shown by `/dill models`. */
data class ModelInfo(
    val cheat: String,
    val version: String,
    val windowSeconds: Double,
    val featureCount: Int,
)

/** One model's answer for one recording. */
data class CheatVerdict(
    val cheat: String,
    val version: String,
    val windowSeconds: Double,
    val windows: Int,
    val meanProbability: Double,
    val maxProbability: Double,
)

/** Aggregate answers from every loaded specialist. */
data class InferenceReport(
    val movementPackets: Int,
    val modelCount: Int,
    val verdicts: List<CheatVerdict>,
    val failures: List<String>,
)

/** Result of one refresh from R2. */
data class RefreshReport(
    val loaded: List<ModelInfo>,
    val failed: List<Pair<String, String>>,
)

/**
 * Downloads, caches and runs the per-cheat ONNX specialists.
 *
 * Cache layout mirrors the object keys under `plugins/Dillway/`:
 *
 *     models/<cheat>/<version>/model.onnx
 *     models/<cheat>/latest.json
 *
 * Startup loads the cache first, so predictions work if R2 is unreachable.
 * A refresh verifies the pointer's SHA-256 before a model reaches the cache,
 * then swaps the in-memory session. Old sessions close only after the swap,
 * and predictions hold a read lock while they run.
 */
class ModelManager(private val plugin: DillPlugin) : AutoCloseable {

    private data class LoadedModel(
        val model: OnnxModel,
        val version: String,
    )

    private val stateLock = ReentrantReadWriteLock()
    private val refreshLock = Any()

    private var loaded: Map<String, LoadedModel> = emptyMap()

    /** Loads every cached model. Runs synchronously during onEnable. */
    fun loadCached() {
        val root = cacheRoot()
        val cheats = (plugin.cheatNames + cachedCheats(root)).distinct()
        val found = mutableMapOf<String, LoadedModel>()

        for (cheat in cheats) {
            val directory = File(root, cheat)
            val version = localVersion(directory) ?: continue
            val file = File(File(directory, version), "model.onnx")
            if (!file.isFile) continue
            try {
                val model = OnnxModel.load(file, cheat)
                found[cheat] = LoadedModel(model, version)
                plugin.logger.info(
                    "Loaded cached $cheat model $version " +
                        "(window ${model.windowSeconds}s, ${model.featureNames.size} features)",
                )
            } catch (e: Exception) {
                plugin.logger.warning("Could not load cached $cheat model ${file.path}: ${e.message}")
            }
        }

        replaceAll(found)
    }

    /**
     * Fetches the newest model for every configured or discovered cheat.
     * A failed cheat keeps the model already in memory.
     */
    fun refresh(): RefreshReport {
        synchronized(refreshLock) {
            val storage = plugin.storage
            if (!storage.enabled) {
                return RefreshReport(emptyList(), listOf("all" to "remote storage is disabled"))
            }

            val remoteCheats = storage.listCommonPrefixes(storage.modelsPrefix)
            val cheats = (plugin.cheatNames + remoteCheats).distinct().sorted()
            val current = snapshot()
            val updates = mutableMapOf<String, LoadedModel>()
            val loadedNow = mutableListOf<ModelInfo>()
            val failures = mutableListOf<Pair<String, String>>()

            for (cheat in cheats) {
                try {
                    val pointerJson = storage.readString(storage.modelKey(cheat, "latest.json"))
                    if (pointerJson == null) {
                        failures += cheat to "no latest.json (not trained yet)"
                        continue
                    }

                    val pointer = ModelPointer.parse(pointerJson, cheat)
                    val target = File(plugin.dataFolder, pointer.modelKey)
                    if (!target.isFile || !checksumMatches(target, pointer.sha256)) {
                        val partial = File(target.parentFile, "${target.name}.part")
                        if (!storage.download(pointer.modelKey, partial.toPath())) {
                            failures += cheat to "download failed: ${pointer.modelKey}"
                            continue
                        }
                        if (!checksumMatches(partial, pointer.sha256)) {
                            partial.delete()
                            failures += cheat to "checksum mismatch for ${pointer.modelKey}"
                            continue
                        }
                        target.parentFile?.mkdirs()
                        Files.move(
                            partial.toPath(),
                            target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }

                    val existing = current[cheat]
                    val model = if (existing?.version == pointer.version) {
                        existing.model
                    } else {
                        OnnxModel.load(target, cheat)
                    }

                    updates[cheat] = LoadedModel(model, pointer.version)
                    writePointer(cheat, pointerJson)
                    prune(cheat, pointer.version)
                    loadedNow += ModelInfo(
                        cheat = cheat,
                        version = pointer.version,
                        windowSeconds = model.windowSeconds,
                        featureCount = model.featureNames.size,
                    )
                    plugin.logger.info(
                        "Loaded $cheat model ${pointer.version} " +
                            "(window ${model.windowSeconds}s, ${model.featureNames.size} features)",
                    )
                } catch (e: Exception) {
                    val reason = e.message ?: e.javaClass.simpleName
                    failures += cheat to reason
                    plugin.logger.warning("Could not refresh $cheat model: $reason")
                }
            }

            // Cheats that failed keep their previous model.
            val merged = HashMap(current)
            merged.putAll(updates)
            replaceAll(merged)

            return RefreshReport(loadedNow.sortedBy { it.cheat }, failures)
        }
    }

    /** Runs every loaded specialist over one recording. */
    fun infer(recording: Recording): InferenceReport {
        val lock = stateLock.readLock()
        lock.lock()
        try {
            val snapshot = loaded.toSortedMap()
            val verdicts = mutableListOf<CheatVerdict>()
            val failures = mutableListOf<String>()

            for ((cheat, model) in snapshot) {
                try {
                    verdicts += inferOne(cheat, model, recording)
                } catch (e: Exception) {
                    failures += "$cheat: ${e.message ?: e.javaClass.simpleName}"
                }
            }

            return InferenceReport(
                movementPackets = InferenceWindows.movementCount(recording.packets),
                modelCount = snapshot.size,
                verdicts = verdicts,
                failures = failures,
            )
        } finally {
            lock.unlock()
        }
    }

    /** Loaded model names, for the label menu and `/dill models`. */
    fun knownCheats(): Set<String> {
        val lock = stateLock.readLock()
        lock.lock()
        try {
            return loaded.keys.toSet()
        } finally {
            lock.unlock()
        }
    }

    fun summary(): List<ModelInfo> {
        val lock = stateLock.readLock()
        lock.lock()
        try {
            return loaded
                .map { (cheat, value) ->
                    ModelInfo(
                        cheat = cheat,
                        version = value.version,
                        windowSeconds = value.model.windowSeconds,
                        featureCount = value.model.featureNames.size,
                    )
                }
                .sortedBy { it.cheat }
        } finally {
            lock.unlock()
        }
    }

    override fun close() {
        val lock = stateLock.writeLock()
        lock.lock()
        try {
            loaded.values.forEach { value ->
                try {
                    value.model.close()
                } catch (e: Exception) {
                    plugin.logger.warning("Could not close ${value.model.cheat} model: ${e.message}")
                }
            }
            loaded = emptyMap()
        } finally {
            lock.unlock()
        }
    }

    private fun inferOne(cheat: String, loaded: LoadedModel, recording: Recording): CheatVerdict {
        val windows = InferenceWindows.split(recording.packets, loaded.model.windowSeconds)
        val rows = windows.mapNotNull { window ->
            FeatureExtractor.extract(window, loaded.model.featureNames)
        }

        if (rows.isEmpty()) {
            return CheatVerdict(
                cheat = cheat,
                version = loaded.version,
                windowSeconds = loaded.model.windowSeconds,
                windows = 0,
                meanProbability = Double.NaN,
                maxProbability = Double.NaN,
            )
        }

        val probabilities = loaded.model.predict(rows)
        return CheatVerdict(
            cheat = cheat,
            version = loaded.version,
            windowSeconds = loaded.model.windowSeconds,
            windows = probabilities.size,
            meanProbability = probabilities.average(),
            maxProbability = probabilities.max(),
        )
    }

    private fun snapshot(): Map<String, LoadedModel> {
        val lock = stateLock.readLock()
        lock.lock()
        try {
            return HashMap(loaded)
        } finally {
            lock.unlock()
        }
    }

    private fun replaceAll(replacement: Map<String, LoadedModel>) {
        val lock = stateLock.writeLock()
        lock.lock()
        try {
            val previous = loaded
            loaded = HashMap(replacement)
            previous.forEach { (cheat, value) ->
                val kept = replacement[cheat]
                if (kept == null || kept.model !== value.model) {
                    try {
                        value.model.close()
                    } catch (e: Exception) {
                        plugin.logger.warning("Could not close replaced $cheat model: ${e.message}")
                    }
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private fun cacheRoot(): File = File(plugin.dataFolder, plugin.storage.modelsPrefix)

    private fun cachedCheats(root: File): List<String> =
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?: emptyList()

    private fun localVersion(directory: File): String? {
        val pointer = File(directory, "latest.json")
        if (pointer.isFile) {
            try {
                val version = JsonParser.parseString(pointer.readText())
                    .asJsonObject
                    .string("version")
                if (version != null && File(File(directory, version), "model.onnx").isFile) {
                    return version
                }
            } catch (_: Exception) {
                // Fall through to the newest complete version directory.
            }
        }

        return directory.listFiles()
            ?.filter { it.isDirectory && File(it, "model.onnx").isFile }
            ?.maxByOrNull { it.name }
            ?.name
    }

    private fun writePointer(cheat: String, json: String) {
        val file = File(plugin.dataFolder, plugin.storage.modelKey(cheat, "latest.json"))
        file.parentFile?.mkdirs()
        file.writeText(json)
    }

    private fun prune(cheat: String, keepVersion: String) {
        val directory = File(cacheRoot(), cheat)
        directory.listFiles()
            ?.filter { it.isDirectory && it.name != keepVersion }
            ?.forEach { old -> old.deleteRecursively() }
    }

    private fun checksumMatches(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return true
        if (!file.isFile) return false
        return sha256(file).equals(expected, ignoreCase = true)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class ModelPointer(
        val version: String,
        val modelKey: String,
        val sha256: String?,
    ) {
        companion object {
            fun parse(json: String, expectedCheat: String): ModelPointer {
                val root = JsonParser.parseString(json).asJsonObject
                val cheat = root.string("cheat")
                if (cheat != null && !cheat.equals(expectedCheat, ignoreCase = true)) {
                    error("latest.json is for '$cheat', expected '$expectedCheat'")
                }
                val version = root.string("version") ?: error("latest.json has no version")
                val model = root.getAsJsonObject("model") ?: error("latest.json has no model")
                val modelKey = model.string("path") ?: error("latest.json has no model.path")
                return ModelPointer(version, modelKey, model.string("sha256"))
            }
        }
    }
}

private fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull }?.asString
