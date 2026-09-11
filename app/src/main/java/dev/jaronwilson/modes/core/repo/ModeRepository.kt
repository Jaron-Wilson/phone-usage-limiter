package dev.jaronwilson.modes.core.repo

import android.content.Context
import dev.jaronwilson.modes.core.Defaults
import dev.jaronwilson.modes.core.db.ModesDatabase
import dev.jaronwilson.modes.core.model.HomeEntry
import dev.jaronwilson.modes.core.model.Mode
import dev.jaronwilson.modes.core.model.NotifRule
import dev.jaronwilson.modes.core.model.Vip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
    /** The active mode's home screen, in order. */
    val homeEntries: List<HomeEntry> = emptyList()
) {
    /**
     * Every package reachable from the current home screen. Under
     * [dev.jaronwilson.modes.core.model.GuardScope.ALLOWLIST] this is exactly
     * the set of apps the mode permits, which is why arranging your folders and
     * choosing what you are allowed to open are the same action.
     */
    val homePackages: Set<String> =
        homeEntries.flatMap { it.reachable }.toSet()

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
            homeDao.observeAll()
        ) { gate, guard, home -> Triple(gate, guard, home) }
    ) { active, allModes, rules, vips, extra ->
        val mode = allModes.firstOrNull { it.id == active.modeId }
            ?: allModes.firstOrNull { it.isDefault }
            ?: allModes.firstOrNull()
            ?: return@combine null
        PolicySnapshot(
            mode = mode,
            notifRules = rules.filter { it.enabled }.sortedByDescending { it.priority },
            vips = vips.filter { it.enabled },
            gateEnabled = extra.first,
            guardEnabled = extra.second,
            homeEntries = extra.third.filter { it.modeId == mode.id }.sortedBy { it.sortOrder }
        )
    }

    fun startCaching() {
        scope.launch {
            policy.collect { snapshot = it }
        }
    }

    /** Blocking-safe read for services that start before the cache warms up. */
    suspend fun policyNow(): PolicySnapshot? = snapshot ?: policy.first()

    suspend fun seedIfEmpty() {
        if (modeDao.count() == 0) modeDao.upsertAll(Defaults.modes())
        if (ruleDao.notifRuleCount() == 0) Defaults.notifRules().forEach { ruleDao.upsert(it) }
        if (ruleDao.timeRuleCount() == 0) Defaults.timeRules().forEach { ruleDao.upsert(it) }
        if (ruleDao.calendarRuleCount() == 0) Defaults.calendarRules().forEach { ruleDao.upsert(it) }
        if (homeDao.count() == 0) homeDao.upsertAll(Defaults.homeEntries())
        settings.setSeeded(true)
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
        homeDao.upsertAll(Defaults.homeEntries())
    }

    suspend fun mode(id: String): Mode? = modeDao.get(id)

    suspend fun defaultMode(): Mode =
        modeDao.getDefault() ?: modeDao.getAll().first()
}
