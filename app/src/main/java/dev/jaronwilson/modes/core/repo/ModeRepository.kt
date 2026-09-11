package dev.jaronwilson.modes.core.repo

import android.content.Context
import dev.jaronwilson.modes.core.Defaults
import dev.jaronwilson.modes.core.db.ModesDatabase
import dev.jaronwilson.modes.launcher.AppList
import dev.jaronwilson.modes.core.model.Folder
import dev.jaronwilson.modes.core.model.HomeEntry
import dev.jaronwilson.modes.core.model.HomeRow
import dev.jaronwilson.modes.core.model.Mode
import dev.jaronwilson.modes.core.model.pruned
import dev.jaronwilson.modes.core.model.resolveHomeRows
import dev.jaronwilson.modes.core.model.NotifRule
import dev.jaronwilson.modes.core.model.Vip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Everything a decision needs, in one immutable object.
 *
 * The notification gate has to answer "does this get through?" on the main
 * thread in a few microseconds, so it reads this cached snapshot rather than
 * touching the database. The snapshot is refreshed whenever anything it
 * depends on changes.
 */
data class PolicySnapshot(
    val mode: Mode,
    val notifRules: List<NotifRule>,
    val vips: List<Vip>,
    val gateEnabled: Boolean,
    val guardEnabled: Boolean,
    /** The active mode's home screen, in order, folders resolved. */
    val homeRows: List<HomeRow> = emptyList(),
    /** Apps exempt from every mode's guard, set by hand. */
    val alwaysAllowed: Set<String> = emptySet()
) {
    /**
     * Every package reachable from the current home screen. Under
     * [dev.jaronwilson.modes.core.model.GuardScope.ALLOWLIST] this is exactly
     * the set of apps the mode permits, which is why arranging your folders and
     * choosing what you are allowed to open are the same action.
     *
     * A folder switched off for this mode contributes nothing, by way of
     * [HomeRow.reachable].
     */
    val homePackages: Set<String> =
        homeRows.flatMap { it.reachable }.toSet()

    /** Pre-compiled so matching a notification does not recompile regexes. */
    val compiledRules: List<Pair<NotifRule, Regex>> = notifRules.mapNotNull { rule ->
        runCatching { rule to Regex(rule.pattern) }.getOrNull()
    }
    val compiledVips: List<Regex> = vips.mapNotNull { vip ->
        runCatching { Regex(vip.pattern, RegexOption.IGNORE_CASE) }.getOrNull()
    }
}

