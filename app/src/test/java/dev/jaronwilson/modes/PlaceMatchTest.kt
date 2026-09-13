package dev.jaronwilson.modes

import dev.jaronwilson.modes.core.model.Place
import dev.jaronwilson.modes.schedule.PlaceMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceMatchTest {

    private fun place(
        name: String, lat: Double, lon: Double,
        radius: Double = 150.0, mode: String = "m", priority: Int = 0, enabled: Boolean = true
    ) = Place(
        name = name, latitude = lat, longitude = lon,
        radiusMeters = radius, modeId = mode, priority = priority, enabled = enabled
    )

    @Test
    fun `distance between two close points is small`() {
        // ~111 metres per 0.001 degree of latitude.
        val d = PlaceMatch.distanceMeters(40.0000, -75.0, 40.0010, -75.0)
        assertTrue("expected ~111 m, got $d", d in 100.0..125.0)
    }

    @Test
    fun `a point inside the radius matches`() {
        val home = place("Home", 40.0, -75.0, radius = 150.0)
        val hit = PlaceMatch.current(listOf(home), 40.0008, -75.0)
        assertEquals("Home", hit?.name)
    }

    @Test
    fun `a point outside every radius matches nothing`() {
        val home = place("Home", 40.0, -75.0, radius = 150.0)
        assertNull(PlaceMatch.current(listOf(home), 40.010, -75.0))
    }

    @Test
    fun `higher priority wins when two places overlap`() {
        val big = place("Campus", 40.0, -75.0, radius = 500.0, mode = "school", priority = 0)
        val small = place("Library", 40.0005, -75.0, radius = 300.0, mode = "deep", priority = 5)
        val hit = PlaceMatch.current(listOf(big, small), 40.0004, -75.0)
        assertEquals("Library", hit?.name)
    }

    @Test
    fun `nearer centre wins at equal priority`() {
        val a = place("A", 40.0, -75.0, radius = 500.0, priority = 0)
        val b = place("B", 40.0010, -75.0, radius = 500.0, priority = 0)
        val hit = PlaceMatch.current(listOf(a, b), 40.0002, -75.0)
        assertEquals("A", hit?.name)
    }

    @Test
    fun `a disabled place never matches`() {
        val home = place("Home", 40.0, -75.0, radius = 150.0, enabled = false)
        assertNull(PlaceMatch.current(listOf(home), 40.0, -75.0))
    }
}
