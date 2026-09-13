package dev.jaronwilson.modes.core.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.jaronwilson.modes.commute.Destination
import dev.jaronwilson.modes.commute.Destinations
import dev.jaronwilson.modes.launcher.Edge
import dev.jaronwilson.modes.launcher.EdgePanels
import dev.jaronwilson.modes.launcher.EdgeTarget
import dev.jaronwilson.modes.core.Defaults
import dev.jaronwilson.modes.core.Pkg
import dev.jaronwilson.modes.core.model.ModeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "modes_settings")

/** Small, flat state that is not worth a database table. */
class SettingsStore(private val context: Context) {

    private object K {
        val ACTIVE_MODE = stringPreferencesKey("active_mode")
        val ACTIVE_SOURCE = stringPreferencesKey("active_source")
        val ACTIVE_REASON = stringPreferencesKey("active_reason")
        val ACTIVE_UNTIL = longPreferencesKey("active_until")
        val MANUAL_MODE = stringPreferencesKey("manual_mode")
        val MANUAL_UNTIL = longPreferencesKey("manual_until")
        val ZEN_RULE_IDS = stringPreferencesKey("zen_rule_ids")
        val GATE_ENABLED = booleanPreferencesKey("gate_enabled")
        val GUARD_ENABLED = booleanPreferencesKey("guard_enabled")
        val SEEDED = booleanPreferencesKey("seeded")
        val SETUP_DONE = booleanPreferencesKey("setup_done")
        val LAST_DIGEST = longPreferencesKey("last_digest")
        val AGENDA_HIGHLIGHT = stringPreferencesKey("agenda_highlight")
        val ALWAYS_ALLOWED = stringPreferencesKey("always_allowed")
        val HOME_ADDRESS = stringPreferencesKey("home_address")
        val ARRIVE_EARLY = longPreferencesKey("arrive_early_minutes")
        val GET_READY = longPreferencesKey("get_ready_minutes")
        val DEFAULT_TRAVEL = longPreferencesKey("default_travel_minutes")
        val COMMUTE_ENABLED = booleanPreferencesKey("commute_enabled")
        val DESTINATIONS = stringPreferencesKey("destinations")
        val EDGE_LEFT = stringPreferencesKey("edge_left")
        val EDGE_RIGHT = stringPreferencesKey("edge_right")
        val TAP_CARD_URL = stringPreferencesKey("tap_card_url")
        val DRAWER_FOLDERS = stringPreferencesKey("drawer_folders")
        val CALENDAR_PRIORITY = stringPreferencesKey("calendar_priority")
        val PLACE_MODE = stringPreferencesKey("place_mode")
        val PLACE_NAME = stringPreferencesKey("place_name")
        val PLACE_UNTIL = longPreferencesKey("place_until")
    }

    data class ActiveState(
        val modeId: String = Defaults.MODE_OPEN,
        val source: ModeSource = ModeSource.DEFAULT,
        /** Human-readable explanation, e.g. the calendar event that caused it. */
        val reason: String = "",
        /** When this decision stops being valid, or 0 if open-ended. */
        val until: Long = 0L
    )

    val active: Flow<ActiveState> = context.dataStore.data.map { p ->
        ActiveState(
            modeId = p[K.ACTIVE_MODE] ?: Defaults.MODE_OPEN,
            source = runCatching { ModeSource.valueOf(p[K.ACTIVE_SOURCE] ?: "") }
                .getOrDefault(ModeSource.DEFAULT),
            reason = p[K.ACTIVE_REASON].orEmpty(),
            until = p[K.ACTIVE_UNTIL] ?: 0L
        )
    }

    suspend fun activeNow(): ActiveState = active.first()

    suspend fun setActive(state: ActiveState) {
        context.dataStore.edit { p ->
            p[K.ACTIVE_MODE] = state.modeId
            p[K.ACTIVE_SOURCE] = state.source.name
            p[K.ACTIVE_REASON] = state.reason
            p[K.ACTIVE_UNTIL] = state.until
        }
    }

    /** Manual override: pinned mode and the moment it lapses (0 = until next change). */
    val manualOverride: Flow<Pair<String?, Long>> = context.dataStore.data.map { p ->
        p[K.MANUAL_MODE] to (p[K.MANUAL_UNTIL] ?: 0L)
    }

