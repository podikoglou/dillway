package com.dillway

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.File
import java.io.IOException
import java.net.URI

/**
 * Uploads finished recordings to an S3-compatible bucket (Cloudflare R2).
 *
 * The listener writes the parquet locally first; this uploader then pushes the
 * file right after the recording finished, off the main thread, and removes
 * the local copy once the upload succeeds. A failed upload keeps the file for
 * a later manual push; the recording is never lost while the server is up.
 *
 * Takes its values from `config.yml` under `s3:`; a disabled or misconfigured
 * uploader makes every [upload] a no-op.
 */
class S3Uploader(private val plugin: DillPlugin) {

    private var source: S3Client? = null
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
            source = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                // AWS requires a region; R2 spans all and answers "auto".
                .region(Region.of("auto"))
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secretAccessKey),
                    ),
                )
                .serviceConfiguration(
                    S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build(),
                )
                .httpClient(UrlConnectionHttpClient.create())
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
        source?.close()
        source = null
    }

    /**
     * Uploads [file] to the bucket as one finished recording of [type]. Runs
     * on an async worker so the caller is never held on the network call;
     * after [DillPlugin.onDisable] the scheduler cannot start new tasks, so
     * the upload happens on the calling thread instead.
     */
    fun upload(file: File, type: RecordingType, fileName: String) {
        if (source == null) return
        if (plugin.isEnabled) {
            plugin.server.scheduler.runTaskAsynchronously(
                plugin,
                Runnable { uploadBlocking(file, type, fileName) },
            )
        } else {
            uploadBlocking(file, type, fileName)
        }
    }

    /** Streams [file] to the bucket at `[<key-prefix>]/<type>/<fileName>`. */
    private fun uploadBlocking(file: File, type: RecordingType, fileName: String) {
        val s3 = source ?: return
        val key = keyFor(type, fileName)

        try {
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType("application/octet-stream")
                    .build(),
                RequestBody.fromFile(file),
            )
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
}
