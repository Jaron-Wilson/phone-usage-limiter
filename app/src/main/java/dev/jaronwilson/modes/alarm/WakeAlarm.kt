package dev.jaronwilson.modes.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jaronwilson.modes.AppGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The wake alarm, which Modes owns end to end.
 *
 * The phone's Clock app can be told to create an alarm but never to remove or
 * toggle one, so an alarm set through it drifts out of the app's control the
 * moment it exists. This one is ours: setting it schedules an exact alarm,
 * removing it cancels that alarm, and it rings a full-screen [AlarmActivity]
 * that the app can silence. It rings at the Sleep wake time, every day that
 * Sleep covers.
 */
object WakeAlarm {

    private const val TAG = "WakeAlarm"
    const val REQ = 320
    const val ACTION_RING = "dev.jaronwilson.modes.WAKE_RING"
    const val NOTIFICATION_ID = 3201

    /**
     * Read the setting and the Sleep time, then either arm the next occurrence
     * or cancel. Safe to call any time: it is the single source of truth.
     */
    suspend fun sync(context: Context) {
        val settings = AppGraph.repo.settings
        val am = context.getSystemService(AlarmManager::class.java)
        if (!settings.wakeAlarmEnabled.first()) {
            am.cancel(operation(context))
            return
        }
        val sleep = runCatching { AppGraph.repo.ruleDao.activeTimeRules() }
            .getOrDefault(emptyList())
            .firstOrNull { it.modeId == "sleep" }
        val minutes = sleep?.endMinute ?: (7 * 60)
        val daysMask = sleep?.daysMask ?: 0b1111111
        val at = nextOccurrence(minutes, daysMask)
        runCatching {
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(at, showIntent(context)),
                operation(context)
            )
            Log.i(TAG, "wake alarm armed for $at")
        }.onFailure { Log.w(TAG, "could not arm wake alarm", it) }
    }

    suspend fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(operation(context))
    }

    /**
     * The next moment matching the wake minute and an allowed weekday. Bit 0 is
     * Monday, matching the Sleep rule's own convention.
     */
    fun nextOccurrence(
        minutes: Int,
        daysMask: Int,
        now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()
    ): Long {
        val time = LocalTime.of(minutes / 60, minutes % 60)
        var day: LocalDate = now.toLocalDate()
        for (i in 0..7) {
            val candidate = day.atTime(time).atZone(now.zone)
            val bit = 1 shl (candidate.dayOfWeek.value - 1) // Monday=1 -> bit 0
            val allowed = daysMask == 0 || (daysMask and bit) != 0
            if (allowed && candidate.toInstant().toEpochMilli() > now.toInstant().toEpochMilli()) {
                return candidate.toInstant().toEpochMilli()
            }
            day = day.plusDays(1)
        }
        // Fallback: tomorrow at the time, should never be reached.
        return now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)
            .toInstant().toEpochMilli()
    }

    private fun operation(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQ,
        Intent(context, WakeAlarmReceiver::class.java).setAction(ACTION_RING),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** What the status-bar alarm icon opens: the app's Sleep settings. */
    private fun showIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, REQ + 1,
        Intent(context, dev.jaronwilson.modes.ui.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

/**
 * Fires at the wake time. Launches the full-screen alarm and lines up the next
 * day, so a daily alarm keeps going without the app being opened.
 */
class WakeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.ensure(context)
        // Start the ringing service. Because this broadcast came from an exact
        // alarm, starting a foreground service from the background is allowed.
        // The service owns the sound and the Dismiss, so it works whether or not
        // the full-screen alarm is permitted to take over the screen.
        runCatching { AlarmService.start(context) }
            .onFailure { Log.w("WakeAlarm", "could not start alarm service", it) }

        val pending = goAsync()
        AppGraph.scope.launch {
            try {
                WakeAlarm.sync(context)
            } finally {
                pending.finish()
            }
        }
    }
}
