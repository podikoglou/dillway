package com.dillway.agent.recording

import dev.hardwood.InputFile
import dev.hardwood.OutputFile
import dev.hardwood.reader.ParquetFileReader
import dev.hardwood.reader.RowReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Writes a recording and reads it back through the row reader, locking the
 * one-row-per-packet layout and the null pattern of the payload columns.
 */
class RecordingParquetTest {

    /** Three packets, one per payload kind — move, arm animation, held item. */
    private val recording = Recording(
        id = 1674590916014,
        started = 1674590916014,
        player = "78de5f59-ba63-45af-a9a8-8d111e155c29",
        label = "legit",
        packets = listOf(
            RecordedPacket(
                type = "PacketPlayInPosition",
                timestamp = 1674590916019,
                data = MoveData(
                    x = 50.922640679476665,
                    y = 201.0,
                    z = 39.91171675963879,
                    yaw = 0.0f,
                    pitch = 0.0f,
                    onGround = true,
                    hasPos = true,
                    hasLook = false,
                ),
                context = moveContext(onGround = true),
            ),
            RecordedPacket(
                type = "PacketPlayInArmAnimation",
                timestamp = 1674590919969,
                data = AnimationData(timestamp = 1674590919969),
                context = moveContext(onGround = false),
            ),
            RecordedPacket(
                type = "PacketPlayInHeldItemSlot",
                timestamp = 1674590920231,
                data = HeldItemData(itemInHandIndex = 2),
                context = moveContext(onGround = false),
            ),
        ),
    )

    @Test
    fun `recording round trips through parquet`() {
        val dir = Files.createTempDirectory("dillway-test")
        val file = dir.resolve("recording.parquet").toFile()

        RecordingParquet.writeRecording(file, recording)

        ParquetFileReader.open(InputFile.of(file.toPath())).use { reader ->
            reader.rowReader().use { rows ->
                // Row 0: the move packet carries the move payload.
                rows.next()
                assertHeader(rows)
                assertEquals(1674590916019L, rows.getLong("timestamp"))
                assertEquals(50.922640679476665, rows.getDouble("x"))
                assertEquals(201.0, rows.getDouble("y"))
                assertEquals(39.91171675963879, rows.getDouble("z"))
                assertEquals(0.0f, rows.getFloat("yaw"))
                assertEquals(0.0f, rows.getFloat("pitch"))
                assertEquals(true, rows.getBoolean("f"))
                assertEquals(true, rows.getBoolean("hasPos"))
                assertEquals(false, rows.getBoolean("hasLook"))
                assertTrue(rows.isNull("animation_timestamp"))
                assertTrue(rows.isNull("itemInHandIndex"))
                assertContext(rows)
                assertEquals(true, rows.getBoolean("on_ground"))

                // Row 1: the animation packet carries only its timestamp.
                rows.next()
                assertHeader(rows)
                assertEquals(1674590919969L, rows.getLong("timestamp"))
                assertTrue(rows.isNull("x"))
                assertTrue(rows.isNull("f"))
                assertEquals(1674590919969L, rows.getLong("animation_timestamp"))
                assertTrue(rows.isNull("itemInHandIndex"))
                assertContext(rows)

                // Row 2: the held-item packet carries only the slot index.
                rows.next()
                assertHeader(rows)
                assertEquals(1674590920231L, rows.getLong("timestamp"))
                assertTrue(rows.isNull("x"))
                assertTrue(rows.isNull("animation_timestamp"))
                assertEquals(2, rows.getInt("itemInHandIndex"))
                assertContext(rows)

                // No further rows: three packets, three rows.
                assertEquals(false, rows.hasNext())
            }
        }
    }

    @Test
    fun `several recordings share one file with their own headers`() {
        val dir = Files.createTempDirectory("dillway-shard")
        val file = dir.resolve("compacted-1-2.parquet").toFile()
        val second = recording.copy(
            id = recording.id + 1,
            started = recording.id + 1,
            player = "11111111-2222-3333-4444-555555555555",
            label = "speed",
            packets = recording.packets.take(1),
        )

        RecordingParquet.writeRecordings(file, listOf(recording, second))

        ParquetFileReader.open(InputFile.of(file.toPath())).use { reader ->
            reader.rowReader().use { rows ->
                // The first recording keeps its header for all of its rows.
                repeat(3) {
                    assertTrue(rows.hasNext())
                    rows.next()
                    assertHeader(rows)
                }

                // The next row starts the second recording.
                assertTrue(rows.hasNext())
                rows.next()
                assertEquals(recording.id + 1, rows.getLong("id"))
                assertEquals(recording.id + 1, rows.getLong("started"))
                assertEquals("11111111-2222-3333-4444-555555555555", rows.getString("player"))
                assertEquals("SPEED", rows.getString("recording_type"))
                assertEquals(false, rows.hasNext())
            }
        }
    }

