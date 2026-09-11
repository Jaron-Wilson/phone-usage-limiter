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

    /**
     * Time first, always. Something at 09:00 never hides under something at
     * 14:00 because it was marked important; that would make the agenda a
     * ranking rather than a day.
     *
     * Within the same minute the order is: events whose title you marked worth
     * noticing, then calendars in your stated order, then the title and id so
     * the list never reshuffles between redraws.
     */
    fun sort(
        events: List<CalEvent>,
        priority: List<Long>,
        highlight: Regex? = null
    ): List<CalEvent> =
        events.sortedWith(
            compareBy<CalEvent> { it.begin }
                .thenBy { if (highlight?.containsMatchIn(it.title) == true) 0 else 1 }
                .thenBy { rank(it.calendarId, priority) }
                .thenBy { it.title }
                .thenBy { it.eventId }
        )

    /**
     * The one to put in the headline: whatever is running, else the next thing
     * due, with the same preference breaking ties.
     */
    fun headline(
        events: List<CalEvent>,
        now: Long,
        priority: List<Long>,
        highlight: Regex? = null
    ): CalEvent? {
        val running = sort(
            events.filter { !it.allDay && it.begin <= now && it.end > now }, priority, highlight
        )
        if (running.isNotEmpty()) return running.first()
        return sort(
            events.filter { !it.allDay && it.begin > now }, priority, highlight
        ).firstOrNull()
    }
}