class ModeRepository(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val db = ModesDatabase.get(context)
    val modeDao = db.modeDao()
    val ruleDao = db.ruleDao()
    val heldDao = db.heldDao()
    val homeDao = db.homeDao()
    val folderDao = db.folderDao()
    val eventDao = db.eventDao()
    val passDao = db.passDao()
    val settings = SettingsStore(context)

    /** Last known policy. Safe to read from any thread. */
    @Volatile
    var snapshot: PolicySnapshot? = null
        private set

    val modes: Flow<List<Mode>> = modeDao.observeAll()

    val activeMode: Flow<Mode?> = settings.active.map { modeDao.get(it.modeId) }

    val policy: Flow<PolicySnapshot?> = combine(
        settings.active,
        modeDao.observeAll(),
        ruleDao.observeNotifRules(),
        ruleDao.observeVips(),
        combine(
            settings.gateEnabled,
            settings.guardEnabled,
            homeDao.observeAll(),
            folderDao.observeAll(),
            settings.alwaysAllowed
        ) { gate, guard, home, folders, allowed -> Extras(gate, guard, home, folders, allowed) }
    ) { active, allModes, rules, vips, extra ->
        val mode = allModes.firstOrNull { it.id == active.modeId }
            ?: allModes.firstOrNull { it.isDefault }
            ?: allModes.firstOrNull()
            ?: return@combine null
        PolicySnapshot(
            mode = mode,
            notifRules = rules.filter { it.enabled }.sortedByDescending { it.priority },
            vips = vips.filter { it.enabled },
            gateEnabled = extra.gate,
            guardEnabled = extra.guard,
            homeRows = resolveHomeRows(
                extra.home.filter { it.modeId == mode.id },
                extra.folders
            ),
            alwaysAllowed = extra.allowed
        )
    }

    private data class Extras(
        val gate: Boolean,
        val guard: Boolean,
        val home: List<HomeEntry>,
        val folders: List<Folder>,
        val allowed: Set<String>
    )

    fun startCaching() {
        scope.launch {
            policy.collect { snapshot = it }
        }
    }

    /** Blocking-safe read for services that start before the cache warms up. */
    suspend fun policyNow(): PolicySnapshot? = snapshot ?: policy.first()

    private val seedLock = Mutex()

    /**
     * Seeding is single-flight.
     *
     * Three components race to call this at startup: the application, the
     * notification listener when the system binds it, and the boot receiver.
     * Without the lock, two of them can both observe an empty table and both
     * insert, which is how every home row ended up on screen twice.
     */
    suspend fun seedIfEmpty() = seedLock.withLock { seedIfEmptyLocked() }

    private suspend fun seedIfEmptyLocked() {
        if (modeDao.count() == 0) modeDao.upsertAll(Defaults.modes())
        if (ruleDao.notifRuleCount() == 0) Defaults.notifRules().forEach { ruleDao.upsert(it) }
        if (ruleDao.timeRuleCount() == 0) Defaults.timeRules().forEach { ruleDao.upsert(it) }
        if (ruleDao.calendarRuleCount() == 0) Defaults.calendarRules().forEach { ruleDao.upsert(it) }
        if (folderDao.count() == 0) folderDao.upsertAll(seedFolders())
        if (homeDao.count() == 0) homeDao.upsertAll(Defaults.homeEntries())
        dedupeHomeEntries()
        settings.setSeeded(true)
    }

    /**
     * Removes rows that say the same thing twice, keeping the earliest of each.
     *
     * Repairs phones that seeded twice before the lock existed, without
     * throwing away anything the owner arranged by hand.
     */
    suspend fun dedupeHomeEntries(): Int {
        val all = homeDao.observeAll().first()
        val duplicates = findDuplicates(all)
        duplicates.forEach { homeDao.delete(it) }
        return duplicates.size
    }

    /** Reset rules and modes to the shipped defaults, keeping held history. */
    suspend fun restoreDefaults() {
        modeDao.upsertAll(Defaults.modes())
        ruleDao.observeNotifRules().first().forEach { ruleDao.delete(it) }
        Defaults.notifRules().forEach { ruleDao.upsert(it) }
        ruleDao.observeTimeRules().first().forEach { ruleDao.delete(it) }
        Defaults.timeRules().forEach { ruleDao.upsert(it) }
        ruleDao.observeCalendarRules().first().forEach { ruleDao.delete(it) }
        Defaults.calendarRules().forEach { ruleDao.upsert(it) }
        modeDao.getAll().forEach { homeDao.clearMode(it.id) }
        folderDao.upsertAll(seedFolders())
        homeDao.upsertAll(Defaults.homeEntries())
    }

    /**
     * The shipped folders, cut down to apps this phone actually has.
     *
     * The defaults name every app the build knows about, which is the only way
     * to be useful without knowing whose phone this is. Pruning at seed time
     * turns that long generic list into a short accurate one, so nobody opens
     * a Money folder listing twenty banks to find the four they use.
     */
    private fun seedFolders() = Defaults.folders().map { folder ->
        folder.pruned { AppList.isInstalled(context, it) }
    }

    suspend fun mode(id: String): Mode? = modeDao.get(id)

    suspend fun defaultMode(): Mode =
        modeDao.getDefault() ?: modeDao.getAll().first()
}

/**
 * Given every home row, the ones to delete so each mode holds each app or
 * folder once. The survivor is the lowest id, which is the one whose position
 * and switch the owner has been looking at.
 *
 * Pure, so the repair can be tested without a database.
 */
fun findDuplicates(entries: List<HomeEntry>): List<HomeEntry> =
    entries
        .groupBy { Triple(it.modeId, it.packageName, it.folderId) }
        .values
        .filter { it.size > 1 }
        .flatMap { group -> group.sortedBy { it.id }.drop(1) }
