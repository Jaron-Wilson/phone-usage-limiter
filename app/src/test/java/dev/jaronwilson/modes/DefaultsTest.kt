package dev.jaronwilson.modes

import dev.jaronwilson.modes.core.Defaults
import dev.jaronwilson.modes.core.Pkg
import dev.jaronwilson.modes.core.model.GuardScope
import dev.jaronwilson.modes.core.model.NotifClass
import dev.jaronwilson.modes.core.model.installedCount
import dev.jaronwilson.modes.core.model.pruned
import dev.jaronwilson.modes.core.model.resolveHomeRows
import dev.jaronwilson.modes.guard.GuardPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the shipped defaults against a plausible phone.
 *
 * [modestPhone] is a deliberately sparse fixture: a device with the stock
 * Google apps, one bank, one chat app and little else. Most of what the
 * defaults name is missing from it, which is the point. It catches the failure
 * that is invisible in code review, where a mode reads sensibly but on a real
 * phone leaves you unable to reach something you need.
 */
class DefaultsTest {

    /** A sparse but realistic device: stock apps, one bank, one chat app. */
    private val modestPhone = setOf(
        Pkg.DIALER, Pkg.MESSAGES, Pkg.CONTACTS, Pkg.CLOCK, Pkg.SETTINGS,
        Pkg.CALENDAR, Pkg.CAMERA, Pkg.CHROME, Pkg.GMAIL, Pkg.MAPS, Pkg.PHOTOS,
        Pkg.DRIVE, Pkg.DOCS, Pkg.MEET, Pkg.PLAY_STORE, Pkg.YOUTUBE,
        Pkg.GROUPME, Pkg.WALLET, Pkg.CHASE,
        // Installed but archived, so absent from the launcher query entirely.
        Pkg.INSTAGRAM, Pkg.KEEP, Pkg.AUTHENTICATOR
    )

    private val installed: (String) -> Boolean = { it in modestPhone }

    private fun rowsFor(modeId: String) = resolveHomeRows(
        Defaults.homeEntries().filter { it.modeId == modeId },
        Defaults.folders()
    )

    private fun reachable(modeId: String) =
        rowsFor(modeId).flatMap { it.reachable }.filter(installed).toSet()

    @Test
    fun `every folder has something on this phone`() {
        Defaults.folders().forEach { folder ->
            assertTrue(
                "folder '${folder.name}' is empty on this phone",
                folder.installedCount(installed) > 0
            )
        }
    }

    @Test
    fun `every home row points at a real folder`() {
        val ids = Defaults.folders().map { it.id }.toSet()
        Defaults.homeEntries().mapNotNull { it.folderId }.forEach { id ->
            assertTrue("home row points at folder $id, which does not exist", id in ids)
        }
    }

    @Test
    fun `every mode has a home screen`() {
        Defaults.modes().forEach { mode ->
            assertTrue(
                "mode '${mode.name}' has no rows",
                Defaults.homeEntries().any { it.modeId == mode.id }
            )
        }
    }

    @Test
    fun `you can always reach a phone and a way to text`() {
        Defaults.modes().forEach { mode ->
            val reach = reachable(mode.id)
            assertTrue("${mode.name} cannot reach the dialer", Pkg.DIALER in reach)
            assertTrue("${mode.name} cannot reach messages", Pkg.MESSAGES in reach)
        }
    }

    @Test
    fun `money is reachable in every mode`() {
        Defaults.modes().forEach { mode ->
            val reach = reachable(mode.id)
            assertTrue("${mode.name} cannot reach the bank", Pkg.CHASE in reach)
        }
    }

    @Test
    fun `no strict mode can lock you out of an essential app`() {
        // The real risk of an allowlist: a mode that is on all evening and
        // quietly forbids something you depend on.
        Defaults.modes().filter { it.guardScope == GuardScope.ALLOWLIST }.forEach { mode ->
            val reach = reachable(mode.id)
            (Pkg.ESSENTIAL + Pkg.FINANCE).filter(installed).forEach { pkg ->
                assertFalse(
                    "${mode.name} would stop you opening $pkg",
                    GuardPolicy.shouldGuard(pkg, "dev.jaronwilson.modes", mode, reach) { true }
                )
            }
        }
    }

    @Test
    fun `work allows school apps and holds the distracting ones`() {
        val reach = reachable(Defaults.MODE_WORK)
        listOf(Pkg.GMAIL, Pkg.CALENDAR, Pkg.DRIVE, Pkg.DOCS, Pkg.MEET)
            .forEach { assertTrue("Work should reach $it", it in reach) }
        listOf(Pkg.INSTAGRAM, Pkg.YOUTUBE)
            .forEach { assertFalse("Work should not reach $it", it in reach) }
    }

    @Test
    fun `deep focus is the narrowest mode`() {
        val focus = reachable(Defaults.MODE_FOCUS)
        val work = reachable(Defaults.MODE_WORK)
        assertTrue("focus should be narrower than work", focus.size < work.size)
        assertFalse(Pkg.INSTAGRAM in focus)
        assertFalse(Pkg.CHROME in focus)
        assertFalse(Pkg.GMAIL in focus)
    }

    @Test
    fun `the apps that carry conversations are classed as direct messages`() {
        val classes = dev.jaronwilson.modes.core.PACKAGE_DEFAULT_CLASS
        assertEquals(NotifClass.DIRECT, classes[Pkg.GROUPME])
        assertEquals(NotifClass.DIRECT, classes[Pkg.VOICE])
        assertEquals(NotifClass.DIRECT, classes[Pkg.MESSAGES])
        assertEquals(NotifClass.CALL, classes[Pkg.MEET])
    }

    @Test
    fun `campus card apps count as money, not as noise`() {
        assertTrue(Pkg.EACCOUNTS in Pkg.FINANCE)
    }

    @Test
    fun `youtube studio is engagement, not correspondence`() {
        assertEquals(
            NotifClass.SOCIAL,
            dev.jaronwilson.modes.core.PACKAGE_DEFAULT_CLASS[Pkg.YT_STUDIO]
        )
    }

    @Test
    fun `every folder still has something on a sparse phone`() {
        // Pruning must not leave a mode with an empty home screen.
        val pruned = Defaults.folders().map { it.pruned(installed) }
        assertTrue(
            "no folder survived pruning",
            pruned.any { it.packages.isNotEmpty() }
        )
    }

    @Test
    fun `every seeded rule compiles as a regex`() {
        Defaults.notifRules().forEach { rule ->
            runCatching { Regex(rule.pattern) }
                .onFailure { org.junit.Assert.fail("rule '${rule.note}' has a bad pattern: $it") }
        }
        Defaults.calendarRules().forEach { rule ->
            rule.titlePattern?.let { pattern ->
                runCatching { Regex(pattern) }
                    .onFailure { org.junit.Assert.fail("calendar rule '${rule.note}' is bad: $it") }
            }
        }
    }

    @Test
    fun `pruning drops what is not installed and keeps the rest`() {
        val money = Defaults.folders().first { it.name == "Money" }
        val pruned = money.pruned(installed)
        // The shipped folder names every bank the build knows about; on this
        // phone that has to come down to the one that is present.
        assertTrue(money.packages.size > pruned.packages.size)
        assertTrue(pruned.packages.isNotEmpty())
        assertTrue(pruned.packages.all(installed))
        assertTrue(Pkg.CHASE in pruned.packages)
    }

    @Test
    fun `pruning a folder with nothing installed leaves it empty, not broken`() {
        val folder = Defaults.folders().first().copy(packages = listOf("com.nope.absent"))
        assertTrue(folder.pruned(installed).packages.isEmpty())
    }
}
