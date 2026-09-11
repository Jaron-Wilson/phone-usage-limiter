package dev.jaronwilson.modes

import dev.jaronwilson.modes.core.model.Mode
import dev.jaronwilson.modes.core.model.TimeRule
import dev.jaronwilson.modes.schedule.DigestWindow
import dev.jaronwilson.modes.schedule.activeAt
import dev.jaronwilson.modes.schedule.edgesAfter
import dev.jaronwilson.modes.schedule.nextEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    /** 2026-09-14 is a Monday. */
    private fun at(day: Int, hour: Int, minute: Int = 0): ZonedDateTime =
        ZonedDateTime.of(LocalDateTime.of(2026, 9, day, hour, minute), utc)

    private fun hm(h: Int, m: Int = 0) = h * 60 + m

    // ---- ordinary, same-day windows ----

    @Test
    fun `window inside one day is active between its edges`() {
        val rule = TimeRule(
            daysMask = 0b1111111,
            startMinute = hm(9),
            endMinute = hm(17, 30),
            modeId = "work"
        )
        assertFalse(rule.activeAt(at(14, 8, 59)))
        assertTrue(rule.activeAt(at(14, 9, 0)))
        assertTrue(rule.activeAt(at(14, 17, 29)))
        assertFalse(rule.activeAt(at(14, 17, 30)))
    }

    @Test
    fun `day mask is honoured, monday is bit zero`() {
        val weekdays = TimeRule(
            daysMask = 0b0011111,
            startMinute = hm(9),
            endMinute = hm(17),
            modeId = "work"
        )
        assertTrue("Monday", weekdays.activeAt(at(14, 12)))
        assertTrue("Friday", weekdays.activeAt(at(18, 12)))
        assertFalse("Saturday", weekdays.activeAt(at(19, 12)))
        assertFalse("Sunday", weekdays.activeAt(at(20, 12)))

        val weekend = TimeRule(
            daysMask = 0b1100000,
            startMinute = hm(9),
            endMinute = hm(22),
            modeId = "personal"
        )
        assertFalse(weekend.activeAt(at(18, 12)))
        assertTrue(weekend.activeAt(at(19, 12)))
        assertTrue(weekend.activeAt(at(20, 12)))
    }

    @Test
    fun `a disabled rule is never active`() {
        val rule = TimeRule(
            enabled = false,
            startMinute = hm(9),
            endMinute = hm(17),
            modeId = "work"
        )
        assertFalse(rule.activeAt(at(14, 12)))
    }

    // ---- the interesting case: windows that cross midnight ----

    @Test
    fun `sleep window spans midnight in both directions`() {
        val sleep = TimeRule(
            daysMask = 0b1111111,
            startMinute = hm(22, 30),
            endMinute = hm(7),
            modeId = "sleep"
        )
        assertFalse("before it starts", sleep.activeAt(at(14, 22, 29)))
        assertTrue("just after it starts", sleep.activeAt(at(14, 22, 30)))
        assertTrue("after midnight", sleep.activeAt(at(15, 2, 0)))
        assertTrue("just before it ends", sleep.activeAt(at(15, 6, 59)))
        assertFalse("after it ends", sleep.activeAt(at(15, 7, 0)))
    }

    @Test
    fun `a wrapping window checks yesterday's day bit for the morning half`() {
        // Friday nights only: Friday is bit 4.
        val fridayNight = TimeRule(
            daysMask = 0b0010000,
            startMinute = hm(23),
            endMinute = hm(6),
            modeId = "sleep"
        )
        assertTrue("Friday 23:30", fridayNight.activeAt(at(18, 23, 30)))
        // Saturday 02:00 belongs to the window that began on Friday.
        assertTrue("Saturday 02:00", fridayNight.activeAt(at(19, 2, 0)))
        // Saturday 23:30 would need Saturday's bit, which is off.
        assertFalse("Saturday 23:30", fridayNight.activeAt(at(19, 23, 30)))
    }

    @Test
    fun `nextEnd lands tomorrow for a window that has already wrapped`() {
        val sleep = TimeRule(startMinute = hm(22, 30), endMinute = hm(7), modeId = "sleep")

        // 23:00 Monday: the window ends at 07:00 on Tuesday.
        assertEquals(
            at(15, 7).toInstant().toEpochMilli(),
            sleep.nextEnd(at(14, 23))
        )
        // 02:00 Tuesday: still the same window, ending at 07:00 the same day.
        assertEquals(
            at(15, 7).toInstant().toEpochMilli(),
            sleep.nextEnd(at(15, 2))
        )
    }

    @Test
    fun `nextEnd for a same-day window is later the same day`() {
        val work = TimeRule(startMinute = hm(9), endMinute = hm(17, 30), modeId = "work")
        assertEquals(
            at(14, 17, 30).toInstant().toEpochMilli(),
            work.nextEnd(at(14, 12))
        )
    }

    @Test
    fun `edges cover both boundaries for the next few days`() {
        val work = TimeRule(startMinute = hm(9), endMinute = hm(17), modeId = "work")
        val edges = work.edgesAfter(at(14, 12))
        assertEquals(6, edges.size)
        assertTrue(edges.contains(at(14, 9).toInstant().toEpochMilli()))
        assertTrue(edges.contains(at(14, 17).toInstant().toEpochMilli()))
        assertTrue(edges.contains(at(16, 17).toInstant().toEpochMilli()))
    }

    // ---- digest windows ----

    @Test
    fun `fixed digest times pick the next one today`() {
        val mode = Mode(id = "work", name = "Work", digestTimes = listOf(hm(12, 30), hm(17)))
        val now = at(14, 10).toInstant().toEpochMilli()
        assertEquals(
            at(14, 12, 30).toInstant().toEpochMilli(),
            DigestWindow.next(mode, now, utc)
        )
    }

    @Test
    fun `after the last digest time it rolls to tomorrow`() {
        val mode = Mode(id = "work", name = "Work", digestTimes = listOf(hm(12, 30), hm(17)))
        val now = at(14, 18).toInstant().toEpochMilli()
        assertEquals(
            at(15, 12, 30).toInstant().toEpochMilli(),
            DigestWindow.next(mode, now, utc)
        )
    }

    @Test
    fun `an interval beats fixed times`() {
        val mode = Mode(
            id = "personal",
            name = "Personal",
            digestEveryMinutes = 90,
            digestTimes = listOf(hm(12))
        )
        val now = at(14, 10).toInstant().toEpochMilli()
        assertEquals(now + 90 * 60_000L, DigestWindow.next(mode, now, utc))
    }

    @Test
    fun `a mode with no digest schedule has no next window`() {
        val mode = Mode(id = "open", name = "Open")
        assertEquals(null, DigestWindow.next(mode, at(14, 10).toInstant().toEpochMilli(), utc))
    }

    @Test
    fun `hold duration is clamped so long holds are re-snoozed in chunks`() {
        // Sleep holds until 07:30, which from 23:00 is eight and a half hours.
        val sleep = Mode(id = "sleep", name = "Sleep", digestTimes = listOf(hm(7, 30)))
        val now = at(14, 23).toInstant().toEpochMilli()
        assertEquals(DigestWindow.MAX_HOLD_MS, DigestWindow.holdDuration(sleep, now, utc))
    }

    @Test
    fun `hold duration never drops below the floor`() {
        // A digest window one second away must not cause a snooze storm.
        val mode = Mode(id = "work", name = "Work", digestTimes = listOf(hm(12)))
        val now = at(14, 11, 59).toInstant().toEpochMilli() + 59_000
        assertEquals(DigestWindow.MIN_HOLD_MS, DigestWindow.holdDuration(mode, now, utc))
    }

    @Test
    fun `a mode with no window still holds for a bounded time`() {
        val mode = Mode(id = "open", name = "Open")
        val now = at(14, 10).toInstant().toEpochMilli()
        val held = DigestWindow.holdDuration(mode, now, utc)
        assertTrue(held in DigestWindow.MIN_HOLD_MS..DigestWindow.MAX_HOLD_MS)
    }
}
