package cz.insanebadpc.insanemarket.util

import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

object ByteFormat {
    fun bytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val group = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
        val value = bytes / 1024.0.pow(group.toDouble())
        return String.format(Locale.US, "%.1f %s", value, units[group])
    }
}
