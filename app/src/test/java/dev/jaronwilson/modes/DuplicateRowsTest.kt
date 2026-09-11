package dev.jaronwilson.modes

import dev.jaronwilson.modes.core.Defaults
import dev.jaronwilson.modes.core.model.HomeEntry
import dev.jaronwilson.modes.core.repo.findDuplicates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repairing a home screen that seeded itself twice.
 *
 * Three components race to seed at startup, and before the lock existed two of
 * them could both find an empty table and both insert. Seeded rows now carry
 * fixed ids so a second pass overwrites, and this repairs the phones that
 * doubled before that was true.
 */
class DuplicateRowsTest {

    private fun app(id: Long, mode: String, pkg: String, order: Int = 0) =
        HomeEntry(id = id, modeId = mode, sortOrder = order, packageName = pkg)

    private fun folder(id: Long, mode: String, folderId: Long, order: Int = 0) =
        HomeEntry(id = id, modeId = mode, sortOrder = order, folderId = folderId)

    @Test
    fun `a clean layout has nothing to remove`() {
        val rows = listOf(app(1, "open", "a"), app(2, "open", "b"), folder(3, "open", 1))
        assertTrue(findDuplicates(rows).isEmpty())
    }

    @Test
    fun `the second copy of each row goes`() {
        val rows = listOf(
            app(1, "open", "a"), app(2, "open", "a"),
            folder(3, "open", 7), folder(4, "open", 7)
        )
        assertEquals(setOf(2L, 4L), findDuplicates(rows).map { it.id }.toSet())
    }

    @Test
    fun `the survivor is the one already on screen`() {
        // Lowest id wins: that is the row whose position and switch the owner
        // has been looking at.
        val rows = listOf(app(9, "open", "a"), app(4, "open", "a"), app(7, "open", "a"))
        assertEquals(setOf(7L, 9L), findDuplicates(rows).map { it.id }.toSet())
    }

    @Test
    fun `the same app in two modes is not a duplicate`() {
        val rows = listOf(app(1, "open", "a"), app(2, "work", "a"))
        assertTrue(findDuplicates(rows).isEmpty())
    }

    @Test
    fun `the same folder in two modes is not a duplicate`() {
        val rows = listOf(folder(1, "open", 3), folder(2, "school", 3))
        assertTrue(findDuplicates(rows).isEmpty())
    }

    @Test
    fun `an app row and a folder row never collide`() {
        val rows = listOf(app(1, "open", "a"), folder(2, "open", 1))
        assertTrue(findDuplicates(rows).isEmpty())
    }

    @Test
    fun `repairing the exact doubling seen on the phone`() {
        // Every shipped row, inserted twice with fresh ids, as happened.
        val seeded = Defaults.homeEntries()
        val doubled = seeded + seeded.mapIndexed { i, e -> e.copy(id = 10_000L + i) }
        val drop = findDuplicates(doubled)
        assertEquals(seeded.size, drop.size)
        val survivors = doubled - drop.toSet()
        assertEquals(seeded.size, survivors.size)
        assertTrue("only the originals survive", survivors.all { it.id < 10_000L })
    }

    @Test
    fun `seeded rows have stable distinct ids so seeding twice cannot double`() {
        val once = Defaults.homeEntries()
        val ids = once.map { it.id }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
        assertTrue("ids must be assigned, not left to the database", ids.none { it == 0L })
        // Seeding again yields the same ids, so an upsert overwrites.
        assertEquals(ids, Defaults.homeEntries().map { it.id })
    }
}
