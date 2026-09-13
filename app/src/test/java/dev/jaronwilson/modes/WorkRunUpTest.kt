package dev.jaronwilson.modes

import dev.jaronwilson.modes.commute.WorkRunUp
import dev.jaronwilson.modes.schedule.CalEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkRunUpTest {

    private val workPattern = Regex("(?i)\\b(work|shift|on.?call|clock.?in)\\b")

    private fun event(
        title: String, begin: Long, end: Long = begin + 3_600_000,
        allDay: Boolean = false, location: String = ""
    ) = CalEvent(
        eventId = begin, calendarId = 1, calendarName = "c", title = title,
        begin = begin, end = end, allDay = allDay, busy = true,
        startDay = 0, endDay = 0, color = 0, location = location
    )

    private val now = 1_000_000_000_000L

    @Test
    fun `picks the next work event by title`() {
        val events = listOf(
            event("Dentist", now + 60 * 60_000),
            event("Work shift", now + 120 * 60_000, location = "123 Main St")
        )
        val runUp = WorkRunUp.nextWork(events, now, listOf(workPattern))
        assertEquals("Work shift", runUp?.title)
        assertEquals("123 Main St", runUp?.location)
        assertEquals(now + 120 * 60_000, runUp?.startAt)
    }

    @Test
    fun `ignores events already started`() {
        val events = listOf(event("Work", now - 10 * 60_000))
        assertNull(WorkRunUp.nextWork(events, now, listOf(workPattern)))
    }

    @Test
    fun `ignores all-day events`() {
        val events = listOf(event("Work", now + 60 * 60_000, allDay = true))
        assertNull(WorkRunUp.nextWork(events, now, listOf(workPattern)))
    }

    @Test
    fun `ignores non-work events`() {
        val events = listOf(event("Lunch with Sam", now + 30 * 60_000))
        assertNull(WorkRunUp.nextWork(events, now, listOf(workPattern)))
    }

    @Test
    fun `earliest matching work event wins`() {
        val events = listOf(
            event("Night shift", now + 300 * 60_000),
            event("Work", now + 90 * 60_000)
        )
        assertEquals(now + 90 * 60_000, WorkRunUp.nextWork(events, now, listOf(workPattern))?.startAt)
    }

    @Test
    fun `lead times are one hour and thirty minutes`() {
        assertEquals(60L, WorkRunUp.LEAD_FIRST_MIN)
        assertEquals(30L, WorkRunUp.LEAD_SECOND_MIN)
    }
}
