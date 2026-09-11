package dev.jaronwilson.modes.apply

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.ModesApp
import dev.jaronwilson.modes.R
import dev.jaronwilson.modes.core.model.ModeSource
import dev.jaronwilson.modes.core.repo.ModeRepository
import dev.jaronwilson.modes.core.repo.SettingsStore
import dev.jaronwilson.modes.core.repo.Stats
import dev.jaronwilson.modes.core.model.EventKind
import dev.jaronwilson.modes.notify.DigestPublisher
import dev.jaronwilson.modes.schedule.Decision
import dev.jaronwilson.modes.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Turns a [Decision] into actual phone behaviour: DND state, the home screen's
 * app list, the guard's allow-list, and the status notification.
 */
class ModeApplier(
    private val context: Context,
    private val repo: ModeRepository
) {
    private val settings: SettingsStore = repo.settings
    val zen = ZenController(context, settings)
    private val nm: NotificationManager = context.getSystemService(NotificationManager::class.java)

    suspend fun apply(decision: Decision, force: Boolean = false) {
        val current = settings.activeNow()
        val changed = current.modeId != decision.modeId
        if (!changed && !force && current.reason == decision.reason) {
            return
        }

        val modes = repo.modeDao.getAll()
        val previous = modes.firstOrNull { it.id == current.modeId }
        val next = modes.firstOrNull { it.id == decision.modeId }
        if (next == null) {
            Log.w(TAG, "decision names unknown mode ${decision.modeId}")
            return
        }

        settings.setActive(
            SettingsStore.ActiveState(
                modeId = decision.modeId,
                source = decision.source,
                reason = decision.reason,
                until = decision.until
            )
        )

        // Leaving a mode is a natural moment to hand back what it was holding.
        if (changed && previous != null && previous.releaseOnModeExit) {
            runCatching { DigestPublisher(context, repo).releaseAll("${previous.name} ended") }
                .onFailure { Log.w(TAG, "release on exit failed", it) }
        }

        // A pass to open Instagram during Work should not survive into Sleep.
        if (changed) runCatching { repo.passDao.clear() }
        if (changed) Stats.log(EventKind.MODE_CHANGED, detail = previous?.id ?: "")

        runCatching { zen.activate(decision.modeId, modes) }
            .onFailure { Log.w(TAG, "zen activate failed", it) }

        postStatus(next.name, next.glyph, decision, nextEventLine())
    }

    /** Reapply the current mode without recomputing it. Used after a reboot. */
    suspend fun reapply() {
        val active = settings.activeNow()
        apply(
            Decision(active.modeId, active.source, active.reason, active.until),
            force = true
        )
    }

    /**
     * The next thing on the calendar, in one line, or null.
     *
     * This is the closest an app can get to owning the lock screen. Android
     * does not let anything replace it, so the play is to be the one thing
     * worth reading on it while the gate keeps everything else off.
     */
    private fun nextEventLine(): String? = runCatching {
        val now = System.currentTimeMillis()
        val event = AppGraph.scheduler.calendar
            .events(now, now + 18 * 60 * 60 * 1000L)
            .filter { it.end > now }
            .minByOrNull { it.begin }
            ?: return null
        val time = DateTimeFormatter.ofPattern("HH:mm")
            .format(Instant.ofEpochMilli(event.begin).atZone(ZoneId.systemDefault()))
        if (event.allDay) event.title
        else if (event.begin <= now) "Now: ${event.title}"
        else "$time  ${event.title}"
    }.getOrNull()

    private fun postStatus(
        name: String,
        glyph: String,
        decision: Decision,
        nextEvent: String?
    ) {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val detail = buildString {
            append(decision.reason.ifBlank { sourceLabel(decision.source) })
            if (decision.until > 0) {
                append(" until ")
                append(
                    DateTimeFormatter.ofPattern("HH:mm")
                        .format(Instant.ofEpochMilli(decision.until).atZone(ZoneId.systemDefault()))
                )
            }
        }

        val n = Notification.Builder(context, ModesApp.CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_modes)
            .setContentTitle(if (glyph.isBlank()) name else "$glyph  $name")
            .setContentText(nextEvent ?: detail)
            .setStyle(
                Notification.BigTextStyle()
                    .bigText(listOfNotNull(nextEvent, detail).joinToString("\n"))
            )
            .setContentIntent(open)
            // Sticky and public on purpose: it is the one line meant to survive
            // on a lock screen the gate has otherwise emptied.
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()

        runCatching { nm.notify(STATUS_ID, n) }
            .onFailure { Log.w(TAG, "status notification failed", it) }
    }

    private fun sourceLabel(source: ModeSource) = when (source) {
        ModeSource.MANUAL -> "Set by hand"
        ModeSource.CALENDAR -> "From your calendar"
        ModeSource.TIME_RULE -> "On schedule"
        ModeSource.DEFAULT -> "Default"
    }

    companion object {
        const val STATUS_ID = 1001
        private const val TAG = "ModeApplier"
    }
}
