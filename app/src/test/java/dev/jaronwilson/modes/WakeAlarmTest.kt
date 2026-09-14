package dev.jaronwilson.modes

import dev.jaronwilson.modes.alarm.WakeAlarm
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class WakeAlarmTest {

    private val zone = ZoneId.of("America/New_York")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): ZonedDateTime =
        LocalDateTime.of(y, m, d, h, min).atZone(zone)

    private fun instant(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        at(y, m, d, h, min).toInstant().toEpochMilli()

    private val everyDay = 0b1111111

    @Test
    fun `later today when now is before the wake time`() {
        // Monday 2026-09-14, now 05:00, wake 07:00 -> today 07:00.
        val now = at(2026, 9, 14, 5, 0)
        assertEquals(instant(2026, 9, 14, 7, 0), WakeAlarm.nextOccurrence(7 * 60, everyDay, now))
    }

    @Test
    fun `tomorrow when the wake time already passed today`() {
        val now = at(2026, 9, 14, 8, 0)
        assertEquals(instant(2026, 9, 15, 7, 0), WakeAlarm.nextOccurrence(7 * 60, everyDay, now))
    }

    @Test
    fun `weekday-only mask skips the weekend`() {
        // Friday 2026-09-18 after wake -> next is Monday 2026-09-21.
        val weekdays = 0b0011111 // Mon..Fri (bits 0..4)
        val now = at(2026, 9, 18, 9, 0)
        assertEquals(instant(2026, 9, 21, 7, 0), WakeAlarm.nextOccurrence(7 * 60, weekdays, now))
    }

    @Test
    fun `an empty mask is treated as every day`() {
        val now = at(2026, 9, 14, 8, 0)
        assertEquals(instant(2026, 9, 15, 7, 0), WakeAlarm.nextOccurrence(7 * 60, 0, now))
    }
}
