package dev.jaronwilson.modes

import dev.jaronwilson.modes.core.model.Folder
import dev.jaronwilson.modes.core.model.MAX_FOLDER_DEPTH
import dev.jaronwilson.modes.core.model.canNest
import dev.jaronwilson.modes.core.model.flattenPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folders that contain folders.
 *
 * The danger the moment nesting exists is a loop: a folder that contains
 * itself, directly or through a chain. Rendering one would hang the launcher
 * rather than merely look wrong, and the guard would never finish working out
 * which apps a mode allows. So nothing here trusts the data.
 */
class NestedFoldersTest {

    private fun folder(id: Long, name: String, apps: List<String> = emptyList(), subs: List<Long> = emptyList()) =
        Folder(id = id, name = name, packages = apps, subFolders = subs)

    private fun index(vararg folders: Folder) = folders.associateBy { it.id }

    @Test
    fun `a plain folder yields its own apps`() {
        val f = folder(1, "Money", listOf("bank", "wallet"))
        assertEquals(listOf("bank", "wallet"), flattenPackages(f, index(f)))
    }

    @Test
    fun `a nested folder contributes its apps too`() {
        val child = folder(2, "Maths", listOf("calc"))
        val parent = folder(1, "School", listOf("gmail"), subs = listOf(2))
        assertEquals(listOf("gmail", "calc"), flattenPackages(parent, index(parent, child)))
    }

    @Test
    fun `nesting several levels deep still flattens`() {
        val c = folder(3, "Week", listOf("deep"))
        val b = folder(2, "Term", subs = listOf(3))
        val a = folder(1, "School", listOf("top"), subs = listOf(2))
        assertEquals(listOf("top", "deep"), flattenPackages(a, index(a, b, c)))
    }

    @Test
    fun `a folder containing itself does not hang`() {
        val f = folder(1, "Loop", listOf("a"), subs = listOf(1))
        assertEquals(listOf("a"), flattenPackages(f, index(f)))
    }

    @Test
    fun `a longer cycle does not hang either`() {
        val a = folder(1, "A", listOf("a"), subs = listOf(2))
        val b = folder(2, "B", listOf("b"), subs = listOf(3))
        val c = folder(3, "C", listOf("c"), subs = listOf(1))
        assertEquals(listOf("a", "b", "c"), flattenPackages(a, index(a, b, c)))
    }

    @Test
    fun `a missing child is skipped rather than throwing`() {
        val parent = folder(1, "School", listOf("gmail"), subs = listOf(99))
        assertEquals(listOf("gmail"), flattenPackages(parent, index(parent)))
    }

    @Test
    fun `nesting stops at a sane depth`() {
        // A chain longer than the limit is truncated rather than followed.
        val chain = (1L..10L).map { id ->
            folder(id, "L$id", listOf("app$id"), subs = if (id < 10) listOf(id + 1) else emptyList())
        }
        val found = flattenPackages(chain.first(), chain.associateBy { it.id })
        assertTrue("should stop before the end of a ten-deep chain", found.size <= MAX_FOLDER_DEPTH + 1)
    }

    // ---- what may be nested ----

    @Test
    fun `a folder cannot contain itself`() {
        val f = folder(1, "School")
        assertFalse(canNest(f, f, index(f)))
    }

    @Test
    fun `a folder cannot contain its own ancestor`() {
        // Dropping School into Maths, when Maths already sits inside School.
        val maths = folder(2, "Maths")
        val school = folder(1, "School", subs = listOf(2))
        assertFalse(canNest(parent = maths, child = school, byId = index(school, maths)))
    }

    @Test
    fun `a deeper ancestor is caught as well`() {
        val c = folder(3, "Week")
        val b = folder(2, "Term", subs = listOf(3))
        val a = folder(1, "School", subs = listOf(2))
        assertFalse(canNest(parent = c, child = a, byId = index(a, b, c)))
    }

    @Test
    fun `unrelated folders may be nested`() {
        val money = folder(1, "Money")
        val social = folder(2, "Social")
        assertTrue(canNest(money, social, index(money, social)))
    }

    @Test
    fun `an empty folder knows it is empty`() {
        assertTrue(folder(1, "New").isEmpty)
        assertFalse(folder(1, "New", listOf("a")).isEmpty)
        assertFalse(folder(1, "New", subs = listOf(2)).isEmpty)
    }
}
