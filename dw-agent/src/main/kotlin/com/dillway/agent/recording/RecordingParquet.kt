package com.dillway.agent.recording

import dev.hardwood.InputFile
import dev.hardwood.OutputFile
import dev.hardwood.metadata.LogicalType
import dev.hardwood.metadata.PhysicalType
import dev.hardwood.metadata.RepetitionType
import dev.hardwood.reader.ParquetFileReader
import dev.hardwood.reader.RowReader
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
    fun writeRecording(destination: OutputFile, recording: Recording) =
        writeRecordings(destination, listOf(recording))

    /**
     * Writes several recordings into one Parquet sink, keeping the same flat
     * row-per-packet layout. Compaction uses this to turn many session files
     * into one shard; the repeated header columns tell a reader where one
     * recording ends and the next begins, so no extra marker is needed.
     */
    fun writeRecordings(destination: OutputFile, recordings: Iterable<Recording>) {
        ParquetFileWriter.create(destination, schema).use { writer ->
            val rows = writer.rowWriter()
            for (recording in recordings) {
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
                            // A payload-less packet leaves every payload column null.
                            NoPayload -> {}
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
    }

    /**
     * Writes one recording to [file]. Writes go to a temporary sibling and are
     * renamed onto the target when the writer closes, so a failed write leaves
     * the previous file at the target untouched.
     */
    fun writeRecording(file: File, recording: Recording) =
        writeRecording(OutputFile.of(file.toPath()), recording)

    /** Writes several recordings into [file] as one shard. */
    fun writeRecordings(file: File, recordings: Iterable<Recording>) =
        writeRecordings(OutputFile.of(file.toPath()), recordings)

    /**
     * Writes one recording into [output]. The stream is flushed, not closed —
     * lifetime stays with the caller. A failed or discarded write leaves bytes
     * behind in the stream; with no footer at the end, consumers can tell the
     * recording is unusable.
     */
    fun writeRecording(output: OutputStream, recording: Recording) =
        writeRecording(OutputStreamOutputFile(output), recording)

    /**
     * Reads a recording written by [writeRecording].
     *
     * The flat schema is self-describing, so the payload kind comes from which
     * payload column is non-null, not from a hard-coded packet-type list. A row
     * with no payload columns is a legacy packet kind the plugin does not
     * model; it keeps its type, timestamp, and context. An empty file keeps its
     * id and start time from the file name.
     */
    fun readRecording(file: File): Recording {
        ParquetFileReader.open(InputFile.of(file.toPath())).use { reader ->
            reader.rowReader().use { rows ->
                var id = 0L
                var started = 0L
                var player = ""
                var label = ""
                val packets = ArrayList<RecordedPacket>()

                while (rows.hasNext()) {
                    rows.next()
                    id = rows.getLong("id")
                    started = rows.getLong("started")
                    player = rows.getString("player")
                    label = rows.getString("recording_type").lowercase(Locale.ROOT)
                    packets += readPacket(rows)
                }

                if (packets.isEmpty()) {
                    // No rows means no header columns either; recover what the
                    // file name can tell us so the recording still resolves.
                    id = file.nameWithoutExtension.toLongOrNull() ?: 0L
                    started = id
                }

                return Recording(
                    id = id,
                    started = started,
                    player = player,
                    label = label,
                    packets = packets,
                )
            }
        }
    }

    private fun readPacket(rows: RowReader): RecordedPacket {
        val data = when {
            !rows.isNull("x") -> MoveData(
                x = rows.getDouble("x"),
                y = rows.getDouble("y"),
                z = rows.getDouble("z"),
                yaw = rows.getFloat("yaw"),
                pitch = rows.getFloat("pitch"),
                onGround = rows.getBoolean("f"),
                hasPos = rows.getBoolean("hasPos"),
                hasLook = rows.getBoolean("hasLook"),
            )

            !rows.isNull("animation_timestamp") ->
                AnimationData(timestamp = rows.getLong("animation_timestamp"))

            !rows.isNull("itemInHandIndex") ->
                HeldItemData(itemInHandIndex = rows.getInt("itemInHandIndex"))

            else -> NoPayload
        }

        return RecordedPacket(
            type = rows.getString("packet_type"),
            timestamp = rows.getLong("timestamp"),
            data = data,
            context = PlayerContext(
                flySpeed = rows.getFloat("fly_speed"),
                velocityZ = rows.getDouble("velocity_z"),
                locationX = rows.getDouble("location_x"),
                locationY = rows.getDouble("location_y"),
                locationDirectionY = rows.getDouble("location_direction_y"),
                locationDirectionX = rows.getDouble("location_direction_x"),
                velocityX = rows.getDouble("velocity_x"),
                velocityY = rows.getDouble("velocity_y"),
                saturation = rows.getFloat("saturation"),
                locationDirectionZ = rows.getDouble("location_direction_z"),
                locationZ = rows.getDouble("location_z"),
                foodLevel = rows.getInt("food_level"),
                eyeLocationYaw = rows.getFloat("eye_location_yaw"),
                walkSpeed = rows.getFloat("walk_speed"),
                eyeHeight = rows.getDouble("eye_height"),
                allowFlight = rows.getBoolean("allow_flight"),
                exhaustion = rows.getFloat("exhaustion"),
                health = rows.getDouble("health"),
                eyeLocationPitch = rows.getFloat("eye_location_pitch"),
                locationPitch = rows.getFloat("location_pitch"),
                fallDistance = rows.getFloat("fall_distance"),
                locationYaw = rows.getFloat("location_yaw"),
                healthScale = rows.getDouble("health_scale"),
                sprinting = rows.getBoolean("sprinting"),
                eyeLocationY = rows.getDouble("eye_location_y"),
                eyeLocationX = rows.getDouble("eye_location_x"),
                flying = rows.getBoolean("flying"),
                eyeLocationZ = rows.getDouble("eye_location_z"),
                sneaking = rows.getBoolean("sneaking"),
                onGround = rows.getBoolean("on_ground"),
            ),
        )
    }
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
