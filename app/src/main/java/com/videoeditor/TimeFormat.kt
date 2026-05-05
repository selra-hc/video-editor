package com.videoeditor

/**
 * Pure time-formatting and parsing helpers shared by MainActivity, VideoMetadataLoader, and
 * TrimRangeView callbacks.  All functions are stateless and have no Android dependencies, making
 * them straightforward to unit-test on the JVM.
 */
object TimeFormat {

    /** Format milliseconds as `M:SS` or `H:MM:SS` (used for the playback time display). */
    fun formatTime(ms: Long): String {
        val s = ms / 1_000L
        val h = s / 3_600L
        val m = (s % 3_600L) / 60L
        val sec = s % 60L
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec)
               else       "%d:%02d".format(m, sec)
    }

    /** Format milliseconds as `M:SS.mmm` or `H:MM:SS.mmm` (used for trim input/display). */
    fun formatMsec(ms: Long): String {
        val t    = ms.coerceAtLeast(0L)
        val h    = t / 3_600_000L
        val min  = (t % 3_600_000L) / 60_000L
        val sec  = (t % 60_000L) / 1_000L
        val msec = t % 1_000L
        return if (h > 0) "%d:%02d:%02d.%03d".format(h, min, sec, msec)
               else       "%d:%02d.%03d".format(min, sec, msec)
    }

    /**
     * Parse a user-entered time string into milliseconds, clamped to [0, maxMs].
     * Accepts `M:SS.mmm`, `H:MM:SS.mmm`, `M:SS`, or a plain decimal seconds value.
     * Returns null if the string cannot be parsed.
     */
    fun parseTime(text: String, maxMs: Long): Long? {
        val s = text.trim().takeIf { it.isNotEmpty() } ?: return null
        return try {
            if (':' in s) {
                val colonParts = s.split(':')
                val lastPart   = colonParts.last()
                val dotIdx     = lastPart.indexOf('.')
                val secStr     = if (dotIdx >= 0) lastPart.substring(0, dotIdx) else lastPart
                val msStr      = if (dotIdx >= 0) lastPart.substring(dotIdx + 1) else ""
                val msVal      = msStr.padEnd(3, '0').take(3).toLong()
                val secVal     = secStr.toLong()
                val totalMin   = colonParts.dropLast(1).fold(0L) { acc, v -> acc * 60L + v.toLong() }
                (totalMin * 60_000L + secVal * 1_000L + msVal).coerceIn(0L, maxMs)
            } else {
                (s.toDouble() * 1_000.0).toLong().coerceIn(0L, maxMs)
            }
        } catch (_: Exception) { null }
    }
}
