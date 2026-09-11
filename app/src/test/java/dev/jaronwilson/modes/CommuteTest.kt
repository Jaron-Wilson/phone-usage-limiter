package dev.jaronwilson.modes

import dev.jaronwilson.modes.commute.Commute
import dev.jaronwilson.modes.commute.CommuteSettings
import dev.jaronwilson.modes.schedule.CalEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Working backwards from an event to the moment you have to move.
 *
 * These are somebody's mornings, so the arithmetic is worth pinning down: an
 * alarm that fires late is worse than no alarm, because it was trusted.
 */
class CommuteTest {

    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    private val settings = CommuteSettings(
        arriveEarlyMinutes = 10,
        getReadyMinutes = 5,
        defaultTravelMinutes = 25
    )

    private fun event(
        startsInMinutes: Long,
        title: String = "Shift",
        location: String = "14 Mill Road",
        allDay: Boolean = false
    ) = CalEvent(
        eventId = 1, calendarId = 1, calendarName = "", title = title,
        begin = now + startsInMinutes * minute,
        end = now + (startsInMinutes + 60) * minute,
        allDay = allDay, busy = true, startDay = 1, endDay = 1, location = location
    )

    @Test
    fun `leave time is the start less the buffer and the drive`() {
        val plan = Commute.nextPlan(listOf(event(120)), now, settings)!!
        // 120 - 10 early - 25 drive = 85 minutes from now.
        assertEquals(now + 85 * minute, plan.leaveAt)
        // The nudge lands 5 minutes before that.
        assertEquals(now + 80 * minute, plan.warnAt)
        assertEquals(5, plan.minutesOfWarning)
    }

    @Test
    fun `an event with no location is not a journey`() {
        assertNull(Commute.nextPlan(listOf(event(120, location = "")), now, settings))
    }

    @Test
    fun `an all-day event is a deadline, not a journey`() {
        assertNull(Commute.nextPlan(listOf(event(120, allDay = true)), now, settings))
    }

    @Test
    fun `an event already under way is skipped`() {
        assertNull(Commute.nextPlan(listOf(event(-30)), now, settings))
    }

    @Test
    fun `an event you should already have left for gets no alarm`() {
        // Starts in 20 minutes but needs 35 of notice. Telling someone to leave
        // 15 minutes ago is worse than saying nothing.
        assertNull(Commute.nextPlan(listOf(event(20)), now, settings))
    }

    @Test
    fun `the soonest event you can still make is the one planned for`() {
        val plan = Commute.nextPlan(
            listOf(event(400, title = "Late"), event(120, title = "Soon"), event(20, title = "Missed")),
            now, settings
        )!!
        assertEquals("Soon", plan.event.title)
    }

    @Test
    fun `a per-event travel estimate overrides the default`() {
        val plan = Commute.nextPlan(listOf(event(120)), now, settings) { 45 }!!
        assertEquals(now + 65 * minute, plan.leaveAt)
        assertEquals(45, plan.travelMinutes)
    }

    @Test
    fun `wording changes as the deadline closes`() {
        val plan = Commute.nextPlan(listOf(event(120)), now, settings)!!
        assertTrue(Commute.phrase(plan, plan.leaveAt - 9 * minute).startsWith("Leave in 9 minutes"))
        assertTrue(Commute.phrase(plan, plan.leaveAt - minute).startsWith("Leave in a minute"))
        assertTrue(Commute.phrase(plan, plan.leaveAt).startsWith("Leave now"))
        assertTrue(Commute.phrase(plan, plan.leaveAt + minute).startsWith("Leave now"))
    }

    @Test
    fun `no events means no plan`() {
        assertNull(Commute.nextPlan(emptyList(), now, settings))
    }
}
