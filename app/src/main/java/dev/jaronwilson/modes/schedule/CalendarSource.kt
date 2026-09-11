package dev.jaronwilson.modes.schedule

import android.Manifest
import android.content.ContentUris
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
    val busy: Boolean
)

data class CalendarInfo(val id: Long, val name: String, val account: String)

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
            CalendarContract.Calendars.ACCOUNT_NAME
        )
        return runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, proj, null, null, null
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(CalendarInfo(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: ""))
                    }
                }
            }.orEmpty()
        }.onFailure { Log.w(TAG, "calendars() failed", it) }.getOrDefault(emptyList())
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
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME
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
                                busy = c.getInt(6) == CalendarContract.Events.AVAILABILITY_BUSY
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
