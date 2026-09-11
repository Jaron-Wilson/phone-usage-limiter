package dev.jaronwilson.modes

import dev.jaronwilson.modes.core.model.EventKind
import dev.jaronwilson.modes.core.model.UsageEvent
import dev.jaronwilson.modes.ui.screens.modeDurations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {

    private val day = 24 * 60 * 60 * 1000L
    private val hour = 60 * 60 * 1000L
    private val start = 1_000_000_000_000L

    private fun change(atHours: Int, to: String, from: String) = UsageEvent(
        at = start + atHours * hour,
        kind = EventKind.MODE_CHANGED,
        modeId = to,
        detail = from
    )

    @Test
    fun `no changes means the whole day so far was the active mode`() {
        val out = modeDurations(emptyList(), "work", start, start + 5 * hour)
        assertEquals(mapOf("work" to 5 * hour), out)
    }

    @Test
    fun `segments are attributed to the mode running during them`() {
        // Open until 09:00, Work until 12:00, Personal until now at 14:00.
        val events = listOf(
            change(9, to = "work", from = "open"),
            change(12, to = "personal", from = "work")
        )
        val out = modeDurations(events, "personal", start, start + 14 * hour)
        assertEquals(9 * hour, out["open"])
        assertEquals(3 * hour, out["work"])
        assertEquals(2 * hour, out["personal"])
    }

    @Test
    fun `the mode before the first change is read from that change`() {
        val events = listOf(change(22, to = "sleep", from = "personal"))
        val out = modeDurations(events, "sleep", start, start + 23 * hour)
        assertEquals(22 * hour, out["personal"])
        assertEquals(1 * hour, out["sleep"])
    }

    @Test
    fun `unrelated events are ignored`() {
        val noise = UsageEvent(at = start + hour, kind = EventKind.NOTIF_HELD, modeId = "work")
        val out = modeDurations(listOf(noise), "work", start, start + 2 * hour)
        assertEquals(mapOf("work" to 2 * hour), out)
    }

    @Test
    fun `durations add up to the elapsed day`() {
        val events = listOf(
            change(7, to = "work", from = "sleep"),
            change(17, to = "personal", from = "work"),
            change(22, to = "sleep", from = "personal")
        )
        val now = start + 23 * hour
        val out = modeDurations(events, "sleep", start, now)
        assertEquals(now - start, out.values.sum())
        assertTrue(out.values.all { it > 0 })
    }
}
