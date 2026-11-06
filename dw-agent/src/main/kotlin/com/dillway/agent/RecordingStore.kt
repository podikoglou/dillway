package com.dillway.agent

import com.dillway.agent.recording.Recording
import com.dillway.agent.recording.RecordingParquet
import java.io.File

/**
 * Recording files on disk, addressed by recording ID.
 *
 * Capture always lands in `.staging/<id>.parquet`; that copy is the source for
 * predictions and for every later label change. A save writes one copy per
 * selected label under `<label>/<id>.parquet`, which may be uploaded and then
 * removed locally. The dot directory cannot collide with a label, because
 * [Labels.normalize] keeps no dots.
 *
 * File methods are safe from any thread; [mintId] serializes itself.
 */
class RecordingStore(private val plugin: DwAgentPlugin) {

    // After enable, never in the field initializer: JavaPlugin sets dataFolder
    // during init, which runs after the plugin instance is constructed.
    private val stagingDir: File get() = File(plugin.dataFolder, STAGING)

    private var lastId = 0L

    /**
     * Builds a fresh ID from [started]. IDs never repeat: the clock may move
     * backwards, and files may already exist from an earlier run.
     */
    @Synchronized
    fun mintId(started: Long): Long {
        var id = maxOf(started, lastId + 1)
        while (resolve(id) != null) id++
        lastId = id
        return id
    }

    /** Writes the canonical copy for a stopped session and returns its ID. */
    fun saveStaging(stopped: StoppedRecording): Long? {
        val id = mintId(stopped.session.started)
        val recording = recordingOf(
            stopped = stopped,
            id = id,
            label = Labels.UNLABELED,
        )
        write(stagingFile(id), recording) ?: return null
        plugin.metrics.recordingSaved()
        plugin.logger.info(
            "Saved recording ${stopped.playerName} as $id (${recording.packets.size} packets)",
        )
        return id
    }

    /**
     * Writes a stopped session straight under `unlabeled/`. Used on shutdown,
     * when the admin can no longer label it but the data is still worth keeping.
     */
    fun saveUnlabeled(stopped: StoppedRecording): File? {
        val id = mintId(stopped.session.started)
        val recording = recordingOf(
            stopped = stopped,
            id = id,
            label = Labels.UNLABELED,
        )
        val file = write(
            File(File(plugin.dataFolder, Labels.UNLABELED), "$id.parquet"),
            recording,
        )
        if (file != null) plugin.metrics.recordingSaved()
        return file
    }

    /** Writes one labeled copy of [recording] and returns the file. */
    fun writeLabel(recording: Recording, label: String): File? {
        val dir = File(plugin.dataFolder, label)
        val file = File(dir, "${recording.id}.parquet")
        val written = write(file, recording.copy(label = label))
        if (written != null) plugin.metrics.labelWritten()
        return written
    }

    /** Loads a recording by ID, or null when no local copy exists. */
    fun load(id: Long): Recording? {
        val file = resolve(id) ?: return null
        return try {
            RecordingParquet.readRecording(file)
        } catch (e: Exception) {
            plugin.logger.warning("Could not read recording ${file.name}: ${e.message}")
            null
        }
    }

    fun exists(id: Long): Boolean = resolve(id) != null

    /** Labels whose directory holds a copy of this recording. */
    fun localLabels(id: Long): List<String> =
        labelDirectories()
            .filter { File(it, "$id.parquet").isFile }
            .map { it.name }
            .sorted()

    /** Deletes the staging copy and every labeled copy. */
    fun remove(id: Long) {
        stagingFile(id).delete()
        labelDirectories().forEach { File(it, "$id.parquet").delete() }
    }

    fun removeLabel(id: Long, label: String) {
        File(File(plugin.dataFolder, label), "$id.parquet").delete()
    }

    /** The staging file if present, otherwise any labeled copy. */
    private fun resolve(id: Long): File? {
        val staging = stagingFile(id)
        if (staging.isFile) return staging
        return labelDirectories()
            .map { File(it, "$id.parquet") }
            .firstOrNull { it.isFile }
    }

    private fun stagingFile(id: Long): File = File(stagingDir, "$id.parquet")

    /** Immediate directories that hold recordings; dot directories never do. */
    private fun labelDirectories(): List<File> =
        plugin.dataFolder.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?: emptyList()

    private fun recordingOf(stopped: StoppedRecording, id: Long, label: String) = Recording(
        id = id,
        started = stopped.session.started,
        player = stopped.session.playerId.toString(),
        label = label,
        packets = stopped.session.packets.toList(),
    )

    private fun write(file: File, recording: Recording): File? =
        try {
            file.parentFile?.mkdirs()
            RecordingParquet.writeRecording(file, recording)
            file
        } catch (e: Exception) {
            plugin.logger.severe(
                "Failed to write recording ${file.name}: " +
                    (e.message ?: e.javaClass.simpleName),
            )
            null
        }

    private companion object {
        const val STAGING = ".staging"
    }
}
