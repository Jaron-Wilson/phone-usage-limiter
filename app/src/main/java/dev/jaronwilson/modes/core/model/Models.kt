package dev.jaronwilson.modes.core.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * What a notification actually *is*, independent of which app sent it.
 * The whole point of the gate is that "Instagram" is not one thing: a DM from
 * a friend and "12 people liked your post" deserve opposite treatment.
 */
enum class NotifClass {
    /** Incoming or missed phone call. Never held. */
    CALL,
    /** A human wrote to you directly: SMS, DM, WhatsApp, Signal. */
    DIRECT,
    /** Someone mentioned, tagged, replied to, or commented at you. */
    MENTION,
    /** Someone you follow posted a story or went live. */
    STORY,
    /** Likes, follows, "X posted for the first time in a while", suggestions. */
    SOCIAL,
    /** Money moving: transactions, balances, payment due, fraud alerts. */
    FINANCE,
    /** Marketing, promos, "check out what's new". */
    PROMO,
    /** Battery, updates, sync, app-internal status. */
    SYSTEM,
    /** Unclassified. */
    OTHER;

    val label: String
        get() = when (this) {
            CALL -> "Calls"
            DIRECT -> "Direct messages"
            MENTION -> "Mentions & replies"
            STORY -> "Stories & live"
            SOCIAL -> "Likes & follows"
            FINANCE -> "Money"
            PROMO -> "Promotions"
            SYSTEM -> "System"
            OTHER -> "Everything else"
        }
}

/**
 * Which apps a mode guards.
 *
 * [ALLOWLIST] is the stricter and more useful setting: the mode's home screen
 * *is* the list of apps it is for, and anything else gets stopped. That means a
 * newly installed app, or one you opened from a link, is off-limits by default
 * rather than silently allowed. Essentials are never guarded either way, so you
 * cannot lock yourself out.
 */
enum class GuardScope {
    /** Only the apps explicitly set aside in this mode are guarded. */
    BLOCKLIST,
    /** Anything not on this mode's home screen is guarded. */
    ALLOWLIST
}

/** How hard the mode pushes back when you open an app it does not allow. */
enum class GuardMode {
    /** Do nothing. */
    OFF,
    /** Show a timed speed bump, then let you through if you still want it. */
    SPEEDBUMP,
    /** Send you straight home. */
    BLOCK
}

/** Which field of a notification a rule matches against. */
enum class MatchField { TITLE, TEXT, ANY, CHANNEL, PACKAGE }

/** Where a mode decision came from. Higher ordinal wins. */
enum class ModeSource { DEFAULT, TIME_RULE, CALENDAR, MANUAL }

@Entity(tableName = "modes")
data class Mode(
    @PrimaryKey val id: String,
    val name: String,
    val glyph: String = "",
    /** Shown in pickers, low first. */
    val sortOrder: Int = 0,
    val isDefault: Boolean = false,

    // ---- notifications ----
    /** Classes that ring through immediately. Everything else is held. */
    val allowedClasses: Set<NotifClass> = setOf(NotifClass.CALL, NotifClass.DIRECT),
    /** Packages that always ring through regardless of class. */
    val allowedPackages: Set<String> = emptySet(),
    /** Packages that are always held, even for otherwise-allowed classes. */
    val blockedPackages: Set<String> = emptySet(),
    /** If true, a VIP sender overrides the class rules. */
    val vipsAlwaysThrough: Boolean = true,

    // ---- home screen ----
    // The layout itself lives in the home_entries table, because folders need
    // their own rows. See [HomeEntry].
    val guardMode: GuardMode = GuardMode.OFF,
    val guardScope: GuardScope = GuardScope.BLOCKLIST,
    /** Seconds the speed bump makes you wait before the "open anyway" button works. */
    val speedbumpSeconds: Int = 10,
    /** How long an "open anyway" pass lasts, in minutes. */
    val passMinutes: Int = 5,

    // ---- do not disturb ----
    /** One of NotificationManager.INTERRUPTION_FILTER_*. 0 means "leave DND alone". */
    val interruptionFilter: Int = 0,
    /** ZenPolicy.PEOPLE_TYPE_* */
    val allowCallsFrom: Int = 2, // PEOPLE_TYPE_STARRED
    val allowMessagesFrom: Int = 2,
    val allowRepeatCallers: Boolean = true,

    // ---- device effects (Android 15+) ----
    val grayscale: Boolean = false,
    val dimWallpaper: Boolean = false,
    val suppressAmbientDisplay: Boolean = false,

    // ---- digest ----
    /** Minutes past midnight at which held notifications are released. */
    val digestTimes: List<Int> = emptyList(),
    /** If > 0, release held notifications every N minutes instead of at fixed times. */
    val digestEveryMinutes: Int = 0,
    /** Release everything the moment the mode ends, rather than waiting for a window. */
    val releaseOnModeExit: Boolean = true
)

