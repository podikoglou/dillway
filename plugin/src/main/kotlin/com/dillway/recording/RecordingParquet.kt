package com.dillway.recording

import dev.hardwood.OutputFile
import dev.hardwood.metadata.LogicalType
import dev.hardwood.metadata.PhysicalType
import dev.hardwood.metadata.RepetitionType
import dev.hardwood.schema.FileSchema
import dev.hardwood.writer.ParquetFileWriter
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Writes recordings as Parquet with the flat, one-row-per-packet layout.
 *
 * The JSON format nested packets under the recording; a columnar file flattens
 * them, so the recording header columns repeat on every row. Collisions the
 * flattening creates are resolved with unambiguous names:
 *
 * - `type` on the recording → `recording_type`; `type` on the packet →
 *   `packet_type`.
 * - `timestamp` inside `AnimationData` → `animation_timestamp`; the packet's
 *   top-level timestamp stays `timestamp` (it is the one feature extraction
 *   reads).
 *
 * Every other key keeps its legacy JSON name (`player`, `x`, `yaw`, `f`,
 * `hasPos`, `itemInHandIndex`, the snake_case context fields), so the Python
 * side keeps working without renaming.
 *
 * A per-packet payload (`move`, animation, held-item) is `OPTIONAL` — present
 * only on the packets that carry it. `PlayerContext` is captured on every
 * packet, so its columns are `REQUIRED`.
 */
object RecordingParquet {

