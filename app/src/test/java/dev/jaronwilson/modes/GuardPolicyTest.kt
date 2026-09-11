package dev.jaronwilson.modes

import dev.jaronwilson.modes.core.Pkg
import dev.jaronwilson.modes.core.model.GuardMode
import dev.jaronwilson.modes.core.model.GuardScope
import dev.jaronwilson.modes.core.model.HomeEntry
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

    // ---- home entries feed the allowlist ----

    @Test
    fun `folder contents and single apps both count as reachable`() {
        val entries = listOf(
            HomeEntry(id = 1, modeId = "work", sortOrder = 0, packageName = Pkg.DIALER),
            HomeEntry(
                id = 2, modeId = "work", sortOrder = 1,
                folderName = "Everyday",
                packages = listOf(Pkg.GMAIL, Pkg.MAPS, Pkg.CHROME)
            )
        )
        val reachable = entries.flatMap { it.reachable }.toSet()
        assertEquals(setOf(Pkg.DIALER, Pkg.GMAIL, Pkg.MAPS, Pkg.CHROME), reachable)

        val m = mode()
        assertFalse(guarded(Pkg.MAPS, m, reachable))
        assertTrue(guarded(Pkg.INSTAGRAM, m, reachable))
    }

    @Test
    fun `a folder row is a folder and an app row is not`() {
        val app = HomeEntry(modeId = "work", packageName = Pkg.DIALER)
        val folder = HomeEntry(modeId = "work", folderName = "Money", packages = listOf(Pkg.CHASE))
        assertFalse(app.isFolder)
        assertTrue(folder.isFolder)
        assertEquals(listOf(Pkg.DIALER), app.reachable)
        assertEquals(listOf(Pkg.CHASE), folder.reachable)
    }

    @Test
    fun `an empty folder contributes nothing`() {
        val folder = HomeEntry(modeId = "work", folderName = "Empty")
        assertTrue(folder.reachable.isEmpty())
    }
}
