package dev.jaronwilson.modes.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jaronwilson.modes.ModesApp
import dev.jaronwilson.modes.R
import dev.jaronwilson.modes.core.model.EventKind
import dev.jaronwilson.modes.core.model.HeldNotification
import dev.jaronwilson.modes.core.repo.Stats
import dev.jaronwilson.modes.core.repo.ModeRepository

/**
 * Hands back everything the current mode has been holding.
 *
 * Two things happen at once, on purpose:
 *  - a stand-in copy of each held notification is posted, so the batch arrives
 *    the moment the window opens rather than whenever the system's snooze timer
 *    happens to expire. The copies carry the original tap target and reply
 *    actions, so they behave like the real thing.
 *  - each key is marked released, so when the genuine notification does come
 *    back out of snooze the gate lets it through and cancels our stand-in.
 *
 * The result is one batch, at a time you chose, with nothing lost.
 */
class DigestPublisher(
    private val context: Context,
    private val repo: ModeRepository
) {
    private val nm: NotificationManager = context.getSystemService(NotificationManager::class.java)

    suspend fun releaseAll(reason: String) {
        val pending = repo.heldDao.pending()
        if (pending.isEmpty()) {
            repo.settings.setLastDigestAt(System.currentTimeMillis())
            return
        }

        val gate = NotificationGate.instance
        ReleaseLedger.mark(pending.map { it.key })

        var copied = 0
        for (item in pending) {
            val original = gate?.originalFor(item.key)
            runCatching { postCopy(item, original) }
                .onSuccess { copied++ }
                .onFailure { Log.w(TAG, "copy for ${item.key} failed", it) }
        }

        runCatching { postSummary(pending, reason) }
            .onFailure { Log.w(TAG, "digest summary failed", it) }

        repo.heldDao.releaseAll(System.currentTimeMillis())
        repo.settings.setLastDigestAt(System.currentTimeMillis())
        Stats.log(EventKind.DIGEST_RELEASED, detail = reason, count = pending.size)
        Log.i(TAG, "released ${pending.size} held notifications ($copied copies): $reason")
    }

    /** Release a single item, e.g. because you tapped it in the app. */
    suspend fun release(item: HeldNotification) {
        ReleaseLedger.mark(listOf(item.key))
        runCatching { postCopy(item, NotificationGate.instance?.originalFor(item.key)) }
        repo.heldDao.release(item.key, System.currentTimeMillis())
    }

    private fun postCopy(item: HeldNotification, original: NotificationGate.Original?) {
        val builder = Notification.Builder(context, ModesApp.CH_DIGEST)
            .setSmallIcon(R.drawable.ic_stat_modes)
            .setContentTitle(item.title ?: item.appLabel)
            .setContentText(item.text.orEmpty())
            .setSubText(item.appLabel)
            .setWhen(item.postedAt)
            .setShowWhen(true)
            .setGroup(GROUP)
            .setAutoCancel(true)

        item.text?.takeIf { it.length > 60 }?.let {
            builder.setStyle(Notification.BigTextStyle().bigText(it))
        }
        original?.contentIntent?.let { builder.setContentIntent(it) }
        original?.actions?.forEach { builder.addAction(it) }

        nm.notify(copyTag(item.key), COPY_ID, builder.build())
    }

    private fun postSummary(items: List<HeldNotification>, reason: String) {
        val byApp = items.groupingBy { it.appLabel }.eachCount()
            .entries.sortedByDescending { it.value }
        val breakdown = byApp.joinToString(", ") { "${it.value} ${it.key}" }

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, dev.jaronwilson.modes.ui.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_SHOW_DIGEST, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val summary = Notification.Builder(context, ModesApp.CH_DIGEST)
            .setSmallIcon(R.drawable.ic_stat_modes)
            .setContentTitle("${items.size} held while you were away")
            .setContentText(breakdown)
            .setStyle(Notification.BigTextStyle().bigText("$breakdown\n\n$reason"))
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        nm.notify(SUMMARY_ID, summary)
    }

    companion object {
        const val GROUP = "dev.jaronwilson.modes.digest"
        const val SUMMARY_ID = 2000
        const val COPY_ID = 2001
        const val EXTRA_SHOW_DIGEST = "show_digest"
        private const val TAG = "DigestPublisher"

        fun copyTag(key: String) = "copy:$key"

        fun cancelCopy(context: Context, key: String) {
            runCatching {
                context.getSystemService(NotificationManager::class.java)
                    .cancel(copyTag(key), COPY_ID)
            }
        }
    }
}

/**
 * Keys that have been released recently.
 *
 * A held notification was snoozed, so the system will push it back into the
 * shade at some point after we have already delivered a stand-in. This is how
 * the gate knows to let that late arrival through instead of holding it again,
 * and to take the stand-in down when the genuine article shows up.
 */
object ReleaseLedger {
    private val released = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val TTL_MS = 12L * 60 * 60 * 1000

    fun mark(keys: Collection<String>) {
        val now = System.currentTimeMillis()
        keys.forEach { released[it] = now }
        prune(now)
    }

    fun consume(key: String): Boolean {
        val at = released.remove(key) ?: return false
        return System.currentTimeMillis() - at < TTL_MS
    }

    private fun prune(now: Long) {
        if (released.size < 500) return
        released.entries.removeIf { now - it.value > TTL_MS }
    }
}
