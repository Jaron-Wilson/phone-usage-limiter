package dev.jaronwilson.modes.notify

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import dev.jaronwilson.modes.core.PACKAGE_DEFAULT_CLASS
import dev.jaronwilson.modes.core.model.MatchField
import dev.jaronwilson.modes.core.model.NotifClass
import dev.jaronwilson.modes.core.repo.PolicySnapshot

/** What the gate decided, and why, so the app can show its working. */
data class Verdict(
    val notifClass: NotifClass,
    val allow: Boolean,
    val reason: String,
    val vip: Boolean = false,
    /** A rule marked this as something that breaks through any mode. */
    val breaksThrough: Boolean = false,
    /** True when the notification must never be touched at all. */
    val exempt: Boolean = false
)

/** The parts of a notification worth reading. */
data class NotifText(
    val title: String,
    val text: String,
    val channel: String,
    val category: String,
    val hasReplyAction: Boolean,
    val isConversation: Boolean
) {
    val all: String get() = "$title\n$text"
}

object Classifier {

    fun read(sbn: StatusBarNotification): NotifText {
        val n = sbn.notification
        val e = n.extras
        fun s(key: String): String = e.getCharSequence(key)?.toString().orEmpty()

        val title = listOf(
            s(Notification.EXTRA_CONVERSATION_TITLE),
            s(Notification.EXTRA_TITLE),
            s(Notification.EXTRA_TITLE_BIG)
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        val text = listOf(
            s(Notification.EXTRA_BIG_TEXT),
            s(Notification.EXTRA_TEXT),
            s(Notification.EXTRA_SUMMARY_TEXT),
            s(Notification.EXTRA_SUB_TEXT),
            s(Notification.EXTRA_INFO_TEXT)
        ).filter { it.isNotBlank() }.distinct().joinToString(" ")

        val hasReply = n.actions?.any { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        } == true

        val template = e.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val isConversation = template.endsWith("MessagingStyle") ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && n.shortcutId != null)

        return NotifText(
            title = title,
            text = text,
            channel = n.channelId.orEmpty(),
            category = n.category.orEmpty(),
            hasReplyAction = hasReply,
            isConversation = isConversation
        )
    }

    /**
     * Notifications we refuse to interfere with. Silencing a running navigation,
     * a media transport or an alarm is how a tool like this stops being trusted.
     */
    fun isExempt(sbn: StatusBarNotification, ownPackage: String): Boolean {
        if (sbn.packageName == ownPackage) return true
        val n = sbn.notification
        val flags = n.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return true
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return true
        return when (n.category) {
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_STOPWATCH -> true
            else -> false
        }
    }

    /** The class, plus whether the rule that decided it overrides the mode. */
    data class Classification(val notifClass: NotifClass, val breaksThrough: Boolean)

    fun classify(sbn: StatusBarNotification, read: NotifText, policy: PolicySnapshot): NotifClass =
        classifyDetailed(sbn, read, policy).notifClass

    fun classifyDetailed(
        sbn: StatusBarNotification,
        read: NotifText,
        policy: PolicySnapshot
    ): Classification {
        if (read.category == Notification.CATEGORY_CALL) {
            return Classification(NotifClass.CALL, breaksThrough = true)
        }
        if (read.category == Notification.CATEGORY_MISSED_CALL) {
            return Classification(NotifClass.CALL, breaksThrough = true)
        }

        // Explicit rules win. They are sorted by priority already.
        for ((rule, regex) in policy.compiledRules) {
            if (rule.packageName != null && rule.packageName != sbn.packageName) continue
            val haystack = when (rule.field) {
                MatchField.TITLE -> read.title
                MatchField.TEXT -> read.text
                MatchField.CHANNEL -> read.channel
                MatchField.PACKAGE -> sbn.packageName
                MatchField.ANY -> read.all
            }
            if (haystack.isNotEmpty() && regex.containsMatchIn(haystack)) {
                return Classification(rule.target, rule.alwaysThrough)
            }
        }

        // A notification you can type a reply into is, by construction, someone
        // talking to you. This is what catches Instagram DMs, which have no
        // distinguishing wording.
        if (read.hasReplyAction) return Classification(NotifClass.DIRECT, false)
        if (read.category == Notification.CATEGORY_MESSAGE && read.isConversation) {
            return Classification(NotifClass.DIRECT, false)
        }

        return Classification(
            PACKAGE_DEFAULT_CLASS[sbn.packageName] ?: NotifClass.OTHER,
            breaksThrough = false
        )
    }

    fun decide(
        sbn: StatusBarNotification,
        read: NotifText,
        policy: PolicySnapshot,
        ownPackage: String
    ): Verdict {
        if (isExempt(sbn, ownPackage)) {
            return Verdict(NotifClass.SYSTEM, allow = true, reason = "Never held", exempt = true)
        }
        if (!policy.gateEnabled) {
            return Verdict(NotifClass.OTHER, allow = true, reason = "Gate off", exempt = true)
        }

        val (cls, breaksThrough) = classifyDetailed(sbn, read, policy)
        val mode = policy.mode

        if (cls == NotifClass.CALL) {
            return Verdict(cls, allow = true, reason = "Calls always come through", breaksThrough = true)
        }
        if (breaksThrough) {
            return Verdict(
                cls, allow = true,
                reason = "Too important to hold",
                breaksThrough = true
            )
        }

        val vip = policy.compiledVips.any { it.containsMatchIn(read.title) }
        if (vip && mode.vipsAlwaysThrough) {
            return Verdict(cls, allow = true, reason = "From someone on your list", vip = true)
        }

        if (sbn.packageName in mode.blockedPackages) {
            return Verdict(cls, allow = false, reason = "${mode.name} holds this app", vip = vip)
        }
        // An app you allowed still does not get to advertise at you. Without
        // this, putting your bank on the allow list would also let through
        // "introducing our new credit card".
        if (sbn.packageName in mode.allowedPackages && cls != NotifClass.PROMO) {
            return Verdict(cls, allow = true, reason = "${mode.name} allows this app", vip = vip)
        }
        if (cls in mode.allowedClasses) {
            return Verdict(cls, allow = true, reason = "${mode.name} allows ${cls.label.lowercase()}", vip = vip)
        }
        return Verdict(cls, allow = false, reason = "${mode.name} holds ${cls.label.lowercase()}", vip = vip)
    }
}
