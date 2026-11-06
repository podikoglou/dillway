package com.dillway

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * S3-compatible storage for Cloudflare R2.
 *
 * Two kinds of objects live here:
 *
 * - recordings: `<key-prefix>/<label>/<started>.parquet`, written locally
 *   first and uploaded after the label is chosen;
 * - models: `<models-prefix>/<cheat>/latest.json` pointing at
 *   `<models-prefix>/<cheat>/<version>/model.onnx`.
 *
 * A disabled or misconfigured storage makes every operation a no-op. The
 * model cache on disk still works in that case.
 */
class S3Storage(private val plugin: DillPlugin) {

    private var source: S3Client? = null
    private var bucket = ""
    private var keyPrefix = ""
    private var modelsKeyPrefix = "models"

    val enabled: Boolean get() = source != null

    val modelsPrefix: String get() = modelsKeyPrefix

    /** Reads config and opens the client. True when remote storage will run. */
    fun start(): Boolean {
        val config = plugin.config
        keyPrefix = value("s3.key-prefix").trim('/')
        modelsKeyPrefix = config.getString("models.prefix")?.trim()?.trim('/').orEmpty()
            .ifEmpty { "models" }
        if (!config.getBoolean("s3.enabled", false)) return false

        val endpoint = value("s3.endpoint")
        bucket = value("s3.bucket")
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
        plugin.logger.info(
            "Recording uploads go to s3://$bucket/${recordingKey("*", "...")}, " +
                "models are read from s3://$bucket/$modelsKeyPrefix/",
        )
        true
        } catch (e: IllegalArgumentException) {
            plugin.logger.severe("Invalid s3 config: ${e.message}")
            source = null
            false
        }
    }

    /** Closes the client. No operation may run after this call. */
    fun stop() {
        source?.close()
        source = null
    }

    /**
     * Uploads [file] as one finished recording of [label]. On the main thread
     * the network call runs async; from an async worker it runs inline so the
     * caller can report completion.
     */
    fun upload(file: File, label: String, fileName: String) {
        if (source == null) return
        if (plugin.server.isPrimaryThread) {
            if (plugin.isEnabled) {
                plugin.server.scheduler.runTaskAsynchronously(
                    plugin,
                    Runnable { uploadBlocking(file, label, fileName) },
                )
            } else {
                uploadBlocking(file, label, fileName)
            }
        } else {
            uploadBlocking(file, label, fileName)
        }
    }

    /** Streams [file] to the bucket at `[<key-prefix>]/<label>/<fileName>`. */
    private fun uploadBlocking(file: File, label: String, fileName: String) {
        val key = recordingKey(label, fileName)
        val s3 = source ?: return
        val bytes = file.length()

        try {
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType("application/octet-stream")
                    .build(),
                RequestBody.fromFile(file),
            )
            plugin.metrics.uploadSucceeded(bytes)
            if (file.exists() && !file.delete()) {
                plugin.logger.warning("Uploaded $key but could not delete the local copy $file")
            }
            plugin.logger.info("Uploaded $key")
        } catch (e: IOException) {
            plugin.metrics.uploadFailed()
            plugin.logger.severe("Upload of $key failed, keeping local copy ${file.name}: ${e.message}")
        } catch (e: S3Exception) {
            plugin.metrics.uploadFailed()
            plugin.logger.severe("Upload of $key failed, keeping local copy ${file.name}: ${e.message}")
        }
    }

    /** Reads a small object (a latest pointer) as UTF-8, or null if absent. */
    fun readString(key: String): String? {
        val s3 = source ?: return null
        return try {
            s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build(),
            ).asUtf8String()
        } catch (e: NoSuchKeyException) {
            null
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) {
                null
            } else {
                plugin.logger.warning("Could not read $key: ${e.message}")
                null
            }
        }
    }

    /** Downloads [key] into [target], replacing it. False when the object is absent. */
    fun download(key: String, target: Path): Boolean {
        val s3 = source ?: return false
        return try {
            target.parent?.let { Files.createDirectories(it) }
            Files.deleteIfExists(target)
            s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build(),
                ResponseTransformer.toFile(target),
            )
            true
        } catch (e: NoSuchKeyException) {
            Files.deleteIfExists(target)
            false
        } catch (e: S3Exception) {
            Files.deleteIfExists(target)
            if (e.statusCode() != 404) {
                plugin.logger.warning("Could not download $key: ${e.message}")
            }
            false
        } catch (e: IOException) {
            Files.deleteIfExists(target)
            plugin.logger.warning("Could not write $key to $target: ${e.message}")
            false
        }
    }

    /**
     * Label directories that hold a recording with [fileName]. Only labels
     * that contain the object are returned, so a download does not follow.
     */
    fun labelsContaining(fileName: String): List<String> {
        if (source == null) return emptyList()
        return listCommonPrefixes(keyPrefix)
            .filter { exists(recordingKey(it, fileName)) }
            .sorted()
    }

    /** True when [key] exists. False when storage is off or the object is absent. */
    fun exists(key: String): Boolean {
        val s3 = source ?: return false
        return try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
            true
        } catch (e: NoSuchKeyException) {
            false
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) {
                false
            } else {
                plugin.logger.warning("Could not check $key: ${e.message}")
                false
            }
        }
    }

    /** Deletes [key]. A missing object is not an error. */
    fun delete(key: String) {
        val s3 = source ?: return
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
            plugin.logger.info("Deleted $key")
        } catch (e: S3Exception) {
            plugin.logger.warning("Could not delete $key: ${e.message}")
        }
    }

    /**
     * Names directly under [prefix] (for example every trained cheat under
     * `models/`). Returns an empty list when listing is unavailable.
     */
    fun listCommonPrefixes(prefix: String): List<String> {
        val s3 = source ?: return emptyList()
        val normalized = prefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val names = linkedSetOf<String>()
        var token: String? = null

        try {
            while (true) {
                val request = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(normalized)
                    .delimiter("/")
                    .continuationToken(token)
                    .build()
                val response = s3.listObjectsV2(request)
                response.commonPrefixes().forEach { common ->
                    val name = common.prefix().removePrefix(normalized).trimEnd('/')
                    if (name.isNotEmpty()) names += name
                }
                if (response.isTruncated != true) break
                token = response.nextContinuationToken() ?: break
            }
        } catch (e: S3Exception) {
            plugin.logger.warning("Could not list s3://$bucket/$normalized: ${e.message}")
        }
        return names.toList()
    }

    /** `<key-prefix>/<label>/<fileName>`, the prefix level dropping when empty. */
    fun recordingKey(label: String, fileName: String): String =
        listOf(keyPrefix, label, fileName)
            .filter { it.isNotEmpty() }
            .joinToString("/")

    /** `<models-prefix>/<cheat>/<suffix>`. */
    fun modelKey(cheat: String, suffix: String): String =
        listOf(modelsKeyPrefix, cheat, suffix)
            .filter { it.isNotEmpty() }
            .joinToString("/")

    private fun value(path: String): String = plugin.config.getString(path)?.trim().orEmpty()
}
