package com.dillway.agent

import java.util.Locale

/** Number formatting shared by the metrics view. */
internal object MetricFormat {

    /** Compact uptime: the two largest units are enough. */
    fun uptime(nanos: Long): String {
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

    fun millis(nanos: Long): String =
        String.format(Locale.ROOT, "%.0f ms", nanos / 1_000_000.0)

    fun bytes(value: Long): String {
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
