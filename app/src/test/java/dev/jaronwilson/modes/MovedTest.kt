package dev.jaronwilson.modes

import dev.jaronwilson.modes.launcher.moved
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Moving one item in a list.
 *
 * This runs on every crossed row boundary during a drag, so it has to be
 * exact: an off-by-one here is a home screen that scrambles itself under the
 * finger, and out-of-range input has to be survivable because a fast drag can
 * outrun the list it is reordering.
 */
class MovedTest {

    private val list = listOf("a", "b", "c", "d")

    @Test
    fun `moving down shifts the rest up`() {
        assertEquals(listOf("b", "c", "a", "d"), list.moved(0, 2))
    }

    @Test
    fun `moving up shifts the rest down`() {
        assertEquals(listOf("a", "d", "b", "c"), list.moved(3, 1))
    }

    @Test
    fun `moving to the same place changes nothing`() {
        assertEquals(list, list.moved(2, 2))
    }

    @Test
    fun `moving to either end works`() {
        assertEquals(listOf("d", "a", "b", "c"), list.moved(3, 0))
        assertEquals(listOf("b", "c", "d", "a"), list.moved(0, 3))
    }

    @Test
    fun `an index off the end is ignored rather than throwing`() {
        assertEquals(list, list.moved(0, 9))
        assertEquals(list, list.moved(-1, 2))
        assertEquals(list, list.moved(4, 0))
    }

    @Test
    fun `nothing is lost or duplicated, whatever the move`() {
        for (from in list.indices) {
            for (to in list.indices) {
                val out = list.moved(from, to)
                assertEquals("size changed moving $from to $to", list.size, out.size)
                assertEquals("contents changed moving $from to $to", list.toSet(), out.toSet())
            }
        }
    }

    @Test
    fun `a single item list survives`() {
        assertEquals(listOf("only"), listOf("only").moved(0, 0))
    }

    @Test
    fun `an empty list survives`() {
        assertEquals(emptyList<String>(), emptyList<String>().moved(0, 1))
    }
}