    /**
     * The mode a place you are standing in asks for, if any, with the place's
     * name and a freshness deadline. Written by the location check and read by
     * the resolver, exactly like the manual override, so the resolver stays
     * pure and never touches a radio.
     */
    val placeOverride: Flow<Triple<String?, String, Long>> = context.dataStore.data.map { p ->
        Triple(p[K.PLACE_MODE], p[K.PLACE_NAME].orEmpty(), p[K.PLACE_UNTIL] ?: 0L)
    }
    suspend fun setPlaceOverride(modeId: String?, name: String, until: Long) {
        context.dataStore.edit { p ->
            if (modeId == null) {
                p.remove(K.PLACE_MODE); p.remove(K.PLACE_NAME); p.remove(K.PLACE_UNTIL)
            } else {
                p[K.PLACE_MODE] = modeId; p[K.PLACE_NAME] = name; p[K.PLACE_UNTIL] = until
            }
        }
    }

    suspend fun setManualOverride(modeId: String?, until: Long) {
        context.dataStore.edit { p ->
            if (modeId == null) {
                p.remove(K.MANUAL_MODE)
                p.remove(K.MANUAL_UNTIL)
            } else {
                p[K.MANUAL_MODE] = modeId
                p[K.MANUAL_UNTIL] = until
            }
        }
    }

    /** modeId -> the id of the AutomaticZenRule we registered for it. */
    suspend fun zenRuleIds(): Map<String, String> =
        decodeMap(context.dataStore.data.first()[K.ZEN_RULE_IDS].orEmpty())

    suspend fun setZenRuleIds(map: Map<String, String>) {
        context.dataStore.edit { it[K.ZEN_RULE_IDS] = encodeMap(map) }
    }

    val gateEnabled: Flow<Boolean> = context.dataStore.data.map { it[K.GATE_ENABLED] ?: true }
    suspend fun setGateEnabled(v: Boolean) = edit { it[K.GATE_ENABLED] = v }

    val guardEnabled: Flow<Boolean> = context.dataStore.data.map { it[K.GUARD_ENABLED] ?: true }
    suspend fun setGuardEnabled(v: Boolean) = edit { it[K.GUARD_ENABLED] = v }

    /**
     * Events whose title matches this are drawn bold on the home screen.
     * Some things on a calendar you glance past; a shift you cannot miss is
     * not one of them.
     */
    val agendaHighlight: Flow<String> = context.dataStore.data.map {
        it[K.AGENDA_HIGHLIGHT] ?: DEFAULT_HIGHLIGHT
    }

    suspend fun setAgendaHighlight(v: String) = edit { it[K.AGENDA_HIGHLIGHT] = v }

    /**
     * Apps no mode ever gets to stop, on top of the built-in essentials.
     * For the handful of things that are neither distraction nor emergency but
     * still need to run whenever they feel like it.
     */
    val alwaysAllowed: Flow<Set<String>> = context.dataStore.data.map { p ->
        p[K.ALWAYS_ALLOWED].orEmpty().split("\n").filter { it.isNotBlank() }.toSet()
    }

    suspend fun setAlwaysAllowed(packages: Set<String>) =
        edit { it[K.ALWAYS_ALLOWED] = packages.joinToString("\n") }

    // ---- leaving on time ----
    val commuteEnabled: Flow<Boolean> = context.dataStore.data.map { it[K.COMMUTE_ENABLED] ?: false }
    suspend fun setCommuteEnabled(v: Boolean) = edit { it[K.COMMUTE_ENABLED] = v }

    val homeAddress: Flow<String> = context.dataStore.data.map { it[K.HOME_ADDRESS].orEmpty() }
    suspend fun setHomeAddress(v: String) = edit { it[K.HOME_ADDRESS] = v }

    /**
     * Calendar ids in the order they should win a tie. Anything unlisted sorts
     * after everything listed.
     */
    val calendarPriority: Flow<List<Long>> = context.dataStore.data.map { p ->
        p[K.CALENDAR_PRIORITY].orEmpty().split(",").mapNotNull { it.trim().toLongOrNull() }
    }

    suspend fun setCalendarPriority(ids: List<Long>) =
        edit { it[K.CALENDAR_PRIORITY] = ids.joinToString(",") }

