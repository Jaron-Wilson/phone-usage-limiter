package dev.jaronwilson.modes.schedule

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat

data class CalEvent(
    val eventId: Long,
    val calendarId: Long,
    val calendarName: String,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val busy: Boolean,
    /**
     * Julian day numbers of the first and last local day this instance touches,
     * inclusive, straight from the provider.
     *
     * Not derivable from [begin] and [end]. An all-day event is stored as UTC
     * midnight to UTC midnight, so an all-day Sunday event begins at 20:00 on
     * Saturday in New York and lands on the wrong day if you bucket by
     * milliseconds. The provider already did this arithmetic; use its answer.
     */
    val startDay: Int = 0,
    val endDay: Int = 0
) {
    /** Whether this instance appears on the given local day. */
    fun occursOn(julianDay: Int): Boolean =
        if (startDay == 0 && endDay == 0) false else julianDay in startDay..endDay
}

data class CalendarInfo(
    val id: Long,
    val name: String,
    val account: String,
    val accountType: String = "",
    val visible: Boolean = true,
    val syncEvents: Boolean = true
)

/** Thin read-only wrapper over the system calendar provider. */
class CalendarSource(private val context: Context) {

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

    fun calendars(): List<CalendarInfo> {
        if (!hasPermission) return emptyList()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS
        )
        return runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, proj, null, null, null
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            CalendarInfo(
                                id = c.getLong(0),
                                name = c.getString(1) ?: "",
                                account = c.getString(2) ?: "",
                                accountType = c.getString(3) ?: "",
                                visible = c.getInt(4) == 1,
                                syncEvents = c.getInt(5) == 1
                            )
                        )
                    }
                }
            }.orEmpty()
        }.onFailure { Log.w(TAG, "calendars() failed", it) }.getOrDefault(emptyList())
    }

    /**
     * Turn one calendar's sync on or off.
     *
     * Two columns, not one. VISIBLE decides whether calendar apps draw it;
     * SYNC_EVENTS decides whether its events are on the phone at all. Setting
     * only the first gives you a calendar that is meant to be shown and has
     * nothing in it.
     *
     * Worth having because Google Calendar's own settings no longer expose
     * this, so a calendar can be switched on there and still absent here.
     */
    fun setSynced(calendarId: Long, on: Boolean): Boolean = runCatching {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.VISIBLE, if (on) 1 else 0)
            put(CalendarContract.Calendars.SYNC_EVENTS, if (on) 1 else 0)
        }
        val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)
        context.contentResolver.update(uri, values, null, null) > 0
    }.onFailure { Log.w(TAG, "could not change sync for calendar $calendarId", it) }
        .getOrDefault(false)

    /** How many events a calendar has in the next [days] days. Cheap enough to show. */
    fun eventCount(calendarId: Long, days: Int = 14): Int {
        if (!hasPermission) return 0
        val now = System.currentTimeMillis()
        return events(now, now + days * 24L * 60 * 60 * 1000).count { it.calendarId == calendarId }
    }

    /**
     * Events overlapping [begin, end). Declined and cancelled events are
     * dropped: an invitation you said no to should not reshape your phone.
     */
    fun events(begin: Long, end: Long): List<CalEvent> {
        if (!hasPermission) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
            it.build()
        }
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.START_DAY,
            CalendarContract.Instances.END_DAY
        )
        return runCatching {
            context.contentResolver.query(
                uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val selfStatus = c.getInt(7)
                        val status = c.getInt(8)
                        if (selfStatus == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                        if (status == CalendarContract.Events.STATUS_CANCELED) continue
                        add(
                            CalEvent(
                                eventId = c.getLong(0),
                                calendarId = c.getLong(1),
                                calendarName = c.getString(9) ?: "",
                                title = c.getString(2) ?: "(no title)",
                                begin = c.getLong(3),
                                end = c.getLong(4),
                                allDay = c.getInt(5) == 1,
                                busy = c.getInt(6) == CalendarContract.Events.AVAILABILITY_BUSY,
                                startDay = c.getInt(10),
                                endDay = c.getInt(11)
                            )
                        )
                    }
                }
            }.orEmpty()
        }.onFailure { Log.w(TAG, "events() failed", it) }.getOrDefault(emptyList())
    }

    private companion object {
        const val TAG = "CalendarSource"
    }
}
