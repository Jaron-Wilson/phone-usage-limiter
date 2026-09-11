package dev.jaronwilson.modes.notify

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.core.model.HeldNotification
import dev.jaronwilson.modes.schedule.DigestWindow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The gate. Every posted notification passes through here and is either left
 * alone or pulled out of the shade until the next digest window.
 *
 * Holding is done with [snoozeNotification] rather than by cancelling: the
 * original comes back on its own, with its real icon, its real tap target and
 * its real reply box. Cancelling would mean rebuilding a worse copy. The only
 * catch is that the system caps how long it will hold a snoozed notification,
 * so long holds are done in chunks: when one comes back early it simply passes
 * through here again and gets snoozed again.
 */
class NotificationGate : NotificationListenerService() {

    /**
     * Tap targets and reply actions for the notifications we are holding.
     * Kept in memory only; a [PendingIntent] cannot be written to a database.
     * If the service is restarted we lose these and fall back to plain text.
     */
    private val originals = ConcurrentHashMap<String, Original>()

    data class Original(
        val contentIntent: PendingIntent?,
        val actions: Array<Notification.Action>?,
        val smallIcon: android.graphics.drawable.Icon?
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        AppGraph.ensure(applicationContext)
        AppGraph.scope.launch {
            AppGraph.repo.seedIfEmpty()
            AppGraph.scheduler.reevaluate("listener connected")
            AppGraph.scheduler.scheduleNextDigest()
        }
        Log.i(TAG, "gate connected")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val policy = AppGraph.repo.snapshot ?: return
        if (Classifier.isExempt(sbn, packageName)) return

        // A genuine notification coming back out of snooze after we already
        // delivered a stand-in for it. Let it through and take the copy down.
        if (ReleaseLedger.consume(sbn.key)) {
            DigestPublisher.cancelCopy(this, sbn.key)
            originals.remove(sbn.key)
            return
        }

        val read = Classifier.read(sbn)
        val verdict = Classifier.decide(sbn, read, policy, packageName)

        if (verdict.exempt) return

        if (verdict.allow) {
            // If we recently released this, make sure a returning snooze does
            // not get grabbed again, and clear our stand-in copy.
            originals.remove(sbn.key)
            return
        }

        val mode = policy.mode
        val holdMs = DigestWindow.holdDuration(mode, System.currentTimeMillis())

        originals[sbn.key] = Original(
            contentIntent = sbn.notification.contentIntent,
            actions = sbn.notification.actions,
            smallIcon = runCatching { sbn.notification.smallIcon }.getOrNull()
        )

        val label = appLabel(sbn.packageName)
        val record = HeldNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = label,
            title = read.title.ifBlank { null },
            text = read.text.ifBlank { null },
            postedAt = System.currentTimeMillis(),
            notifClass = verdict.notifClass,
            modeId = mode.id,
            snoozedUntil = System.currentTimeMillis() + holdMs
        )

        val snoozed = runCatching { snoozeNotification(sbn.key, holdMs) }.isSuccess
        if (!snoozed) {
            runCatching { cancelNotification(sbn.key) }
        }

        AppGraph.scope.launch {
            runCatching {
                AppGraph.repo.heldDao.insert(record.copy(dropped = !snoozed))
            }.onFailure { Log.w(TAG, "could not record held notification", it) }
        }

        Log.d(TAG, "held ${sbn.packageName} as ${verdict.notifClass} for ${holdMs / 1000}s: ${verdict.reason}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        // Dismissed by the user rather than by us: stop tracking it.
        if (reason == REASON_CANCEL || reason == REASON_CANCEL_ALL || reason == REASON_CLICK) {
            originals.remove(sbn.key)
            AppGraph.scope.launch {
                runCatching { AppGraph.repo.heldDao.release(sbn.key, System.currentTimeMillis()) }
            }
        }
    }

    fun originalFor(key: String): Original? = originals[key]

    fun forget(key: String) {
        originals.remove(key)
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        private const val TAG = "NotificationGate"

        @Volatile
        var instance: NotificationGate? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ).orEmpty()
            val me = ComponentName(context, NotificationGate::class.java)
            return flat.split(":").any {
                val cn = ComponentName.unflattenFromString(it)
                cn != null && cn.packageName == me.packageName
            }
        }
    }
}