    /** What a swipe in from each side of the home screen does. */
    val leftEdge: Flow<EdgeTarget?> = context.dataStore.data.map { p ->
        val stored = p[K.EDGE_LEFT]
        if (stored == null) DEFAULT_LEFT_EDGE else EdgePanels.decode(stored)
    }
    val rightEdge: Flow<EdgeTarget?> = context.dataStore.data.map { p ->
        val stored = p[K.EDGE_RIGHT]
        if (stored == null) DEFAULT_RIGHT_EDGE else EdgePanels.decode(stored)
    }

    suspend fun setEdge(edge: Edge, target: EdgeTarget?) = edit {
        val key = if (edge == Edge.LEFT) K.EDGE_LEFT else K.EDGE_RIGHT
        it[key] = EdgePanels.encode(target)
    }

    /** The link another phone reads when it taps yours. */
    val tapCardUrl: Flow<String> = context.dataStore.data.map {
        it[K.TAP_CARD_URL] ?: DEFAULT_TAP_CARD
    }
    suspend fun setTapCardUrl(v: String) = edit { it[K.TAP_CARD_URL] = v }

    /**
     * Folders shown at the top of the app drawer, in order.
     *
     * They come from the same library the home screen uses, so a folder made
     * while rummaging through every app is a folder you can then switch on for
     * a mode. Apps inside one are not listed again below it.
     */
    val drawerFolders: Flow<List<Long>> = context.dataStore.data.map { p ->
        p[K.DRAWER_FOLDERS].orEmpty().split(",").mapNotNull { it.trim().toLongOrNull() }
    }

    suspend fun setDrawerFolders(ids: List<Long>) =
        edit { it[K.DRAWER_FOLDERS] = ids.joinToString(",") }

    /** Places worth one tap from the home screen. */
    val destinations: Flow<List<Destination>> = context.dataStore.data.map {
        Destinations.decode(it[K.DESTINATIONS].orEmpty())
    }

    suspend fun setDestinations(list: List<Destination>) =
        edit { it[K.DESTINATIONS] = Destinations.encode(list) }

    /** Minutes you want to be there before it starts: parking, walking, settling. */
    val arriveEarlyMinutes: Flow<Long> = context.dataStore.data.map { it[K.ARRIVE_EARLY] ?: 10L }
    suspend fun setArriveEarlyMinutes(v: Long) = edit { it[K.ARRIVE_EARLY] = v }

    /** Minutes of warning before you actually have to move. */
    val getReadyMinutes: Flow<Long> = context.dataStore.data.map { it[K.GET_READY] ?: 5L }
    suspend fun setGetReadyMinutes(v: Long) = edit { it[K.GET_READY] = v }

    /** Fallback drive time when nothing better is known. */
    val defaultTravelMinutes: Flow<Long> = context.dataStore.data.map { it[K.DEFAULT_TRAVEL] ?: 20L }
    suspend fun setDefaultTravelMinutes(v: Long) = edit { it[K.DEFAULT_TRAVEL] = v }

    val setupDone: Flow<Boolean> = context.dataStore.data.map { it[K.SETUP_DONE] ?: false }
    suspend fun setSetupDone(v: Boolean) = edit { it[K.SETUP_DONE] = v }

    suspend fun isSeeded(): Boolean = context.dataStore.data.first()[K.SEEDED] ?: false
    suspend fun setSeeded(v: Boolean) = edit { it[K.SEEDED] = v }

    suspend fun lastDigestAt(): Long = context.dataStore.data.first()[K.LAST_DIGEST] ?: 0L
    suspend fun setLastDigestAt(v: Long) = edit { it[K.LAST_DIGEST] = v }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    companion object {
        const val DEFAULT_HIGHLIGHT = "(?i)\\bwork\\b"

        /** Your own site, until you say otherwise. */
        const val DEFAULT_TAP_CARD = "https://jaronwilson.org"

        // Seeded so a swipe does something the first time it is tried. Both
        // are changed, or cleared, under Rules.
        val DEFAULT_LEFT_EDGE: EdgeTarget = EdgeTarget.App(Pkg.CALENDAR)
        val DEFAULT_RIGHT_EDGE: EdgeTarget = EdgeTarget.App(Pkg.BOFA)
    }

    private fun encodeMap(map: Map<String, String>): String =
        map.entries.joinToString(";") { "${it.key}=${it.value}" }

    private fun decodeMap(s: String): Map<String, String> =
        if (s.isBlank()) emptyMap()
        else s.split(";").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
}
