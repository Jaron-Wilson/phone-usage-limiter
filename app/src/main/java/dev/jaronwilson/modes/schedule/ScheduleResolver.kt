package dev.jaronwilson.modes.schedule

import dev.jaronwilson.modes.core.model.CalendarRule
import dev.jaronwilson.modes.core.model.ModeSource
import dev.jaronwilson.modes.core.model.TimeRule
import dev.jaronwilson.modes.core.repo.ModeRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class Decision(
    val modeId: String,
    val source: ModeSource,
    val reason: String,
    /** When this decision expires, or 0 for open-ended. */
    val until: Long
)

/**
 * Works out which mode should be running right now.
 *
 * Precedence, highest first:
 *   1. a manual override you set by hand
 *   2. a calendar event matching a calendar rule
 *   3. a time-of-day rule
 *   4. the default mode
 *
 * Ties inside a level are broken by the rule's own priority, then by the
 * shorter event: a 30-minute "deep work" block inside a 4-hour "work" block is
 * the more specific intent.
 */
class ScheduleResolver(
    private val repo: ModeRepository,
    private val calendar: CalendarSource,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {

    suspend fun resolve(now: Long = System.currentTimeMillis()): Decision {
        val (manualMode, manualUntil) = currentManual()
        if (manualMode != null && (manualUntil == 0L || manualUntil > now)) {
            return Decision(
                modeId = manualMode,
                source = ModeSource.MANUAL,
                reason = if (manualUntil == 0L) "Set by hand" else "Set by hand, until the next change",
                until = manualUntil
            )
        }

        calendarDecision(now)?.let { return it }
        timeDecision(now)?.let { return it }

        val fallback = repo.defaultMode()
        return Decision(fallback.id, ModeSource.DEFAULT, "Nothing scheduled", 0L)
    }

    private suspend fun currentManual(): Pair<String?, Long> =
        repo.settings.manualOverride.first()

    private suspend fun calendarDecision(now: Long): Decision? {
        val rules = repo.ruleDao.activeCalendarRules()
        if (rules.isEmpty() || !calendar.hasPermission) return null
        // A small window around now; instances are cheap to query.
        val events = calendar.events(now - 1, now + 1)
            .filter { it.begin <= now && it.end > now }
        if (events.isEmpty()) return null

        data class Hit(val rule: CalendarRule, val event: CalEvent)

        val hits = events.flatMap { event ->
            rules.mapNotNull { rule -> if (matches(rule, event)) Hit(rule, event) else null }
        }
        val best = hits.minWithOrNull(
            compareByDescending<Hit> { it.rule.priority }
                .thenBy { it.event.end - it.event.begin }
        ) ?: return null

        return Decision(
            modeId = best.rule.modeId,
            source = ModeSource.CALENDAR,
            reason = best.event.title,
            until = best.event.end
        )
    }

    private fun matches(rule: CalendarRule, event: CalEvent): Boolean {
        if (!rule.enabled) return false
        if (event.allDay && !rule.includeAllDay) return false
        if (rule.busyOnly && !event.busy) return false
        rule.calendarId?.let { if (it != event.calendarId) return false }
        val pattern = rule.titlePattern
        if (!pattern.isNullOrBlank()) {
            val regex = runCatching { Regex(pattern) }.getOrNull() ?: return false
            if (!regex.containsMatchIn(event.title)) return false
        }
        return true
    }

    private suspend fun timeDecision(now: Long): Decision? {
        val rules = repo.ruleDao.activeTimeRules()
        if (rules.isEmpty()) return null
        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone())
        val active = rules.filter { it.activeAt(zdt) }
        val best = active.maxByOrNull { it.priority } ?: return null
        return Decision(
            modeId = best.modeId,
            source = ModeSource.TIME_RULE,
            reason = best.note.ifBlank { "Scheduled ${formatMinute(best.startMinute)}-${formatMinute(best.endMinute)}" },
            until = best.nextEnd(zdt)
        )
    }

    /**
     * The next instant at which the answer could change, so the scheduler knows
     * when to wake up. Looks at manual expiry, calendar edges and time-rule
     * edges over the next day.
     */
    suspend fun nextBoundary(now: Long = System.currentTimeMillis()): Long {
        val candidates = mutableListOf<Long>()

        val (_, manualUntil) = currentManual()
        if (manualUntil > now) candidates += manualUntil

        if (calendar.hasPermission) {
            val horizon = now + HORIZON_MS
            calendar.events(now, horizon).forEach { event ->
                if (event.begin > now) candidates += event.begin
                if (event.end > now) candidates += event.end
            }
        }

        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone())
        repo.ruleDao.activeTimeRules().forEach { rule ->
            candidates += rule.edgesAfter(zdt)
        }

        return candidates.filter { it > now }.minOrNull() ?: (now + FALLBACK_MS)
    }

    private fun formatMinute(m: Int) = "%02d:%02d".format(m / 60, m % 60)

    companion object {
        private const val HORIZON_MS = 26 * 60 * 60 * 1000L
        private const val FALLBACK_MS = 30 * 60 * 1000L
    }
}

private fun TimeRule.dayEnabled(bit: Int) = (daysMask shr bit) and 1 == 1

/** Monday is bit 0, matching [java.time.DayOfWeek.getValue] minus one. */
fun TimeRule.activeAt(zdt: ZonedDateTime): Boolean {
    if (!enabled) return false
    val minute = zdt.hour * 60 + zdt.minute
    val today = zdt.dayOfWeek.value - 1
    val yesterday = (today + 6) % 7
    return if (startMinute <= endMinute) {
        dayEnabled(today) && minute >= startMinute && minute < endMinute
    } else {
        // Window crosses midnight: either it started today, or it started
        // yesterday and has not ended yet.
        (dayEnabled(today) && minute >= startMinute) ||
            (dayEnabled(yesterday) && minute < endMinute)
    }
}

/** When the currently-running instance of this rule finishes. */
fun TimeRule.nextEnd(zdt: ZonedDateTime): Long {
    val minute = zdt.hour * 60 + zdt.minute
    val base = zdt.toLocalDate()
    val day = if (startMinute <= endMinute || minute < endMinute) base else base.plusDays(1)
    return day.atStartOfDay(zdt.zone).plusMinutes(endMinute.toLong()).toInstant().toEpochMilli()
}

/** Every start/end edge of this rule over the next two days. */
fun TimeRule.edgesAfter(zdt: ZonedDateTime): List<Long> {
    val out = mutableListOf<Long>()
    val today: LocalDate = zdt.toLocalDate()
    for (offset in 0..2L) {
        val day = today.plusDays(offset).atStartOfDay(zdt.zone)
        out += day.plusMinutes(startMinute.toLong()).toInstant().toEpochMilli()
        out += day.plusMinutes(endMinute.toLong()).toInstant().toEpochMilli()
    }
    return out
}
