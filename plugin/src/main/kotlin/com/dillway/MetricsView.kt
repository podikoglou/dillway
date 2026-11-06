package com.dillway

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import java.util.Locale

/**
 * Chat rendering for one [MetricsSnapshot].
 *
 * One line per group keeps the view scannable: recording, inference, watch,
 * storage and model refreshes. Counts are separated by the same dim dot as the
 * rest of the plugin's output.
 */
object MetricsView {

    fun render(snapshot: MetricsSnapshot): Component {
        val text = Component.text()
            .append(
                mini(
                    "<gray>Dillway metrics <dark_gray>·</dark_gray> up <uptime></gray>",
                    Placeholder.unparsed("uptime", uptime(snapshot.uptimeNanos)),
                ),
            )

        text.append(Component.newline()).append(
            line(
                "Recording",
                gauge("${snapshot.recordingsActive} active", snapshot.recordingsActive > 0),
                fact("${count(snapshot.recordingsStarted)} started"),
                fact("${count(snapshot.recordingsSaved)} saved"),
                fact("${count(snapshot.recordingsDiscarded)} discarded"),
                fact("${count(snapshot.packetsRecorded)} packets", NamedTextColor.WHITE),
                fact("${count(snapshot.labelsWritten)} labels"),
            ),
        )

        text.append(Component.newline()).append(
            line(
                "Inference",
                fact("${count(snapshot.inferenceRuns)} runs"),
                fact("avg ${millis(averageInference(snapshot))}"),
                fact("max ${millis(snapshot.inferenceNanosMax)}"),
                fact("last ${millis(snapshot.inferenceNanosLast)}"),
                fact("${count(snapshot.windowsScored)} windows"),
                fact("${count(snapshot.flaggedVerdicts)} flagged", flaggedColor(snapshot)),
                failures(snapshot.inferenceFailures),
            ),
        )

        text.append(Component.newline()).append(
            line(
                "Watch",
                gauge("${snapshot.watchesActive} active", snapshot.watchesActive > 0),
                fact("${count(snapshot.watchesStarted)} started"),
            ),
        )

        text.append(Component.newline()).append(
            line(
                "Storage",
                fact("${count(snapshot.uploads)} uploads"),
                fact(bytes(snapshot.bytesUploaded)),
                failures(snapshot.uploadFailures),
            ),
        )

        text.append(Component.newline()).append(
            line(
                "Models",
                fact("${count(snapshot.modelRefreshes)} refreshes"),
                fact("${count(snapshot.modelsLoaded)} loaded"),
                failures(snapshot.modelFailures),
            ),
        )

        return text.build()
    }

    private fun averageInference(snapshot: MetricsSnapshot): Long =
        if (snapshot.inferenceRuns == 0L) {
            0
        } else {
            snapshot.inferenceNanosTotal / snapshot.inferenceRuns
        }

    private fun line(label: String, vararg facts: Component): Component {
        val text = Component.text()
        text.append(Component.text(label, NamedTextColor.GRAY))
        facts.forEach { fact ->
            text.append(dot()).append(fact)
        }
        return text.build()
    }

    private fun fact(value: String, color: NamedTextColor = NamedTextColor.AQUA): Component =
        Component.text(value, color)

    private fun gauge(value: String, active: Boolean): Component =
        Component.text(value, if (active) NamedTextColor.GREEN else NamedTextColor.DARK_GRAY)

    private fun failures(value: Long): Component {
        val text = if (value == 1L) "1 failure" else "${count(value)} failures"
        return fact(text, if (value > 0) NamedTextColor.RED else NamedTextColor.DARK_GRAY)
    }

    private fun flaggedColor(snapshot: MetricsSnapshot): NamedTextColor =
        if (snapshot.flaggedVerdicts > 0) NamedTextColor.RED else NamedTextColor.DARK_GRAY

    /** Compact uptime: the two largest units are enough. */
    private fun uptime(nanos: Long): String {
        val seconds = (nanos / 1_000_000_000L).coerceAtLeast(0)
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val minutes = (seconds % 3_600) / 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    private fun millis(nanos: Long): String =
        String.format(Locale.ROOT, "%.0f ms", nanos / 1_000_000.0)

    private fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = listOf("KB", "MB", "GB", "TB")
        var amount = value.toDouble()
        var unit = -1
        while (amount >= 1024 && unit < units.lastIndex) {
            amount /= 1024
            unit++
        }
        return String.format(Locale.ROOT, "%.1f %s", amount, units[unit])
    }
}
