package com.dillway

import dev.hardwood.OutputFile
import dev.hardwood.s3.S3Credentials
import dev.hardwood.s3.S3Source
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Uploads finished recordings to an S3-compatible bucket (Cloudflare R2).
 *
 * The listener writes the parquet locally first; this uploader then pushes the
 * file right after the recording finished, off the main thread, and removes
 * the local copy once the upload succeeds. A failed upload keeps the file for
 * a later manual push; the recording is never lost while the server is up.
 *
 * Staging takes its values from `config.yml` under `s3:`; a disabled or
 * misconfigured uploader makes every [upload] a no-op.
 */
class S3Uploader(private val plugin: DillPlugin) {

    private var source: S3Source? = null
    private var bucket = ""
    private var keyPrefix = ""

    /** Reads config and opens the client. True when uploads will run. */
    fun start(): Boolean {
        val config = plugin.config
        if (!config.getBoolean("s3.enabled", false)) return false

        val endpoint = value("s3.endpoint")
        bucket = value("s3.bucket")
        keyPrefix = value("s3.key-prefix")
        val accessKeyId = value("s3.access-key-id")
        val secretAccessKey = value("s3.secret-access-key")
        val pathStyle = config.getBoolean("s3.path-style", true)

        val missing = listOf(
            "endpoint" to endpoint,
            "bucket" to bucket,
            "access-key-id" to accessKeyId,
            "secret-access-key" to secretAccessKey,
        ).filter { (_, value) -> value.isEmpty() }.map { (name, _) -> name }

        if (missing.isNotEmpty()) {
            plugin.logger.severe("s3.enabled is on but these config keys are empty: $missing")
            return false
        }

        return try {
            source = S3Source.builder()
                .endpoint(endpoint)
                .pathStyle(pathStyle)
                .credentials(S3Credentials.of(accessKeyId, secretAccessKey))
                .build()
            true
        } catch (e: IllegalArgumentException) {
            plugin.logger.severe("Invalid s3 config: ${e.message}")
            source = null
            false
        }
    }

    /** Closes the client. The server must not upload after this call. */
    fun stop() {
        source?.let { source ->
            try {
                source.close()
            } catch (e: IOException) {
                plugin.logger.warning("Closing s3 client failed: ${e.message}")
            }
        }
        source = null
    }

    /**
     * Uploads [file] to the bucket as one finished recording of [type]. Runs
     * on an async worker so the caller (the server main thread) is never held
     * on the network call; after [DillPlugin.onDisable] started stopping, new
     * async tasks cannot run, so the upload happens on the calling thread.
     */
    fun upload(file: File, type: RecordingType, fileName: String) {
        if (source == null) return
        if (plugin.isEnabled) {
            plugin.server.scheduler.runTaskAsynchronously(plugin) {
                uploadBlocking(file, type, fileName)
            }
        } else {
            uploadBlocking(file, type, fileName)
        }
    }

    /**
     * Streams the local parquet into the bucket at
     * `[<key-prefix>]/<type>/<fileName>`. Hardwood's `OutputFile` is the
     * upload sink; the multipart details (8 MiB parts, four in flight,
     * completion on close) are the module's job.
     *
     * A failure to complete releases the uploaded parts, and nothing appears
     * at the key — a bucket lifecycle rule on incomplete multipart uploads is
     * the safety net for a process that died mid-upload.
     */
    private fun uploadBlocking(file: File, type: RecordingType, fileName: String) {
        val s3 = source ?: return
        val key = keyFor(type, fileName)

        try {
            val out: OutputFile = s3.outputFile(bucket, key)
            out.use { sink ->
                sink.create() // the writer framework calls this; manual use must too
                FileInputStream(file).buffered().use { input ->
                    val chunk = ByteArray(CHUNK_BYTES)
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        if (read > 0) sink.write(ByteBuffer.wrap(chunk, 0, read))
                    }
                }
            }
            if (file.exists() && !file.delete()) {
                plugin.logger.warning("Uploaded $key but could not delete the local copy $file")
            }
            plugin.logger.info("Uploaded $key")
        } catch (e: IOException) {
            plugin.logger.severe("Upload of $key failed, keeping local copy ${file.name}: ${e.message}")
        }
    }

    /** `<key-prefix>/<type>/<fileName>`, the prefix level dropping when empty. */
    private fun keyFor(type: RecordingType, fileName: String): String =
        listOf(keyPrefix, type.folder, fileName)
            .filter { it.isNotEmpty() }
            .joinToString("/")

    private fun value(path: String): String = plugin.config.getString(path)?.trim().orEmpty()

    private companion object {
        /** Uploads are once-per-recording; a 64 KiB read chunk keeps memory tiny. */
        private const val CHUNK_BYTES = 64 * 1024
    }
}
