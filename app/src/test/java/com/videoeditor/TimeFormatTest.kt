package com.videoeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeFormatTest {

    // ── formatTime ────────────────────────────────────────────────────────────

    @Test fun formatTime_zero() = assertEquals("0:00", TimeFormat.formatTime(0))
    @Test fun formatTime_seconds() = assertEquals("0:45", TimeFormat.formatTime(45_000))
    @Test fun formatTime_minutes() = assertEquals("2:03", TimeFormat.formatTime(123_000))
    @Test fun formatTime_hours() = assertEquals("1:02:03", TimeFormat.formatTime(3_723_000))
    @Test fun formatTime_subSecondTruncated() = assertEquals("0:00", TimeFormat.formatTime(999))

    // ── formatMsec ────────────────────────────────────────────────────────────

    @Test fun formatMsec_zero() = assertEquals("0:00.000", TimeFormat.formatMsec(0))
    @Test fun formatMsec_milliseconds() = assertEquals("0:00.123", TimeFormat.formatMsec(123))
    @Test fun formatMsec_seconds() = assertEquals("0:45.000", TimeFormat.formatMsec(45_000))
    @Test fun formatMsec_minutes() = assertEquals("2:03.456", TimeFormat.formatMsec(123_456))
    @Test fun formatMsec_hourBoundary() = assertEquals("1:00:00.000", TimeFormat.formatMsec(3_600_000))
    @Test fun formatMsec_hoursMinutesSeconds() =
        assertEquals("1:02:03.456", TimeFormat.formatMsec(3_723_456))
    @Test fun formatMsec_negativeClampedToZero() = assertEquals("0:00.000", TimeFormat.formatMsec(-1))

    // ── parseTime ─────────────────────────────────────────────────────────────

    private fun parse(s: String, maxMs: Long = Long.MAX_VALUE) = TimeFormat.parseTime(s, maxMs)

    @Test fun parseTime_zero() = assertEquals(0L, parse("0:00.000"))
    @Test fun parseTime_mmssmmm() = assertEquals(123_456L, parse("2:03.456"))
    @Test fun parseTime_hhmmssmmm() = assertEquals(3_723_456L, parse("1:02:03.456"))
    @Test fun parseTime_noMillis() = assertEquals(45_000L, parse("0:45"))
    @Test fun parseTime_decimalSeconds() = assertEquals(1_500L, parse("1.5"))
    @Test fun parseTime_clampedToMax() = assertEquals(5_000L, parse("10:00.000", maxMs = 5_000L))
    @Test fun parseTime_clampedToZero() = assertEquals(0L, parse("-1", maxMs = 60_000L))
    @Test fun parseTime_emptyString() = assertNull(parse(""))
    @Test fun parseTime_blankString() = assertNull(parse("   "))
    @Test fun parseTime_garbage() = assertNull(parse("abc"))

    // ── Round-trips ───────────────────────────────────────────────────────────

    @Test fun roundTrip_msec() {
        val samples = listOf(0L, 1L, 999L, 1_000L, 59_999L, 60_000L, 3_599_999L, 3_600_000L)
        for (ms in samples) {
            val formatted = TimeFormat.formatMsec(ms)
            val parsed = parse(formatted, Long.MAX_VALUE)
            assertEquals("round-trip failed for $ms → \"$formatted\"", ms, parsed)
        }
    }
}