@Entity(tableName = "calendar_rules")
data class CalendarRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val enabled: Boolean = true,
    /** Regex against the event title. Null or blank matches any event. */
    val titlePattern: String? = null,
    /** Restrict to one calendar. Null matches any. */
    val calendarId: Long? = null,
    /** Only match events marked Busy. */
    val busyOnly: Boolean = true,
    /** Whether all-day events count. Usually not: they are not really "now". */
    val includeAllDay: Boolean = false,
    val modeId: String,
    /** Higher wins when two calendar rules match the same instant. */
    val priority: Int = 0,
    val note: String = ""
)

@Entity(tableName = "time_rules")
data class TimeRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val enabled: Boolean = true,
    /** Bit 0 = Monday ... bit 6 = Sunday. */
    val daysMask: Int = 0b1111111,
    val startMinute: Int,
    /** May be less than start, meaning the window wraps past midnight. */
    val endMinute: Int,
    val modeId: String,
    val priority: Int = 0,
    val note: String = ""
)

@Entity(tableName = "notif_rules", indices = [Index("packageName")])
data class NotifRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val enabled: Boolean = true,
    /** Null matches any app. */
    val packageName: String? = null,
    val field: MatchField = MatchField.ANY,
    /** Regex, case-insensitive. */
    val pattern: String,
    val target: NotifClass,
    /**
     * Break through whatever the mode says. For the handful of things that are
     * never noise: a fraud alert, a one-time passcode, a school closure.
     */
    val alwaysThrough: Boolean = false,
    /** Higher wins. */
    val priority: Int = 0,
    val note: String = ""
)

/**
 * One row on the minimal home screen: either a single app, or a named folder
 * holding several.
 *
 * A folder is not just tidying. Under [GuardScope.ALLOWLIST] the packages
 * reachable from this screen are exactly the packages the mode permits, so
 * deciding what goes in a folder is the same act as deciding what the mode is
 * for.
 */
@Entity(tableName = "home_entries", indices = [Index("modeId")])
data class HomeEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modeId: String,
    val sortOrder: Int = 0,
    /** Set for a single-app row, null for a folder. */
    val packageName: String? = null,
    /** Set for a folder, blank for a single-app row. */
    val folderName: String = "",
    /** Contents of a folder, in order. Empty for a single-app row. */
    val packages: List<String> = emptyList()
) {
    val isFolder: Boolean get() = packageName == null

    /** Every package this row can reach. */
    val reachable: List<String>
        get() = if (isFolder) packages else listOfNotNull(packageName)
}

/** A person who gets through no matter what the mode says. */
@Entity(tableName = "vips")
data class Vip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val enabled: Boolean = true,
    /** Matched case-insensitively against the notification title / sender name. */
    val pattern: String,
    val note: String = ""
)

/** A notification the gate pulled out of the shade, kept for the next digest. */
@Entity(tableName = "held", indices = [Index("postedAt"), Index("releasedAt")])
data class HeldNotification(
    @PrimaryKey val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String? = null,
    val text: String? = null,
    val postedAt: Long,
    val notifClass: NotifClass,
    val modeId: String,
    /** Non-null once it has been handed back to the shade. */
    val releasedAt: Long? = null,
    /** When we asked the system to un-snooze it. */
    val snoozedUntil: Long? = null,
    /** True if we could only cancel it, so the original is gone for good. */
    val dropped: Boolean = false
)

/** An app you were let into despite the mode, and until when. */
@Entity(tableName = "passes")
data class AppPass(
    @PrimaryKey val packageName: String,
    val expiresAt: Long,
    val modeId: String
)
