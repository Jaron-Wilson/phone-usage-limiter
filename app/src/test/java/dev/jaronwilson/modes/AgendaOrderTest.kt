package dev.jaronwilson.modes

import dev.jaronwilson.modes.schedule.AgendaOrder
import dev.jaronwilson.modes.schedule.CalEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which event goes first.
 *
 * Time settles almost every pair. What it does not settle is two things
 * starting at the same minute, and there the provider's order is arbitrary, so
 * a stated preference between calendars decides it: the class you must attend
 * above the tailgate you might.
 */
class AgendaOrderTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun event(
        atMinutes: Long,
        title: String,
        calendar: Long,
        allDay: Boolean = false,
        lastsMinutes: Long = 60
    ) = CalEvent(
        eventId = title.hashCode().toLong(), calendarId = calendar, calendarName = "",
        title = title, begin = now + atMinutes * minute,
        end = now + (atMinutes + lastsMinutes) * minute,
        allDay = allDay, busy = true, startDay = 1, endDay = 1
    )

    @Test
    fun `time decides when times differ, whatever the preference`() {
        val events = listOf(event(60, "Later", 1), event(10, "Sooner", 2))
        assertEquals(
            listOf("Sooner", "Later"),
            AgendaOrder.sort(events, priority = listOf(1)).map { it.title }
        )
    }

    @Test
    fun `a clash at the same minute is settled by the preferred calendar`() {
        // The real case: two things at 10:00, one from the school calendar.
        val events = listOf(event(60, "Commuter Tailgate", 8), event(60, "Python Crash Course", 4))
        assertEquals(
            listOf("Python Crash Course", "Commuter Tailgate"),
            AgendaOrder.sort(events, priority = listOf(4, 8)).map { it.title }
        )
        // Reverse the preference and the answer reverses with it.
        assertEquals(
            listOf("Commuter Tailgate", "Python Crash Course"),
            AgendaOrder.sort(events, priority = listOf(8, 4)).map { it.title }
        )
    }

    @Test
    fun `an unlisted calendar sorts after every listed one`() {
        val events = listOf(event(60, "Unlisted", 99), event(60, "Listed", 4))
        assertEquals(
            listOf("Listed", "Unlisted"),
            AgendaOrder.sort(events, priority = listOf(4)).map { it.title }
        )
    }

    @Test
    fun `with no preference the order is still stable, not arbitrary`() {
        val events = listOf(event(60, "Beta", 2), event(60, "Alpha", 1))
        val once = AgendaOrder.sort(events, emptyList()).map { it.title }
        val twice = AgendaOrder.sort(events.reversed(), emptyList()).map { it.title }
        assertEquals(once, twice)
        assertEquals(listOf("Alpha", "Beta"), once)
    }

    @Test
    fun `rank places listed calendars in order and the rest last`() {
        assertEquals(0, AgendaOrder.rank(4, listOf(4, 8)))
        assertEquals(1, AgendaOrder.rank(8, listOf(4, 8)))
        assertEquals(Int.MAX_VALUE, AgendaOrder.rank(99, listOf(4, 8)))
    }

    @Test
    fun `the headline is whatever is running now`() {
        val events = listOf(event(-10, "Running", 8), event(30, "Next up", 4))
        assertEquals("Running", AgendaOrder.headline(events, now, listOf(4))?.title)
    }

    @Test
    fun `two things running at once defer to the preference`() {
        val events = listOf(event(-10, "Tailgate", 8), event(-10, "Lecture", 4))
        assertEquals("Lecture", AgendaOrder.headline(events, now, listOf(4, 8))?.title)
    }

    @Test
    fun `with nothing running the headline is the next thing due`() {
        val events = listOf(event(120, "Later", 8), event(30, "Sooner", 4))
        assertEquals("Sooner", AgendaOrder.headline(events, now, emptyList())?.title)
    }

    @Test
    fun `an all-day event never takes the headline`() {
        // A deadline spanning the day is worth seeing and is not what you are
        // doing at this moment.
        val events = listOf(event(0, "Quarter ends", 4, allDay = true, lastsMinutes = 1440))
        assertNull(AgendaOrder.headline(events, now, listOf(4)))
    }

    @Test
    fun `an empty day has no headline`() {
        assertNull(AgendaOrder.headline(emptyList(), now, listOf(4)))
    }
}
