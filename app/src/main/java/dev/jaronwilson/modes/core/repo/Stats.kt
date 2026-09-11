package dev.jaronwilson.modes.core.repo

import android.util.Log
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.core.model.EventKind
import dev.jaronwilson.modes.core.model.UsageEvent
import kotlinx.coroutines.launch

/**
 * Fire-and-forget event logging. Never blocks the caller and never throws:
 * a stats failure must not be allowed to break a notification decision.
 */
object Stats {
    fun log(
        kind: EventKind,
        packageName: String? = null,
        detail: String? = null,
        count: Int = 1
    ) {
        val modeId = AppGraph.repo.snapshot?.mode?.id ?: ""
        val event = UsageEvent(
            at = System.currentTimeMillis(),
            kind = kind,
            modeId = modeId,
            packageName = packageName,
            detail = detail,
            count = count
        )
        AppGraph.scope.launch {
            runCatching { AppGraph.repo.eventDao.insert(event) }
                .onFailure { Log.w("Stats", "could not log $kind", it) }
        }
    }

    const val KEEP_DAYS = 30L
}
