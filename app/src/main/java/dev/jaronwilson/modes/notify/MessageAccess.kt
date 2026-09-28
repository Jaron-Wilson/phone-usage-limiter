package dev.jaronwilson.modes.notify

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reading and answering messages without opening the app.
 *
 * A message notification carries its own reply box (a [RemoteInput] on one of
 * its actions); that is what a smartwatch or Android Auto fills in. This does
 * the same from the notification listener, so a reply goes out through the real
 * app with the screen off. It is built for the hands-free case: read what came
 * in, dictate an answer, send it, all without looking at the phone.
 *
 * It only ever touches notifications that already offer a reply box, so it works
 * the same for SMS, WhatsApp, Signal, Instagram and the rest, and knows nothing
 * app-specific.
 */
object MessageAccess {

    /**
     * The repliable messages currently in the shade, newest first, as JSON.
     * Each has key (pass back to [reply]), package, app, sender, text.
     */
    fun list(service: NotificationListenerService): JSONArray {
        val arr = JSONArray()
        val active = runCatching { service.activeNotifications }.getOrNull() ?: return arr
        active
            .filter { it.packageName != service.packageName }
            .filter { !isSummary(it) }
            .filter { replyAction(it.notification) != null }
            .sortedByDescending { it.postTime }
            .take(30)
            .forEach { sbn ->
                val extras = sbn.notification.extras
                val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = latestText(sbn.notification)
                arr.put(
                    JSONObject()
                        .put("key", sbn.key)
                        .put("package", sbn.packageName)
                        .put("app", appLabel(service, sbn.packageName))
                        .put("sender", sender)
                        .put("text", text)
                )
            }
        return arr
    }

    /**
     * Send [text] as a reply to the notification with [key]. Returns false if it
     * is gone from the shade or offers no reply box.
     */
    fun reply(service: NotificationListenerService, key: String, text: String): Boolean {
        val sbn = runCatching { service.activeNotifications }.getOrNull()
            ?.firstOrNull { it.key == key } ?: return false
        val action = replyAction(sbn.notification) ?: return false
        val inputs = action.remoteInputs ?: return false
        val results = Bundle()
        for (input in inputs) results.putCharSequence(input.resultKey, text)
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return runCatching {
            action.actionIntent.send(service as Context, 0, intent)
            true
        }.getOrDefault(false)
    }

    /** The first action carrying a free-text reply box, if any. */
    private fun replyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        }

    /**
     * The newest line of the conversation. MessagingStyle keeps the history in
     * EXTRA_MESSAGES, whose last entry is what just arrived; otherwise the plain
     * EXTRA_TEXT is the message.
     */
    private fun latestText(n: Notification): String {
        val messages = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null && messages.isNotEmpty()) {
            val last = messages.last() as? Bundle
            val body = last?.getCharSequence("text")?.toString()
            if (!body.isNullOrBlank()) return body
        }
        return n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    }

    private fun isSummary(sbn: StatusBarNotification): Boolean =
        sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    private fun appLabel(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
