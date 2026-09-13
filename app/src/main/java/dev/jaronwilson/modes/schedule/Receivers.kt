package dev.jaronwilson.modes.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.notify.DigestPublisher
import kotlinx.coroutines.launch

/** Fired at a scheduled mode boundary or digest window. */
class ModeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.ensure(context)
        val pending = goAsync()
        AppGraph.scope.launch {
            try {
                when (intent.action) {
                    ModeScheduler.ACTION_DIGEST -> {
                        DigestPublisher(context, AppGraph.repo).releaseAll("Scheduled digest")
                        AppGraph.scheduler.scheduleNextDigest()
                    }
                    else -> {
                        AppGraph.scheduler.reevaluate("boundary alarm")
                        AppGraph.scheduler.scheduleNextBoundary()
                        AppGraph.scheduler.scheduleNextDigest()
                    }
                }
            } catch (t: Throwable) {
                Log.w("ModeAlarmReceiver", "alarm handling failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Reboots, updates and clock changes all invalidate our alarms. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.ensure(context)
        val pending = goAsync()
        AppGraph.scope.launch {
            try {
                AppGraph.repo.seedIfEmpty()
                AppGraph.scheduler.reevaluate("boot: ${intent.action}")
                AppGraph.scheduler.scheduleNextBoundary()
                AppGraph.scheduler.scheduleNextDigest()
                AppGraph.scheduler.ensurePeriodicSync()
                dev.jaronwilson.modes.commute.WorkRunUpScheduler(context).scheduleNext()
            } catch (t: Throwable) {
                Log.w("BootReceiver", "boot handling failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}

/** The calendar changed, so the plan for the rest of the day may have too. */
class CalendarChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.ensure(context)
        val pending = goAsync()
        AppGraph.scope.launch {
            try {
                AppGraph.scheduler.reevaluate("calendar changed")
                AppGraph.scheduler.scheduleNextBoundary()
                dev.jaronwilson.modes.commute.WorkRunUpScheduler(context).scheduleNext()
            } catch (t: Throwable) {
                Log.w("CalendarChanged", "handling failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
