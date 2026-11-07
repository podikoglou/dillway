package com.dillway.agent

import com.dillway.agent.recording.Recording
import com.dillway.agent.recording.RecordingParquet
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.command.CommandSender
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Merges the small one-session recording objects into larger shards.
 *
 * A run walks the label directories under the recording prefix one at a
 * time: `legit` finishes before the next cheat starts. Inside a label it
 * lists the objects without the shard prefix, downloads a batch with bounded
 * concurrency, writes one shard, uploads it, and only then deletes the source
 * objects that the shard holds. A crash therefore leaves either the sources or
 * both the sources and the shard; training deduplicates by recording id, so
 * neither state counts a recording twice.
 *
 * Shards live in the label directory and start with [SHARD_PREFIX], so
 * "not compacted yet" is a property of the object name alone.
 *
 * Only one run may exist per server. The command is manual, so nothing starts
 * a run on its own.
 */
class RecordingCompactor(private val plugin: DwAgentPlugin) {

    private val running = AtomicBoolean(false)

    @Volatile
    private var cancelled = false

    /** Starts a run, or returns false when one is already running. */
    fun start(sender: CommandSender): Boolean {
        if (!running.compareAndSet(false, true)) return false
        cancelled = false

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val report = try {
                run()
            } catch (e: Exception) {
                plugin.logger.severe(
                    "Compaction failed: ${e.message ?: e.javaClass.simpleName}",
                )
                Report(failed = listOf("run"))
            } finally {
                running.set(false)
            }
            announce(sender, report)
        })
        return true
    }

    /** Asks a running job to stop between batches. */
    fun stop() {
        cancelled = true
    }

    private fun run(): Report {
        val storage = plugin.storage
        val perShard = plugin.config
            .getInt("s3.compaction.recordings-per-shard", 50)
            .coerceAtLeast(1)
        val concurrency = plugin.config
            .getInt("s3.compaction.download-concurrency", 4)
            .coerceAtLeast(1)

        val labels = storage.listCommonPrefixes(storage.recordingPrefix)
            .filter { it.isNotEmpty() && it != Labels.UNLABELED }
            .sorted()
        if (labels.isEmpty()) return Report()

        cleanup()
        val downloads = Executors.newFixedThreadPool(concurrency) { task ->
            Thread(task, "dw-compact-download").apply { isDaemon = true }
        }

        var shards = 0
        var recordings = 0
        val failed = mutableListOf<String>()
        try {
            for (label in labels) {
                if (cancelled || !plugin.isEnabled) break
                val result = compactLabel(label, perShard, downloads)
                shards += result.shards
                recordings += result.recordings
                if (result.error) failed += label
            }
        } finally {
            downloads.shutdownNow()
            cleanup()
        }
        return Report(shards, recordings, failed)
    }

    private fun compactLabel(
        label: String,
        perShard: Int,
        downloads: ExecutorService,
    ): LabelResult {
        val storage = plugin.storage
        val objects = storage.listObjects(storage.recordingKey(label, ""))
        if (objects == null) {
            plugin.logger.warning("[compaction] [$label] could not list objects")
            return LabelResult(error = true)
        }

        val sources = objects
            .filter { isSourceObject(it.key) }
            .sortedBy { it.key }
        if (sources.isEmpty()) {
            plugin.logger.info("[compaction] [$label] nothing to compact")
            return LabelResult()
        }

        val batches = (sources.size + perShard - 1) / perShard
        plugin.logger.info(
            "[compaction] [$label] ${sources.size} recordings in $batches batches",
        )

        val dir = File(compactionDir(), label)
        var shards = 0
        var compacted = 0
        var failed = false

        try {
            sources.chunked(perShard).forEachIndexed { index, batch ->
                if (cancelled || !plugin.isEnabled) {
                    failed = true
                    return LabelResult(shards, compacted, error = true)
                }

                val downloaded = download(batch, dir, downloads)
                if (downloaded.isEmpty()) {
                    plugin.logger.warning(
                        "[compaction] [$label] batch ${index + 1}/$batches downloaded nothing",
                    )
                    failed = true
                    return@forEachIndexed
                }

                val temp = File(dir, "shard-${index + 1}.parquet")
                val merged = merge(label, downloaded, temp)
                downloaded.forEach { it.second.delete() }
                if (merged.isEmpty()) {
                    plugin.logger.warning(
                        "[compaction] [$label] batch ${index + 1}/$batches held no usable recordings",
                    )
                    failed = true
                    return@forEachIndexed
                }

                val (fileName, key) = uniqueShardKey(label, shardFileName(merged))
                val shard = File(dir, fileName)
                if (!temp.renameTo(shard)) {
                    plugin.logger.severe(
                        "[compaction] [$label] could not rename the shard to $fileName",
                    )
                    temp.delete()
                    failed = true
                    return@forEachIndexed
                }

                if (!storage.putFile(key, shard)) {
                    shard.delete()
                    failed = true
                    return@forEachIndexed
                }
                shard.delete()

                merged.forEach { storage.delete(it.key) }
                shards += 1
                compacted += merged.size
                plugin.logger.info(
                    "[compaction] [$label] shard ${index + 1}/$batches: " +
                        "${merged.size} recordings -> $key",
                )
            }
        } finally {
            dir.deleteRecursively()
        }

        return LabelResult(shards, compacted, error = failed)
    }

    /**
     * Downloads [batch] with at most the pool's parallelism and returns the
     * successful (object, local file) pairs. A failed object stays in the
     * bucket; the rest of the batch still merges.
     */
    private fun download(
        batch: List<StoredObject>,
        dir: File,
        pool: ExecutorService,
    ): List<Pair<StoredObject, File>> {
        dir.mkdirs()
        val tasks = batch.map { stored ->
            val target = File(dir, fileName(stored.key))
            pool.submit(Callable {
                if (plugin.storage.download(stored.key, target.toPath())) {
                    stored to target
                } else {
                    null
                }
            })
        }
        return tasks.mapNotNull { task ->
            try {
                task.get()
            } catch (e: Exception) {
                plugin.logger.warning("[compaction] download task failed: ${e.message}")
                null
            }
        }
    }

    /**
     * Reads the downloaded sessions and writes them into [shard] in one pass:
     * only one recording is held in memory at a time. Returns the source
     * objects that the shard actually holds.
     */
    private fun merge(
        label: String,
        downloaded: List<Pair<StoredObject, File>>,
        shard: File,
    ): List<StoredObject> {
        val included = mutableListOf<StoredObject>()
        val recordings = Iterable {
            downloaded.asSequence().mapNotNull { (stored, file) ->
                val recording = read(label, file) ?: return@mapNotNull null
                included += stored
                recording
            }.iterator()
        }

        return try {
            RecordingParquet.writeRecordings(shard, recordings)
            included.toList()
        } catch (e: Exception) {
            plugin.logger.severe(
                "[compaction] [$label] could not write a shard: ${e.message ?: e.javaClass.simpleName}",
            )
            shard.delete()
            emptyList()
        }
    }

    private fun read(label: String, file: File): Recording? =
        try {
            val recording = RecordingParquet.readRecording(file)
            if (recording.packets.isEmpty()) {
                plugin.logger.warning("[compaction] [$label] skipped empty ${file.name}")
                null
            } else {
                recording
            }
        } catch (e: Exception) {
            plugin.logger.warning(
                "[compaction] [$label] skipped unreadable ${file.name}: " +
                    (e.message ?: e.javaClass.simpleName),
            )
            null
        }

    /** `compacted-<first id>-<last id>.parquet`, the range the shard covers. */
    private fun shardFileName(merged: List<StoredObject>): String {
        val ids = merged.mapNotNull { fileName(it.key).removeSuffix(".parquet").toLongOrNull() }
            .sorted()
        val first = ids.firstOrNull() ?: 0L
        val last = ids.lastOrNull() ?: 0L
        return "$SHARD_PREFIX$first-$last.parquet"
    }

    /** Adds `-2`, `-3`, ... when a shard with the same range already exists. */
    private fun uniqueShardKey(label: String, fileName: String): Pair<String, String> {
        val stem = fileName.removeSuffix(".parquet")
        var candidate = fileName
        var attempt = 1
        while (plugin.storage.exists(plugin.storage.recordingKey(label, candidate))) {
            attempt += 1
            candidate = "$stem-$attempt.parquet"
        }
        return candidate to plugin.storage.recordingKey(label, candidate)
    }

    private fun announce(sender: CommandSender, report: Report) {
        if (!plugin.isEnabled) return
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (report.shards == 0 && report.failed.isEmpty()) {
                sender.sendMini("<gray>Nothing to compact.</gray>")
                return@Runnable
            }

            val message = if (report.failed.isEmpty()) {
                "<gray>Compacted <green><recordings></green> recordings into " +
                    "<green><shards></green> shards.</gray>"
            } else {
                "<gray>Compacted <green><recordings></green> recordings into " +
                    "<green><shards></green> shards. " +
                    "<red>Failed: <labels></red></gray>"
            }
            sender.sendMini(
                message,
                Placeholder.unparsed("recordings", report.recordings.toString()),
                Placeholder.unparsed("shards", report.shards.toString()),
                Placeholder.unparsed("labels", report.failed.joinToString(", ")),
            )
        })
    }

    private fun cleanup() {
        compactionDir().deleteRecursively()
    }

    private fun compactionDir(): File = File(plugin.dataFolder, COMPACTION_DIR)

    private companion object {
        const val SHARD_PREFIX = "compacted-"
        const val COMPACTION_DIR = ".compaction"

        fun fileName(key: String): String = key.substringAfterLast('/')

        fun isSourceObject(key: String): Boolean =
            key.endsWith(".parquet") && !fileName(key).startsWith(SHARD_PREFIX)
    }

    private data class Report(
        val shards: Int = 0,
        val recordings: Int = 0,
        val failed: List<String> = emptyList(),
    )

    private data class LabelResult(
        val shards: Int = 0,
        val recordings: Int = 0,
        val error: Boolean = false,
    )
}
