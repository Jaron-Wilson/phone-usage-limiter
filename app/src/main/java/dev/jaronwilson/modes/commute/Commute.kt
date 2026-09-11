package dev.jaronwilson.modes.commute

import dev.jaronwilson.modes.schedule.CalEvent

/**
 * Working out when to leave.
 *
 * Deliberately free of Android and of any network, because this is arithmetic
 * about someone's morning and it should be testable without either.
 *
 * The chain, backwards from the event:
 *
 *   event starts            09:00
 *   - arriveEarly  (10m)    08:50   be there, parked, walking in
 *   - travel       (25m)    08:25   the drive itself
 *   = leave at              08:25
 *   - getReady      (5m)    08:20   the nudge, so leaving at 08:25 is possible
 */
data class Plan(
    val event: CalEvent,
    val destination: String,
    /** When you must actually be moving. */
    val leaveAt: Long,
    /** When to say something, a little before that. */
    val warnAt: Long,
    val travelMinutes: Long,
    val arriveEarlyMinutes: Long
) {
    val minutesOfWarning: Long get() = (leaveAt - warnAt) / 60_000
}

data class CommuteSettings(
    val arriveEarlyMinutes: Long = 10,
    val getReadyMinutes: Long = 5,
    val defaultTravelMinutes: Long = 20
)

object Commute {

    /**
     * A plan for the next event worth driving to, or null.
     *
     * An event only qualifies if it has somewhere to go and has not already
     * started. All-day events are skipped: a deadline is not a journey.
     */
    fun nextPlan(
        events: List<CalEvent>,
        now: Long,
        settings: CommuteSettings,
        travelMinutesFor: (CalEvent) -> Long? = { null }
    ): Plan? = events
        .asSequence()
        .filter { !it.allDay }
        .filter { it.begin > now }
        .filter { it.location.isNotBlank() }
        .sortedBy { it.begin }
        .mapNotNull { event ->
            val travel = travelMinutesFor(event) ?: settings.defaultTravelMinutes
            val leaveAt = event.begin -
                (settings.arriveEarlyMinutes + travel) * 60_000L
            val warnAt = leaveAt - settings.getReadyMinutes * 60_000L
            Plan(
                event = event,
                destination = event.location,
                leaveAt = leaveAt,
                warnAt = warnAt,
                travelMinutes = travel,
                arriveEarlyMinutes = settings.arriveEarlyMinutes
            )
        }
        // Something you should already have left for is not worth an alarm;
        // saying "leave in -20 minutes" helps nobody.
        .firstOrNull { it.leaveAt > now }

    /** How the nudge should read, given how long is left. */
    fun phrase(plan: Plan, now: Long): String {
        val minutes = ((plan.leaveAt - now) / 60_000).coerceAtLeast(0)
        return when {
            minutes <= 0 -> "Leave now for ${plan.event.title}"
            minutes == 1L -> "Leave in a minute for ${plan.event.title}"
            else -> "Leave in $minutes minutes for ${plan.event.title}"
        }
    }
}
