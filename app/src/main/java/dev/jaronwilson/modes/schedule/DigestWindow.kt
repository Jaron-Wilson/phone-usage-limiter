package dev.jaronwilson.modes.schedule

import dev.jaronwilson.modes.core.model.Mode
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Works out when a mode next hands back what it has been holding. */
object DigestWindow {

    fun next(mode: Mode, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (mode.digestEveryMinutes > 0) {
            return now + mode.digestEveryMinutes * 60_000L
        }
        if (mode.digestTimes.isEmpty()) return null

        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val today = zdt.toLocalDate()
        return mode.digestTimes
            .sorted()
            .flatMap { minute ->
                listOf(
                    today.atStartOfDay(zone).plusMinutes(minute.toLong()),
                    today.plusDays(1).atStartOfDay(zone).plusMinutes(minute.toLong())
                )
            }
            .map { it.toInstant().toEpochMilli() }
            .firstOrNull { it > now }
    }

    /**
     * How long a notification held right now should be kept out of the shade.
     * Capped, because the system puts an upper bound on how long it will hold a
     * snoozed notification for us.
     */
    fun holdDuration(mode: Mode, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val until = next(mode, now, zone) ?: (now + DEFAULT_HOLD_MS)
        return (until - now).coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
    }

    const val MIN_HOLD_MS = 60_000L

    /**
     * Android's snooze helper will not hold a notification indefinitely, so we
     * re-snooze in chunks: when a held notification reappears, the gate simply
     * evaluates it again and pushes it back out.
     */
    const val MAX_HOLD_MS = 2 * 60 * 60 * 1000L
    private const val DEFAULT_HOLD_MS = 60 * 60 * 1000L
}
