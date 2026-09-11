package dev.jaronwilson.modes.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.jaronwilson.modes.apply.ModeApplier
import dev.jaronwilson.modes.core.repo.ModeRepository
import java.util.concurrent.TimeUnit

/**
 * Keeps the active mode in step with the schedule.
 *
 * Three overlapping mechanisms, because none of them is reliable alone:
 *  - an exact alarm at the next boundary, which is what makes transitions land
 *    on the minute
 *  - a 15-minute periodic worker, which catches anything the alarm missed after
 *    a doze, a reboot or a battery-optimisation kill
 *  - a broadcast from the calendar provider when events change underneath us
 */
class ModeScheduler(
    private val context: Context,
    private val repo: ModeRepository,
    private val applier: ModeApplier
) {
    val calendar = CalendarSource(context)
    val resolver = ScheduleResolver(repo, calendar)

    private val alarmManager: AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    suspend fun reevaluate(trigger: String) {
        val decision = runCatching { resolver.resolve() }
            .onFailure { Log.w(TAG, "resolve failed ($trigger)", it) }
            .getOrNull() ?: return
        Log.i(TAG, "[$trigger] -> ${decision.modeId} (${decision.source}) ${decision.reason}")
        applier.apply(decision)
    }

    suspend fun scheduleNextBoundary() {
        val at = runCatching { resolver.nextBoundary() }.getOrNull() ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQ_BOUNDARY,
            Intent(context, ModeAlarmReceiver::class.java).setAction(ACTION_BOUNDARY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        runCatching {
            if (canBeExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                // Without the exact-alarm grant we still switch, just less punctually.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }.onFailure { Log.w(TAG, "alarm scheduling failed", it) }
    }

    /** Schedule the next release of held notifications for the active mode. */
    suspend fun scheduleNextDigest() {
        val mode = repo.modeDao.get(repo.settings.activeNow().modeId) ?: return
        val at = DigestWindow.next(mode, System.currentTimeMillis()) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQ_DIGEST,
            Intent(context, ModeAlarmReceiver::class.java).setAction(ACTION_DIGEST),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        runCatching {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }.onFailure { Log.w(TAG, "digest alarm failed", it) }
    }

    fun ensurePeriodicSync() {
        val work = PeriodicWorkRequestBuilder<ModeSyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            work
        )
    }

    companion object {
        const val ACTION_BOUNDARY = "dev.jaronwilson.modes.BOUNDARY"
        const val ACTION_DIGEST = "dev.jaronwilson.modes.DIGEST"
        private const val REQ_BOUNDARY = 100
        private const val REQ_DIGEST = 101
        private const val WORK_NAME = "modes-sync"
        private const val TAG = "ModeScheduler"
    }
}
