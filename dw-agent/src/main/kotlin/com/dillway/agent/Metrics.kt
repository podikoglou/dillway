package com.dillway.agent

import java.util.concurrent.atomic.AtomicLong

/** A consistent read of every counter, plus the time they have been running. */
data class MetricsSnapshot(
    val uptimeNanos: Long = 0,
    val recordingsActive: Int = 0,
    val recordingsStarted: Long = 0,
    val recordingsStopped: Long = 0,
    val recordingsSaved: Long = 0,
    val recordingsDiscarded: Long = 0,
    val labelsWritten: Long = 0,
    val packetsRecorded: Long = 0,
    val watchesActive: Int = 0,
    val watchesStarted: Long = 0,
    val inferenceRuns: Long = 0,
    val inferenceFailures: Long = 0,
    val inferenceNanosTotal: Long = 0,
    val inferenceNanosMax: Long = 0,
    val inferenceNanosLast: Long = 0,
    val windowsScored: Long = 0,
    val verdicts: Long = 0,
    val flaggedVerdicts: Long = 0,
    val uploads: Long = 0,
    val uploadFailures: Long = 0,
    val bytesUploaded: Long = 0,
    val modelRefreshes: Long = 0,
    val modelsLoaded: Long = 0,
    val modelFailures: Long = 0,
)

/**
 * Process-local counters behind `/dill metrics`.
 *
 * Nothing here touches disk and every counter resets when the server restarts.
 * Capture runs on the main thread while inference and uploads report from async
 * workers, so each update is an atomic add; a snapshot read may observe a
 * counter mid-update but never a torn value.
 */
class Metrics {

    private val startedAt = System.nanoTime()

    private val recordingsStarted = AtomicLong()
    private val recordingsStopped = AtomicLong()
    private val recordingsSaved = AtomicLong()
    private val recordingsDiscarded = AtomicLong()
    private val labelsWritten = AtomicLong()
    private val packetsRecorded = AtomicLong()

    private val watchesStarted = AtomicLong()

    private val inferenceRuns = AtomicLong()
    private val inferenceFailures = AtomicLong()
    private val inferenceNanosTotal = AtomicLong()
    private val inferenceNanosMax = AtomicLong()
    private val inferenceNanosLast = AtomicLong()
    private val windowsScored = AtomicLong()
    private val verdicts = AtomicLong()
    private val flaggedVerdicts = AtomicLong()

    private val uploads = AtomicLong()
    private val uploadFailures = AtomicLong()
    private val bytesUploaded = AtomicLong()

    private val modelRefreshes = AtomicLong()
    private val modelsLoaded = AtomicLong()
    private val modelFailures = AtomicLong()

    fun recordingStarted() {
        recordingsStarted.incrementAndGet()
    }

    fun recordingStopped() {
        recordingsStopped.incrementAndGet()
    }

    fun recordingSaved() {
        recordingsSaved.incrementAndGet()
    }

    fun recordingDiscarded() {
        recordingsDiscarded.incrementAndGet()
    }

    fun labelWritten() {
        labelsWritten.incrementAndGet()
    }

    fun packetRecorded() {
        packetsRecorded.incrementAndGet()
    }

    fun watchStarted() {
        watchesStarted.incrementAndGet()
    }

    /** Records one finished inference pass over every loaded specialist. */
    fun inferenceFinished(
        durationNanos: Long,
        windows: Int,
        verdicts: Int,
        flagged: Int,
        failures: Int,
    ) {
        inferenceRuns.incrementAndGet()
        inferenceNanosTotal.addAndGet(durationNanos)
        inferenceNanosLast.set(durationNanos)
        inferenceNanosMax.accumulateAndGet(durationNanos) { current, value ->
            maxOf(current, value)
        }
        windowsScored.addAndGet(windows.toLong())
        this.verdicts.addAndGet(verdicts.toLong())
        flaggedVerdicts.addAndGet(flagged.toLong())
        inferenceFailures.addAndGet(failures.toLong())
    }

    fun uploadSucceeded(bytes: Long) {
        uploads.incrementAndGet()
        bytesUploaded.addAndGet(bytes)
    }

    fun uploadFailed() {
        uploads.incrementAndGet()
        uploadFailures.incrementAndGet()
    }

    fun modelRefreshFinished(loaded: Int, failed: Int) {
        modelRefreshes.incrementAndGet()
        modelsLoaded.addAndGet(loaded.toLong())
        modelFailures.addAndGet(failed.toLong())
    }

    /** Names the live gauges are read from, so they cannot drift out of sync. */
    fun snapshot(recordingsActive: Int, watchesActive: Int): MetricsSnapshot = MetricsSnapshot(
        uptimeNanos = System.nanoTime() - startedAt,
        recordingsActive = recordingsActive,
        recordingsStarted = recordingsStarted.get(),
        recordingsStopped = recordingsStopped.get(),
        recordingsSaved = recordingsSaved.get(),
        recordingsDiscarded = recordingsDiscarded.get(),
        labelsWritten = labelsWritten.get(),
        packetsRecorded = packetsRecorded.get(),
        watchesActive = watchesActive,
        watchesStarted = watchesStarted.get(),
        inferenceRuns = inferenceRuns.get(),
        inferenceFailures = inferenceFailures.get(),
        inferenceNanosTotal = inferenceNanosTotal.get(),
        inferenceNanosMax = inferenceNanosMax.get(),
        inferenceNanosLast = inferenceNanosLast.get(),
        windowsScored = windowsScored.get(),
        verdicts = verdicts.get(),
        flaggedVerdicts = flaggedVerdicts.get(),
        uploads = uploads.get(),
        uploadFailures = uploadFailures.get(),
        bytesUploaded = bytesUploaded.get(),
        modelRefreshes = modelRefreshes.get(),
        modelsLoaded = modelsLoaded.get(),
        modelFailures = modelFailures.get(),
    )
}
