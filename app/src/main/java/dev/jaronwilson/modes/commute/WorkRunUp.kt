package dev.jaronwilson.modes.commute

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.ModesApp
import dev.jaronwilson.modes.R
import dev.jaronwilson.modes.core.Defaults
import dev.jaronwilson.modes.schedule.CalEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The run-up to work: an alarm an hour before it starts, another thirty minutes
 * before, and that second one opens the drive there.
 *
 * This is separate from the leave-by nudge. That one does travel-time
 * arithmetic and only ever offers a button, because it fires when you might
 * already be moving. This is a fixed pair of alarms tied to when work begins,
 * and thirty minutes before is early enough that opening the map is a help, not
 * a hazard.
 */
data class RunUp(
    val title: String,
    /** When work begins. */
    val startAt: Long,
    /** Where to drive, straight from the event; may be blank. */
    val location: String
)

object WorkRunUp {

    const val LEAD_FIRST_MIN = 60L
    const val LEAD_SECOND_MIN = 30L

    /**
     * The next work event, or null. Pure so the timing is testable without a
     * calendar or a clock: an upcoming, timed event whose title matches one of
     * the work patterns, earliest first.
     */
    fun nextWork(events: List<CalEvent>, now: Long, patterns: List<Regex>): RunUp? =
        events.asSequence()
            .filter { !it.allDay }
            .filter { it.begin > now }
            .filter { event -> patterns.any { it.containsMatchIn(event.title) } }
            .minByOrNull { it.begin }
            ?.let { RunUp(it.title, it.begin, it.location) }
}

/**
 * Schedules the two alarms and lines up the next pair after they fire.
 */
class WorkRunUpScheduler(private val context: Context) {

    private val alarms: AlarmManager = context.getSystemService(AlarmManager::class.java)

    suspend fun scheduleNext() {
        val settings = AppGraph.repo.settings
        if (!settings.workAlarmsEnabled.first()) {
            cancelAll()
            return
        }
        val now = System.currentTimeMillis()
        val events = runCatching {
            AppGraph.scheduler.calendar.events(now, now + 36 * 60 * 60 * 1000L)
        }.getOrDefault(emptyList())

        val patterns = workPatterns()
        val runUp = WorkRunUp.nextWork(events, now, patterns)
        if (runUp == null) {
            cancelAll()
            return
        }

        // The event's own location wins; then a saved Work place; then the word
        // Work, which Maps resolves to your saved Work if you have one.
        val destination = runUp.location.ifBlank {
            val saved = settings.destinations.first()
            Destinations.find(saved, "Work")?.query ?: "Work"
        }

        scheduleOne(
            req = REQ_FIRST,
            at = runUp.startAt - WorkRunUp.LEAD_FIRST_MIN * 60_000L,
            now = now,
            title = runUp.title,
            destination = destination,
            minutesBefore = WorkRunUp.LEAD_FIRST_MIN,
            navigate = false
        )
        scheduleOne(
            req = REQ_SECOND,
            at = runUp.startAt - WorkRunUp.LEAD_SECOND_MIN * 60_000L,
            now = now,
            title = runUp.title,
            destination = destination,
            minutesBefore = WorkRunUp.LEAD_SECOND_MIN,
            navigate = true
        )
    }

    private fun scheduleOne(
        req: Int, at: Long, now: Long, title: String, destination: String,
        minutesBefore: Long, navigate: Boolean
    ) {
        if (at <= now + 5_000) {
            runCatching { alarms.cancel(pendingFor(req, title, destination, minutesBefore, navigate)) }
            return
        }
        val pending = pendingFor(req, title, destination, minutesBefore, navigate)
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
            Log.i(TAG, "work run-up: $minutesBefore min before '$title', navigate=$navigate")
        }.onFailure { Log.w(TAG, "could not schedule work run-up", it) }
    }

    fun cancelAll() {
        runCatching { alarms.cancel(pendingFor(REQ_FIRST, "", "", 0, false)) }
        runCatching { alarms.cancel(pendingFor(REQ_SECOND, "", "", 0, true)) }
    }

    private suspend fun workPatterns(): List<Regex> {
        val rules = runCatching { AppGraph.repo.ruleDao.activeCalendarRules() }
            .getOrDefault(emptyList())
            .filter { it.modeId == Defaults.MODE_WORK }
            .mapNotNull { it.titlePattern?.takeIf { p -> p.isNotBlank() } }
        val raw = rules.ifEmpty { listOf(DEFAULT_WORK_PATTERN) }
        return raw.mapNotNull { runCatching { Regex(it) }.getOrNull() }
            .ifEmpty { listOf(Regex(DEFAULT_WORK_PATTERN)) }
    }

    private fun pendingFor(
        req: Int, title: String, destination: String, minutesBefore: Long, navigate: Boolean
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        req,
        Intent(context, WorkAlarmReceiver::class.java)
            .setAction(ACTION_WORK_ALARM)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_DESTINATION, destination)
            .putExtra(EXTRA_MINUTES, minutesBefore)
            .putExtra(EXTRA_NAVIGATE, navigate),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        const val ACTION_WORK_ALARM = "dev.jaronwilson.modes.WORK_ALARM"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_NAVIGATE = "navigate"
        const val NOTIFICATION_ID = 3101
        private const val REQ_FIRST = 310
        private const val REQ_SECOND = 311
        private const val TAG = "WorkRunUp"
        private const val DEFAULT_WORK_PATTERN = "(?i)\\b(work|shift|on.?call|clock.?in)\\b"
    }
}

/**
 * Rings the run-up alarm. The first is a heads-up; the second opens the drive.
 */
class WorkAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.ensure(context)
        val title = intent.getStringExtra(WorkRunUpScheduler.EXTRA_TITLE).orEmpty()
        val destination = intent.getStringExtra(WorkRunUpScheduler.EXTRA_DESTINATION).orEmpty()
        val minutes = intent.getLongExtra(WorkRunUpScheduler.EXTRA_MINUTES, 0L)
        val navigate = intent.getBooleanExtra(WorkRunUpScheduler.EXTRA_NAVIGATE, false)

        val navPending = PendingIntent.getActivity(
            context, 312,
            CommuteScheduler.navigationIntent(destination),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nm = context.getSystemService(NotificationManager::class.java)
        val builder = Notification.Builder(context, ModesApp.CH_COMMUTE)
            .setSmallIcon(R.drawable.ic_stat_modes)
            .setContentTitle(
                if (navigate) "30 minutes to $title" else "1 hour to $title"
            )
            .setContentText(
                if (navigate) "Tap to drive to $destination" else destination
            )
            .setContentIntent(navPending)
            // A reminder, not an alarm. CATEGORY_ALARM with a full-screen intent
            // made some phones ring this like a clock alarm: a solid buzz for
            // minutes until it timed out. A reminder buzzes once.
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)

        if (navigate) {
            builder.addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?, "Navigate", navPending
                ).build()
            )
        }

        runCatching { nm.notify(WorkRunUpScheduler.NOTIFICATION_ID + minutes.toInt(), builder.build()) }

        // Best effort: where a phone allows an app to launch from the background
        // the drive opens on its own; where it does not, the notification opens
        // it in one tap. Either way it never rings.
        if (navigate) {
            runCatching { context.startActivity(CommuteScheduler.navigationIntent(destination)) }
                .onFailure { Log.i("WorkRunUp", "direct nav launch blocked, use the notification") }
        }

        val pending = goAsync()
        AppGraph.scope.launch {
            try {
                WorkRunUpScheduler(context).scheduleNext()
            } finally {
                pending.finish()
            }
        }
    }
}
