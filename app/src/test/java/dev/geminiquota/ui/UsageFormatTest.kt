package dev.geminiquota.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class UsageFormatTest {

    private lateinit var originalTimeZone: TimeZone

    private val zone = ZoneId.of("UTC")

    @Before
    fun pinTimeZone() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun `a missing reset time has no state`() {
        assertEquals(UsageFormat.ResetKind.NONE, UsageFormat.resetKind(null))
    }

    @Test
    fun `a reset in the past is already resetting`() {
        val now = utc(2026, Calendar.OCTOBER, 25, 14, 30)
        assertEquals(UsageFormat.ResetKind.SOON, UsageFormat.resetKind(now, now))
        assertEquals(UsageFormat.ResetKind.SOON, UsageFormat.resetKind(now - 60_000, now))
    }

    @Test
    fun `a future reset is timed`() {
        val now = utc(2026, Calendar.OCTOBER, 25, 14, 30)
        assertEquals(UsageFormat.ResetKind.TIMED, UsageFormat.resetKind(now + 60_000, now))
    }

    @Test
    fun `formats the reset as a date and a 24 hour time`() {
        val reset = utc(2026, Calendar.OCTOBER, 25, 14, 30)
        assertEquals(
            "Sun 25 Oct · 14:30",
            normalize(UsageFormat.resetDateTime(reset, twentyFourHour = true, locale = Locale.US)),
        )
    }

    @Test
    fun `formats the reset as a date and a 12 hour time`() {
        val reset = utc(2026, Calendar.OCTOBER, 25, 14, 30)
        assertEquals(
            "Sun 25 Oct · 2:30 PM",
            normalize(UsageFormat.resetDateTime(reset, twentyFourHour = false, locale = Locale.US)),
        )
    }

    @Test
    fun `keeps a morning reset in the am half`() {
        val reset = utc(2026, Calendar.OCTOBER, 26, 9, 5)
        assertEquals(
            "Mon 26 Oct · 9:05 AM",
            normalize(UsageFormat.resetDateTime(reset, twentyFourHour = false, locale = Locale.US)),
        )
    }

    @Test
    fun `classifies a reset later today as today`() {
        val now = utc(2026, Calendar.OCTOBER, 25, 8, 0)
        val reset = utc(2026, Calendar.OCTOBER, 25, 14, 30)
        assertEquals(UsageFormat.ResetDay.TODAY, UsageFormat.resetDay(reset, now, zone))
    }

    @Test
    fun `classifies the next calendar day as tomorrow`() {
        val now = utc(2026, Calendar.OCTOBER, 25, 23, 30)
        val reset = utc(2026, Calendar.OCTOBER, 26, 3, 10)
        assertEquals(UsageFormat.ResetDay.TOMORROW, UsageFormat.resetDay(reset, now, zone))
    }

    @Test
    fun `classifies a distant reset as other`() {
        val now = utc(2026, Calendar.OCTOBER, 25, 12, 0)
        val reset = utc(2026, Calendar.OCTOBER, 27, 10, 10)
        assertEquals(UsageFormat.ResetDay.OTHER, UsageFormat.resetDay(reset, now, zone))
    }

    @Test
    fun `renders just the clock for near resets`() {
        val reset = utc(2026, Calendar.OCTOBER, 26, 3, 10)
        assertEquals("03:10", UsageFormat.resetClock(reset, twentyFourHour = true, locale = Locale.US))
        assertEquals(
            "3:10 AM",
            normalize(UsageFormat.resetClock(reset, twentyFourHour = false, locale = Locale.US)),
        )
    }

    /** CLDR uses a narrow no-break space before AM/PM on newer JDKs. */
    private fun normalize(text: String): String =
        text.replace('\u202F', ' ').replace('\u00A0', ' ')

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis
}
