package com.dillway.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class MetricsTest {

    @Test
    fun `a fresh snapshot has zero counters and the live gauges`() {
        val snapshot = Metrics().snapshot(recordingsActive = 2, watchesActive = 1)

        assertEquals(2, snapshot.recordingsActive)
        assertEquals(1, snapshot.watchesActive)
        assertEquals(0L, snapshot.recordingsStarted)
        assertEquals(0L, snapshot.recordingsSaved)
        assertEquals(0L, snapshot.packetsRecorded)
        assertEquals(0L, snapshot.inferenceRuns)
        assertEquals(0L, snapshot.uploads)
        assertEquals(0L, snapshot.modelRefreshes)
    }

    @Test
    fun `recording and watch counters accumulate`() {
        val metrics = Metrics()

        metrics.recordingStarted()
        metrics.recordingStarted()
        metrics.packetRecorded()
        metrics.packetRecorded()
        metrics.packetRecorded()
        metrics.recordingStopped()
        metrics.recordingSaved()
        metrics.labelWritten()
        metrics.labelWritten()
        metrics.recordingDiscarded()
        metrics.watchStarted()

        val snapshot = metrics.snapshot(recordingsActive = 1, watchesActive = 1)

        assertEquals(1, snapshot.recordingsActive)
        assertEquals(2L, snapshot.recordingsStarted)
        assertEquals(1L, snapshot.recordingsStopped)
        assertEquals(1L, snapshot.recordingsSaved)
        assertEquals(2L, snapshot.labelsWritten)
        assertEquals(1L, snapshot.recordingsDiscarded)
        assertEquals(3L, snapshot.packetsRecorded)
        assertEquals(1L, snapshot.watchesStarted)
    }

    @Test
    fun `inference counters keep totals, peak, last and verdicts`() {
        val metrics = Metrics()

        metrics.inferenceFinished(
            durationNanos = 10_000_000,
            windows = 3,
            verdicts = 2,
            flagged = 1,
            failures = 0,
        )
        metrics.inferenceFinished(
            durationNanos = 30_000_000,
            windows = 4,
            verdicts = 2,
            flagged = 0,
            failures = 1,
        )

        val snapshot = metrics.snapshot(recordingsActive = 0, watchesActive = 0)

        assertEquals(2L, snapshot.inferenceRuns)
        assertEquals(40_000_000L, snapshot.inferenceNanosTotal)
        assertEquals(30_000_000L, snapshot.inferenceNanosMax)
        assertEquals(30_000_000L, snapshot.inferenceNanosLast)
        assertEquals(7L, snapshot.windowsScored)
        assertEquals(4L, snapshot.verdicts)
        assertEquals(1L, snapshot.flaggedVerdicts)
        assertEquals(1L, snapshot.inferenceFailures)
    }

    @Test
    fun `storage and refresh counters accumulate`() {
        val metrics = Metrics()

        metrics.uploadSucceeded(1024)
        metrics.uploadSucceeded(2048)
        metrics.uploadFailed()
        metrics.modelRefreshFinished(loaded = 3, failed = 1)
        metrics.modelRefreshFinished(loaded = 2, failed = 0)

        val snapshot = metrics.snapshot(recordingsActive = 0, watchesActive = 0)

        assertEquals(3L, snapshot.uploads)
        assertEquals(1L, snapshot.uploadFailures)
        assertEquals(3072L, snapshot.bytesUploaded)
        assertEquals(2L, snapshot.modelRefreshes)
        assertEquals(5L, snapshot.modelsLoaded)
        assertEquals(1L, snapshot.modelFailures)
    }

    @Test
    fun `uptime shows the two largest units`() {
        assertEquals("0s", MetricFormat.uptime(0))
        assertEquals("42s", MetricFormat.uptime(seconds(42)))
        assertEquals("1m 5s", MetricFormat.uptime(seconds(65)))
        assertEquals("1h 0m", MetricFormat.uptime(seconds(3600)))
        assertEquals("1h 2m", MetricFormat.uptime(seconds(3720)))
        assertEquals("1d 1h", MetricFormat.uptime(seconds(90_000)))
    }

    @Test
    fun `milliseconds round to the nearest millisecond`() {
        assertEquals("12 ms", MetricFormat.millis(12_400_000))
        assertEquals("2 ms", MetricFormat.millis(1_500_000))
        assertEquals("0 ms", MetricFormat.millis(0))
    }

    @Test
    fun `bytes pick the largest unit`() {
        assertEquals("0 B", MetricFormat.bytes(0))
        assertEquals("1023 B", MetricFormat.bytes(1023))
        assertEquals("1.0 KB", MetricFormat.bytes(1024))
        assertEquals("1.5 KB", MetricFormat.bytes(1536))
        assertEquals("1.0 MB", MetricFormat.bytes(1024L * 1024))
        assertEquals("3.3 MB", MetricFormat.bytes(3_500_000))
        assertEquals("1.0 GB", MetricFormat.bytes(1024L * 1024 * 1024))
    }

    private fun seconds(value: Long): Long = value * 1_000_000_000L
}
