package dev.jaronwilson.modes

import dev.jaronwilson.modes.commute.Destination
import dev.jaronwilson.modes.commute.Destinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationsTest {

    private val home = Destination("Home", "12 Elm Street, Springfield")
    private val school = Destination("School", "Northern Virginia Community College")

    @Test
    fun `a round trip keeps both fields intact`() {
        val list = listOf(home, school)
        assertEquals(list, Destinations.decode(Destinations.encode(list)))
    }

    @Test
    fun `commas and punctuation in an address survive`() {
        val awkward = Destination("Work", "Unit 4, 12-14 King's Road, Apt #3")
        assertEquals(listOf(awkward), Destinations.decode(Destinations.encode(listOf(awkward))))
    }

    @Test
    fun `nothing stored decodes to nothing`() {
        assertTrue(Destinations.decode("").isEmpty())
    }

    @Test
    fun `a malformed line is skipped rather than crashing`() {
        assertTrue(Destinations.decode("no separator here").isEmpty())
    }

    @Test
    fun `entries missing a field are dropped`() {
        assertTrue(Destinations.encode(listOf(Destination("Home", ""))).isEmpty())
        assertTrue(Destinations.encode(listOf(Destination("", "somewhere"))).isEmpty())
    }

    @Test
    fun `adding the same label twice replaces rather than duplicates`() {
        val moved = Destination("Home", "99 New Road")
        val after = Destinations.upsert(listOf(home, school), moved)
        assertEquals(2, after.size)
        assertEquals("99 New Road", Destinations.find(after, "Home")?.query)
    }

    @Test
    fun `labels are matched without regard to case`() {
        val after = Destinations.upsert(listOf(home), Destination("home", "99 New Road"))
        assertEquals(1, after.size)
        assertEquals(listOf(home), Destinations.remove(Destinations.upsert(listOf(home), school), "SCHOOL"))
    }

    @Test
    fun `removing something absent changes nothing`() {
        assertEquals(listOf(home), Destinations.remove(listOf(home), "Gym"))
        assertNull(Destinations.find(listOf(home), "Gym"))
    }

    @Test
    fun `whitespace around what you typed is trimmed`() {
        val padded = Destinations.upsert(emptyList(), Destination("  Gym  ", "  5 Mill Lane  "))
        assertEquals(Destination("Gym", "5 Mill Lane"), padded.single())
    }
}
