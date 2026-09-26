package com.supervideo.core.util

import kotlin.math.roundToLong

object Durations {

    /** "1 h 5 min 3 s" style, rounded to the second. */
    fun period(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0) / 1000.0).roundToLong()
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return buildList {
            if (hours > 0) add("$hours h")
            if (hours > 0 || minutes > 0) add("$minutes min")
            add("$seconds s")
        }.joinToString(" ")
    }

    /** `m:ss.mmm` (or `h:mm:ss.mmm`) for a timestamp in microseconds. */
    fun timestamp(us: Long): String {
        val totalMillis = (us.coerceAtLeast(0) + 500) / 1000
        val millis = totalMillis % 1000
        val totalSeconds = totalMillis / 1000
        val seconds = totalSeconds % 60
        val minutes = totalSeconds / 60 % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) "%d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
        else "%d:%02d.%03d".format(minutes, seconds, millis)
    }

    /** Parses `[[h:]m:]s[.fraction]` into microseconds; null when malformed. */
    fun parseTimestamp(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(':')
        if (parts.size > 3) return null
        val secondsPart = parts.last().toBigDecimalOrNull() ?: return null
        if (secondsPart.signum() < 0) return null
        var total = secondsPart.movePointRight(6)
        val multipliers = listOf(60L, 3600L)
        for ((i, part) in parts.dropLast(1).reversed().withIndex()) {
            val value = part.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            total += java.math.BigDecimal.valueOf(value * multipliers[i] * 1_000_000)
        }
        return total.toLong()
    }
}
