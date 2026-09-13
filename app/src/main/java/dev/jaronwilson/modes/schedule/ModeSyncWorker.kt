package dev.jaronwilson.modes.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.jaronwilson.modes.AppGraph

/** Safety net: re-derive the mode every 15 minutes no matter what else failed. */
class ModeSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppGraph.ensure(applicationContext)
        // Check where we are before deciding the mode, so arriving somewhere
        // with a saved place takes effect on the next sync.
        LocationGate.refresh(applicationContext, AppGraph.repo)
        AppGraph.scheduler.reevaluate("periodic sync")
        AppGraph.scheduler.scheduleNextBoundary()
        AppGraph.scheduler.scheduleNextDigest()
        dev.jaronwilson.modes.commute.CommuteScheduler(applicationContext).scheduleNext()
        dev.jaronwilson.modes.commute.WorkRunUpScheduler(applicationContext).scheduleNext()
        AppGraph.repo.heldDao.prune(System.currentTimeMillis() - PRUNE_AFTER_MS)
        AppGraph.repo.passDao.prune(System.currentTimeMillis())
        AppGraph.repo.eventDao.prune(
            System.currentTimeMillis() - dev.jaronwilson.modes.core.repo.Stats.KEEP_DAYS * 24 * 60 * 60 * 1000
        )
        return Result.success()
    }

    private companion object {
        const val PRUNE_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    }
}
