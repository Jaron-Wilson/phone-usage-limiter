package dev.jaronwilson.modes.commute

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.ModesApp
import dev.jaronwilson.modes.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Says when to leave, and opens the map if you want it to.
 *
 * It stops at telling you and offering a button. Starting navigation on its own
 * would mean an app deciding to take over the screen of someone who may already
 * be driving, and no amount of convenience is worth that.
 */
class CommuteScheduler(private val context: Context) {

    private val alarms: AlarmManager = context.getSystemService(AlarmManager::class.java)

    suspend fun scheduleNext() {
        val settings = AppGraph.repo.settings
        if (!settings.commuteEnabled.first()) {
            cancel()
            return
        }
        val now = System.currentTimeMillis()
        val events = runCatching {
            AppGraph.scheduler.calendar.events(now, now + 24 * 60 * 60 * 1000L)
        }.getOrDefault(emptyList())

        val plan = Commute.nextPlan(
            events = events,
            now = now,
            settings = CommuteSettings(
                arriveEarlyMinutes = settings.arriveEarlyMinutes.first(),
                getReadyMinutes = settings.getReadyMinutes.first(),
                defaultTravelMinutes = settings.defaultTravelMinutes.first()
            )
        )
        if (plan == null) {
            cancel()
            return
        }

        val at = maxOf(plan.warnAt, now + 5_000)
        val pending = alarmIntent(plan.event.title, plan.destination, plan.leaveAt)
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
            Log.i(TAG, "leave-by nudge for '${plan.event.title}' at $at")
        }.onFailure { Log.w(TAG, "could not schedule the nudge", it) }
    }

    fun cancel() {
        runCatching { alarms.cancel(alarmIntent("", "", 0)) }
    }

    private fun alarmIntent(title: String, destination: String, leaveAt: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQ,
            Intent(context, LeaveReceiver::class.java)
                .setAction(ACTION_LEAVE)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_DESTINATION, destination)
                .putExtra(EXTRA_LEAVE_AT, leaveAt),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    companion object {
        const val ACTION_LEAVE = "dev.jaronwilson.modes.LEAVE_NOW"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_LEAVE_AT = "leave_at"
        const val NOTIFICATION_ID = 3001
        private const val REQ = 300
        private const val TAG = "CommuteScheduler"

        /** Google Maps, in navigation mode, aimed at the destination. */
        fun navigationIntent(destination: String): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination)))
                .setPackage("com.google.android.apps.maps")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Falls back to whatever maps app exists if Google Maps is absent. */
        fun navigationIntentAnyApp(destination: String): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Fires a little before you have to move. */
class LeaveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.ensure(context)
        val title = intent.getStringExtra(CommuteScheduler.EXTRA_TITLE).orEmpty()
        val destination = intent.getStringExtra(CommuteScheduler.EXTRA_DESTINATION).orEmpty()
        val leaveAt = intent.getLongExtra(CommuteScheduler.EXTRA_LEAVE_AT, 0L)
        val minutes = ((leaveAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)

        val navigate = PendingIntent.getActivity(
            context, 301,
            CommuteScheduler.navigationIntent(destination),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nm = context.getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(context, ModesApp.CH_COMMUTE)
            .setSmallIcon(R.drawable.ic_stat_modes)
            .setContentTitle(
                if (minutes <= 0) "Leave now for $title" else "Leave in $minutes min for $title"
            )
            .setContentText(destination)
            .setStyle(Notification.BigTextStyle().bigText("$destination\n\nTap Navigate when you are ready to drive."))
            .addAction(
                Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Navigate", navigate).build()
            )
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()

        runCatching { nm.notify(CommuteScheduler.NOTIFICATION_ID, notification) }

        // Line up whatever comes after this one.
        val pending = goAsync()
        AppGraph.scope.launch {
            try {
                CommuteScheduler(context).scheduleNext()
            } finally {
                pending.finish()
            }
        }
    }
}