    val schema: FileSchema = FileSchema.builder("recording")
        // Recording header, repeated on every packet row.
        .addColumn("id", PhysicalType.INT64, RepetitionType.REQUIRED)
        .addColumn("started", PhysicalType.INT64, RepetitionType.REQUIRED)
        .addColumn("player", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED, LogicalType.string())
        .addColumn("recording_type", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED, LogicalType.string())
        // Packet row.
        .addColumn("packet_type", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED, LogicalType.string())
        .addColumn("timestamp", PhysicalType.INT64, RepetitionType.REQUIRED)
        // Move payload (position / position / look packets only).
        .addColumn("x", PhysicalType.DOUBLE, RepetitionType.OPTIONAL)
        .addColumn("y", PhysicalType.DOUBLE, RepetitionType.OPTIONAL)
        .addColumn("z", PhysicalType.DOUBLE, RepetitionType.OPTIONAL)
        // Location stores yaw and pitch as float; the schema keeps that width.
        .addColumn("yaw", PhysicalType.FLOAT, RepetitionType.OPTIONAL)
        .addColumn("pitch", PhysicalType.FLOAT, RepetitionType.OPTIONAL)
        // Legacy key names: the client-reported ground flag and the
        // position/look change markers.
        .addColumn("f", PhysicalType.BOOLEAN, RepetitionType.OPTIONAL)
        .addColumn("hasPos", PhysicalType.BOOLEAN, RepetitionType.OPTIONAL)
        .addColumn("hasLook", PhysicalType.BOOLEAN, RepetitionType.OPTIONAL)
        // Animation payload (arm-animation packets only).
        .addColumn("animation_timestamp", PhysicalType.INT64, RepetitionType.OPTIONAL)
        // Held-item payload (held-item-slot packets only).
        .addColumn("itemInHandIndex", PhysicalType.INT32, RepetitionType.OPTIONAL)
        // PlayerContext, captured at the time of the packet.
        .addColumn("fly_speed", PhysicalType.FLOAT, RepetitionType.REQUIRED)
        .addColumn("velocity_z", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
        .addColumn("location_x", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
        .addColumn("location_y", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
        .addColumn("location_direction_y", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
        .addColumn("location_direction_x", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
        .addColumn("velocity_x", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
        .addColumn("velocity_y", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
        .addColumn("saturation", PhysicalType.FLOAT, RepetitionType.REQUIRED)
        .addColumn("location_direction_z", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
        .addColumn("location_z", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
        .addColumn("food_level", PhysicalType.INT32, RepetitionType.REQUIRED)
        .addColumn("eye_location_yaw", PhysicalType.FLOAT, RepetitionType.REQUIRED)
        .addColumn("walk_speed", PhysicalType.FLOAT, RepetitionType.REQUIRED)
        .addColumn("eye_height", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
        .addColumn("allow_flight", PhysicalType.BOOLEAN, RepetitionType.REQUIRED)
        .addColumn("exhaustion", PhysicalType.FLOAT, RepetitionType.REQUIRED)
        .addColumn("health", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
        .addColumn("eye_location_pitch", PhysicalType.FLOAT, RepetitionType.REQUIRED)
        .addColumn("location_pitch", PhysicalType.FLOAT, RepetitionType.REQUIRED)
        .addColumn("fall_distance", PhysicalType.FLOAT, RepetitionType.REQUIRED)
        .addColumn("location_yaw", PhysicalType.FLOAT, RepetitionType.REQUIRED)
        .addColumn("health_scale", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
        .addColumn("sprinting", PhysicalType.BOOLEAN, RepetitionType.REQUIRED)
        .addColumn("eye_location_y", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
        .addColumn("eye_location_x", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
        .addColumn("flying", PhysicalType.BOOLEAN, RepetitionType.REQUIRED)
        .addColumn("eye_location_z", PhysicalType.DOUBLE, RepetitionType.REQUIRED)
        .addColumn("sneaking", PhysicalType.BOOLEAN, RepetitionType.REQUIRED)
        .addColumn("on_ground", PhysicalType.BOOLEAN, RepetitionType.REQUIRED)
        .build()

    /**
     * Writes one recording to a Parquet sink. The output holds one row per
     * packet; the context columns carry the context captured with that packet.
     * [OutputFile] is the destination abstraction; callers wanting a plain
     * stream use the [OutputStream] overload.
     */
    fun writeRecording(destination: OutputFile, recording: Recording) {
        ParquetFileWriter.create(destination, schema).use { writer ->
            val rows = writer.rowWriter()
            for (packet in recording.packets) {
                rows.writeRow { row ->
                    row.setLong("id", recording.id)
                        .setLong("started", recording.started)
                        .setString("player", recording.player)
                        .setString("recording_type", recording.label.uppercase(Locale.ROOT))
                        .setString("packet_type", packet.type)
                        .setLong("timestamp", packet.timestamp)

                    when (val data = packet.data) {
                        is MoveData -> row.setDouble("x", data.x)
                            .setDouble("y", data.y)
                            .setDouble("z", data.z)
                            .setFloat("yaw", data.yaw)
                            .setFloat("pitch", data.pitch)
                            .setBoolean("f", data.onGround)
                            .setBoolean("hasPos", data.hasPos)
                            .setBoolean("hasLook", data.hasLook)
                        is AnimationData -> row.setLong("animation_timestamp", data.timestamp)
                        is HeldItemData -> row.setInt("itemInHandIndex", data.itemInHandIndex)
                    }

                    val c = packet.context
                    row.setFloat("fly_speed", c.flySpeed)
                        .setDouble("velocity_z", c.velocityZ)
                        .setDouble("location_x", c.locationX)
                        .setDouble("location_y", c.locationY)
                        .setDouble("location_direction_y", c.locationDirectionY)
                        .setDouble("location_direction_x", c.locationDirectionX)
                        .setDouble("velocity_x", c.velocityX)
                        .setDouble("velocity_y", c.velocityY)
                        .setFloat("saturation", c.saturation)
                        .setDouble("location_direction_z", c.locationDirectionZ)
                        .setDouble("location_z", c.locationZ)
                        .setInt("food_level", c.foodLevel)
                        .setFloat("eye_location_yaw", c.eyeLocationYaw)
                        .setFloat("walk_speed", c.walkSpeed)
                        .setDouble("eye_height", c.eyeHeight)
                        .setBoolean("allow_flight", c.allowFlight)
                        .setFloat("exhaustion", c.exhaustion)
                        .setDouble("health", c.health)
                        .setFloat("eye_location_pitch", c.eyeLocationPitch)
                        .setFloat("location_pitch", c.locationPitch)
                        .setFloat("fall_distance", c.fallDistance)
                        .setFloat("location_yaw", c.locationYaw)
                        .setDouble("health_scale", c.healthScale)
                        .setBoolean("sprinting", c.sprinting)
                        .setDouble("eye_location_y", c.eyeLocationY)
                        .setDouble("eye_location_x", c.eyeLocationX)
                        .setBoolean("flying", c.flying)
                        .setDouble("eye_location_z", c.eyeLocationZ)
                        .setBoolean("sneaking", c.sneaking)
                        .setBoolean("on_ground", c.onGround)
                }
            }
        }
    }

    /**
     * Writes one recording to [file]. Writes go to a temporary sibling and are
     * renamed onto the target when the writer closes, so a failed write leaves
     * the previous file at the target untouched.
     */
    fun writeRecording(file: File, recording: Recording) =
        writeRecording(OutputFile.of(file.toPath()), recording)

    /**
     * Writes one recording into [output]. The stream is flushed, not closed —
     * lifetime stays with the caller. A failed or discarded write leaves bytes
     * behind in the stream; with no footer at the end, consumers can tell the
     * recording is unusable.
     */
    fun writeRecording(output: OutputStream, recording: Recording) =
        writeRecording(OutputStreamOutputFile(output), recording)
}

/**
 * Turns any [OutputStream] into a Hardwood [OutputFile] sink. Hardwood produces
 * the container front to back and never seeks backward, so a stream is enough.
 *
 * Writes are synchronized: the writer copies column chunks and flushes pages
 * from several of its own threads, and [OutputStream] itself is not
 * thread-safe.
 */
private class OutputStreamOutputFile(private val output: OutputStream) : OutputFile {

    /** Bytes handed to the stream so far, per the [OutputFile.position] contract. */
    @Volatile
    private var position: Long = 0

    override fun create() {}

    override fun write(data: ByteBuffer) {
        val length = data.remaining()
        if (length == 0) return
        synchronized(output) {
            val chunk = ByteArray(minOf(length, CHUNK_BYTES))
            while (data.hasRemaining()) {
                val take = minOf(data.remaining(), chunk.size)
                data.get(chunk, 0, take)
                output.write(chunk, 0, take)
            }
            position += length
        }
    }

    override fun position(): Long = position

    override fun close() {
        synchronized(output) { output.flush() }
    }

    override fun discard() {
        // Cannot unwrite what already reached the stream; the missing footer
        // makes a discarded write visible to any consumer.
    }

    private companion object {
        private const val CHUNK_BYTES = 64 * 1024
    }
}
