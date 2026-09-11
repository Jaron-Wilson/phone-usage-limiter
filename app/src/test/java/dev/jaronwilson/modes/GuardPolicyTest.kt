package dev.jaronwilson.modes

import dev.jaronwilson.modes.core.Pkg
import dev.jaronwilson.modes.core.model.GuardMode
import dev.jaronwilson.modes.core.model.GuardScope
import dev.jaronwilson.modes.core.model.Folder
import dev.jaronwilson.modes.core.model.HomeEntry
import dev.jaronwilson.modes.core.model.resolveHomeRows
import dev.jaronwilson.modes.core.model.Mode
import dev.jaronwilson.modes.guard.GuardPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardPolicyTest {

    private val own = "dev.jaronwilson.modes"
    private val launchable: (String) -> Boolean = { true }

    private fun mode(
        scope: GuardScope = GuardScope.ALLOWLIST,
        guard: GuardMode = GuardMode.SPEEDBUMP,
        blocked: Set<String> = emptySet(),
        allowed: Set<String> = emptySet()
    ) = Mode(
        id = "work",
        name = "Work",
        guardMode = guard,
        guardScope = scope,
        blockedPackages = blocked,
        allowedPackages = allowed
    )

    private fun guarded(
        pkg: String,
        mode: Mode,
        home: Set<String> = emptySet(),
        isLaunchable: (String) -> Boolean = launchable
    ) = GuardPolicy.shouldGuard(pkg, own, mode, home, isLaunchable)

    // ---- the question the whole feature exists to answer ----

    @Test
    fun `an app not on the home screen is stopped under allowlist`() {
        // The case that matters: something installed later, or opened from a
        // link, that was never part of this mode.
        assertTrue(guarded("com.newly.installed", mode(), home = setOf(Pkg.GMAIL)))
    }

    @Test
    fun `an app inside a folder is allowed`() {
        val home = setOf(Pkg.GMAIL, Pkg.MAPS, Pkg.CHROME)
        assertFalse(guarded(Pkg.CHROME, mode(), home))
    }

    @Test
    fun `under blocklist an unknown app is allowed`() {
        assertFalse(guarded("com.newly.installed", mode(scope = GuardScope.BLOCKLIST)))
    }

    @Test
    fun `under blocklist only the listed apps are stopped`() {
        val m = mode(scope = GuardScope.BLOCKLIST, blocked = setOf(Pkg.INSTAGRAM))
        assertTrue(guarded(Pkg.INSTAGRAM, m))
        assertFalse(guarded(Pkg.REDDIT, m))
    }

    @Test
    fun `an app set aside is stopped even if it is on the home screen`() {
        val m = mode(blocked = setOf(Pkg.INSTAGRAM))
        assertTrue(guarded(Pkg.INSTAGRAM, m, home = setOf(Pkg.INSTAGRAM)))
    }

    // ---- the ways this could trap you, which it must not ----

    @Test
    fun `essentials are never stopped`() {
        val m = mode()
        Pkg.ESSENTIAL.forEach { pkg ->
            assertFalse("$pkg must stay reachable", guarded(pkg, m))
        }
    }

    @Test
    fun `system surfaces are never stopped`() {
        val m = mode()
        GuardPolicy.NEVER_GUARD.forEach { pkg ->
            assertFalse("$pkg must stay reachable", guarded(pkg, m))
        }
    }

    @Test
    fun `settings stays reachable so you can always undo the mode`() {
        assertFalse(guarded("com.android.settings", mode()))
    }

    @Test
    fun `money apps are never stopped, in any mode`() {
        val m = mode(guard = GuardMode.BLOCK)
        Pkg.FINANCE.forEach { pkg ->
            assertFalse("$pkg must stay reachable", guarded(pkg, m))
        }
    }

    @Test
    fun `money apps are reachable even when explicitly set aside`() {
        // Deliberate: a fraud alert you cannot act on is worse than the
        // distraction. Banking is exempt before the blocklist is consulted.
        val m = mode(blocked = setOf(Pkg.CHASE))
        assertFalse(guarded(Pkg.CHASE, m))
    }

    @Test
    fun `modes itself is never stopped`() {
        assertFalse(guarded(own, mode()))
    }

    @Test
    fun `guard off means nothing is stopped`() {
        val m = mode(guard = GuardMode.OFF, blocked = setOf(Pkg.INSTAGRAM))
        assertFalse(guarded(Pkg.INSTAGRAM, m))
        assertFalse(guarded("com.anything", m))
    }

    @Test
    fun `non-launchable packages are left alone`() {
        // Background services and share sheets surface as window changes too.
        assertFalse(guarded("com.some.background.service", mode()) { false })
    }

    @Test
    fun `allowedPackages bypasses the allowlist`() {
        val m = mode(allowed = setOf("com.work.vpn"))
        assertFalse(guarded("com.work.vpn", m))
    }

    // ---- folders feed the allowlist ----

    private fun folder(id: Long, name: String, vararg pkgs: String) =
        Folder(id = id, name = name, packages = pkgs.toList())

    private fun folderRow(id: Long, folderId: Long, order: Int, on: Boolean = true) =
        HomeEntry(id = id, modeId = "work", sortOrder = order, folderId = folderId, enabled = on)

    private fun appRow(id: Long, pkg: String, order: Int, on: Boolean = true) =
        HomeEntry(id = id, modeId = "work", sortOrder = order, packageName = pkg, enabled = on)

    @Test
    fun `folder contents and single apps both count as reachable`() {
        val folders = listOf(folder(1, "Everyday", Pkg.GMAIL, Pkg.MAPS, Pkg.CHROME))
        val entries = listOf(appRow(1, Pkg.DIALER, 0), folderRow(2, 1, 1))

        val reachable = resolveHomeRows(entries, folders).flatMap { it.reachable }.toSet()
        assertEquals(setOf(Pkg.DIALER, Pkg.GMAIL, Pkg.MAPS, Pkg.CHROME), reachable)

        val m = mode()
        assertFalse(guarded(Pkg.MAPS, m, reachable))
        assertTrue(guarded(Pkg.INSTAGRAM, m, reachable))
    }

    @Test
    fun `a folder switched off for this mode is not reachable`() {
        // The whole point of per-mode switches: Social exists, Work does not
        // turn it on, so Work will not open Instagram.
        val folders = listOf(folder(4, "Social", Pkg.INSTAGRAM, Pkg.REDDIT))
        val entries = listOf(folderRow(1, 4, 0, on = false))

        val reachable = resolveHomeRows(entries, folders).flatMap { it.reachable }.toSet()
        assertTrue("a folder that is off contributes nothing", reachable.isEmpty())
        assertTrue(guarded(Pkg.INSTAGRAM, mode(), reachable))
    }

    @Test
    fun `switching the same folder on makes it reachable again`() {
        val folders = listOf(folder(4, "Social", Pkg.INSTAGRAM))
        val off = resolveHomeRows(listOf(folderRow(1, 4, 0, on = false)), folders)
            .flatMap { it.reachable }.toSet()
        val on = resolveHomeRows(listOf(folderRow(1, 4, 0, on = true)), folders)
            .flatMap { it.reachable }.toSet()

        assertTrue(guarded(Pkg.INSTAGRAM, mode(), off))
        assertFalse(guarded(Pkg.INSTAGRAM, mode(), on))
    }

    @Test
    fun `a single app row can be switched off too`() {
        val entries = listOf(appRow(1, Pkg.CHROME, 0, on = false))
        val rows = resolveHomeRows(entries, emptyList())
        assertTrue(rows.single().reachable.isEmpty())
    }

    @Test
    fun `folders are shared, so one edit reaches every mode that has it on`() {
        val shared = folder(3, "Money", Pkg.CHASE, Pkg.VENMO)
        val work = resolveHomeRows(listOf(folderRow(1, 3, 0)), listOf(shared))
        val personal = resolveHomeRows(
            listOf(HomeEntry(id = 9, modeId = "personal", folderId = 3)),
            listOf(shared)
        )
        assertEquals(work.single().packages, personal.single().packages)

        val edited = shared.copy(packages = shared.packages + Pkg.PAYPAL)
        val after = resolveHomeRows(listOf(folderRow(1, 3, 0)), listOf(edited))
        assertTrue(after.single().packages.contains(Pkg.PAYPAL))
    }

    @Test
    fun `rows come back in sort order`() {
        val folders = listOf(folder(1, "Everyday", Pkg.GMAIL))
        val entries = listOf(
            folderRow(3, 1, 2),
            appRow(1, Pkg.DIALER, 0),
            appRow(2, Pkg.MESSAGES, 1)
        )
        val names = resolveHomeRows(entries, folders).map { it.name }
        assertEquals(listOf(Pkg.DIALER, Pkg.MESSAGES, "Everyday"), names)
    }

    @Test
    fun `a row pointing at a deleted folder is dropped, not crashed on`() {
        val entries = listOf(folderRow(1, 99, 0), appRow(2, Pkg.DIALER, 1))
        val rows = resolveHomeRows(entries, emptyList())
        assertEquals(1, rows.size)
        assertEquals(Pkg.DIALER, rows.single().name)
    }

    @Test
    fun `an empty folder contributes nothing`() {
        val rows = resolveHomeRows(listOf(folderRow(1, 5, 0)), listOf(folder(5, "Empty")))
        assertTrue(rows.single().reachable.isEmpty())
    }

    @Test
    fun `a folder row is a folder and an app row is not`() {
        val rows = resolveHomeRows(
            listOf(appRow(1, Pkg.DIALER, 0), folderRow(2, 3, 1)),
            listOf(folder(3, "Money", Pkg.CHASE))
        )
        assertFalse(rows[0].isFolder)
        assertTrue(rows[1].isFolder)
        assertEquals(listOf(Pkg.DIALER), rows[0].reachable)
        assertEquals(listOf(Pkg.CHASE), rows[1].reachable)
    }
}
