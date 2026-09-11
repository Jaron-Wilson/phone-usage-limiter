package dev.jaronwilson.modes

import dev.jaronwilson.modes.schedule.CalEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.JulianFields

/**
 * Splitting an agenda into "today" and "tomorrow".
 *
 * The trap this guards is the all-day event. The provider stores one as UTC
 * midnight to UTC midnight, so in New York an all-day Sunday event begins at
 * 20:00 on Saturday. Bucketing by timestamp therefore files Sunday's events
 * under tomorrow on a Friday evening, which is exactly the bug that prompted
 * these tests.
 */
class AgendaDayTest {

    private val ny: ZoneId = ZoneId.of("America/New_York")
    private val friday: LocalDate = LocalDate.of(2026, 9, 11)
    private val fridayJulian = friday.getLong(JulianFields.JULIAN_DAY).toInt()

    private fun julian(d: LocalDate) = d.getLong(JulianFields.JULIAN_DAY).toInt()

    /** An all-day event as the provider really stores it: UTC midnight. */
    private fun allDay(date: LocalDate, title: String): CalEvent {
        val beginUtc = date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        return CalEvent(
            eventId = 1, calendarId = 1, calendarName = "", title = title,
            begin = beginUtc, end = beginUtc + 86_400_000L,
            allDay = true, busy = false,
            startDay = julian(date), endDay = julian(date)
        )
    }

    private fun timed(date: LocalDate, hour: Int, title: String): CalEvent {
        val begin = date.atStartOfDay(ny).plusHours(hour.toLong()).toInstant().toEpochMilli()
        return CalEvent(
            eventId = 2, calendarId = 1, calendarName = "", title = title,
            begin = begin, end = begin + 3_600_000L,
            allDay = false, busy = true,
            startDay = julian(date), endDay = julian(date)
        )
    }

    private fun split(events: List<CalEvent>): Pair<List<CalEvent>, List<CalEvent>> {
        val today = events.filter { it.occursOn(fridayJulian) }
        val tomorrow = events.filter { it.occursOn(fridayJulian + 1) && !it.occursOn(fridayJulian) }
        return today to tomorrow
    }

    @Test
    fun `a sunday all-day event does not leak into tomorrow`() {
        val sunday = allDay(friday.plusDays(2), "Check credit card balance")
        // Proof the trap is real: by timestamp it looks like Saturday evening.
        val asLocal = java.time.Instant.ofEpochMilli(sunday.begin).atZone(ny).toLocalDate()
        assertEquals("stored timestamp lands on Saturday in New York", friday.plusDays(1), asLocal)

        val (today, tomorrow) = split(listOf(sunday))
        assertTrue("must not be today", today.isEmpty())
        assertTrue("must not be tomorrow either", tomorrow.isEmpty())
    }

    @Test
    fun `a saturday all-day event is tomorrow`() {
        val (today, tomorrow) = split(listOf(allDay(friday.plusDays(1), "Quarter ends")))
        assertTrue(today.isEmpty())
        assertEquals(listOf("Quarter ends"), tomorrow.map { it.title })
    }

    @Test
    fun `a friday all-day event is today`() {
        val (today, tomorrow) = split(listOf(allDay(friday, "SAM.gov registration")))
        assertEquals(listOf("SAM.gov registration"), today.map { it.title })
        assertTrue(tomorrow.isEmpty())
    }

    @Test
    fun `timed events land on their own day`() {
        val events = listOf(
            timed(friday, 17, "Dinner with Pelletiers"),
            timed(friday.plusDays(1), 10, "Python Crash Course"),
            timed(friday.plusDays(2), 9, "Sunday service")
        )
        val (today, tomorrow) = split(events)
        assertEquals(listOf("Dinner with Pelletiers"), today.map { it.title })
        assertEquals(listOf("Python Crash Course"), tomorrow.map { it.title })
    }

    @Test
    fun `an event spanning midnight counts as today, not twice`() {
        val overnight = timed(friday, 23, "Night shift").copy(endDay = fridayJulian + 1)
        val (today, tomorrow) = split(listOf(overnight))
        assertEquals(1, today.size)
        assertTrue("already shown under today", tomorrow.isEmpty())
    }

    @Test
    fun `an event with no day numbers is dropped rather than misplaced`() {
        val broken = timed(friday, 12, "No day info").copy(startDay = 0, endDay = 0)
        val (today, tomorrow) = split(listOf(broken))
        assertTrue(today.isEmpty())
        assertTrue(tomorrow.isEmpty())
        assertFalse(broken.occursOn(fridayJulian))
    }
}