    @Test
    fun `recording survives a write and read round trip`() {
        val dir = Files.createTempDirectory("dillway-round-trip")
        val file = dir.resolve("${recording.id}.parquet").toFile()

        RecordingParquet.writeRecording(file, recording)

        assertEquals(recording, RecordingParquet.readRecording(file))
    }

    @Test
    fun `empty recording keeps its id from the file name`() {
        val dir = Files.createTempDirectory("dillway-empty")
        val file = dir.resolve("${recording.id}.parquet").toFile()

        RecordingParquet.writeRecording(file, recording.copy(packets = emptyList()))

        val read = RecordingParquet.readRecording(file)
        assertEquals(recording.id, read.id)
        assertTrue(read.packets.isEmpty())
    }

    /**
     * The only part of the stream path that is ours is the adapter in
     * `RecordingParquet`, so this test locks just the passthrough: the bytes
     * it hands to [OutputStream] match what Hardwood's own in-memory sink
     * holds, and the stream is left open (`close()` flushes, never closes).
     * Container validity is the library's business and its own tests.
     */
    @Test
    fun `output stream sink receives the same bytes hardwood's own sink holds`() {
        val viaStream = ByteArrayOutputStream()
        RecordingParquet.writeRecording(viaStream, recording)

        val inMemory = OutputFile.inMemory()
        RecordingParquet.writeRecording(inMemory, recording)

        val expected = inMemory.buffer()
        val expectedBytes = ByteArray(expected.remaining()).also { expected.get(it) }

        assertTrue(
            expectedBytes.contentEquals(viaStream.toByteArray()),
            "streamed bytes must match the in-memory file byte for byte",
        )
    }

    /** Header columns repeat identically on every row. */
    private fun assertHeader(rows: RowReader) {
        assertEquals(1674590916014L, rows.getLong("id"))
        assertEquals(1674590916014L, rows.getLong("started"))
        assertEquals("78de5f59-ba63-45af-a9a8-8d111e155c29", rows.getString("player"))
        assertEquals("LEGIT", rows.getString("recording_type"))
    }

    /** Context columns are REQUIRED, so they land on every row. */
    private fun assertContext(rows: RowReader) {
        assertEquals(0.1f, rows.getFloat("fly_speed"))
        assertEquals(0.0, rows.getDouble("velocity_z"))
        assertEquals(51.19542895656041, rows.getDouble("location_x"))
        assertEquals(5.0f, rows.getFloat("saturation"))
        assertEquals(20, rows.getInt("food_level"))
        assertEquals(1.62, rows.getDouble("eye_height"))
        assertEquals(20.0, rows.getDouble("health"))
        assertEquals(0.0f, rows.getFloat("fall_distance"))
        assertEquals(20.0, rows.getDouble("health_scale"))
        assertEquals(true, rows.getBoolean("sprinting"))
        assertEquals(202.62, rows.getDouble("eye_location_y"))
        assertEquals(false, rows.getBoolean("flying"))
    }

    private fun moveContext(onGround: Boolean) = PlayerContext(
        flySpeed = 0.1f,
        velocityZ = 0.0,
        locationX = 51.19542895656041,
        locationY = 201.0,
        locationDirectionY = -0.19956846917130244,
        locationDirectionX = -0.9784812622903266,
        velocityX = 0.0,
        velocityY = -0.0784000015258789,
        saturation = 5.0f,
        locationDirectionZ = 0.05241035641313858,
        locationZ = 39.90488002539233,
        foodLevel = 20,
        eyeLocationYaw = 86.934f,
        walkSpeed = 0.2f,
        eyeHeight = 1.62,
        allowFlight = true,
        exhaustion = 0.0f,
        health = 20.0,
        eyeLocationPitch = 11.511725f,
        locationPitch = 11.511725f,
        fallDistance = 0.0f,
        locationYaw = 86.934f,
        healthScale = 20.0,
        sprinting = true,
        eyeLocationY = 202.62,
        eyeLocationX = 51.19542895656041,
        flying = false,
        eyeLocationZ = 39.90488002539233,
        sneaking = false,
        onGround = onGround,
    )
}
