package dev.jaronwilson.modes.schedule

/**
 * The order the day is read in.
 *
 * Time decides almost everything, but not quite: two things at 10:00 have to
 * be printed in some order, and left to the provider that order is arbitrary.
 * A stated preference between calendars settles it, so the class you must
 * attend sits above the tailgate you might.
 */
object AgendaOrder {

    /** Calendars named in [priority] come first, in that order; the rest follow. */
    fun rank(calendarId: Long, priority: List<Long>): Int {
        val index = priority.indexOf(calendarId)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    fun sort(events: List<CalEvent>, priority: List<Long>): List<CalEvent> =
        events.sortedWith(
            compareBy<CalEvent> { it.begin }
                .thenBy { rank(it.calendarId, priority) }
                // Last resort so the list never reshuffles between redraws.
                .thenBy { it.title }
                .thenBy { it.eventId }
        )

    /**
     * The one to put in the headline: whatever is running, else the next thing
     * due, with the same preference breaking ties.
     */
    fun headline(events: List<CalEvent>, now: Long, priority: List<Long>): CalEvent? {
        val running = sort(events.filter { !it.allDay && it.begin <= now && it.end > now }, priority)
        if (running.isNotEmpty()) return running.first()
        return sort(events.filter { !it.allDay && it.begin > now }, priority).firstOrNull()
    }
}
