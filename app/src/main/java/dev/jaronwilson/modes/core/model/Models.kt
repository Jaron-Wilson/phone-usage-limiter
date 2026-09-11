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

/**
 * How the home screen draws itself.
 *
 * The difference is deliberate. Icons are faster to hit and pleasant to look
 * at, which is exactly why they belong in the mode where browsing is allowed
 * and not in the ones where you are meant to be doing something else. Text is
 * duller on purpose.
 */
enum class HomeStyle {
    /** Names only. Quiet, slower to scan, harder to drift into. */
    TEXT,
    /** Icons in a grid, folders as tiles. For when the phone is yours. */
    ICONS
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
    val homeStyle: HomeStyle = HomeStyle.TEXT,
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
 * A named group of apps, shared across every mode.
 *
 * Folders are defined once and switched on per mode, because the grouping
 * rarely changes but what you are allowed to reach does. "Social" means the
 * same three apps whether you are working or not; the difference is that Work
 * does not turn it on.
 */
@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Contents, in the order they appear when the folder is opened. */
    val packages: List<String> = emptyList(),
    val sortOrder: Int = 0
)

/**
 * Drops packages that are not on the phone.
 *
 * Shipped folders name apps you may not have, and a folder claiming twenty apps
 * when six exist is a folder you cannot reason about. Pure so it can be tested
 * without a package manager.
 */
fun Folder.pruned(isInstalled: (String) -> Boolean): Folder =
    copy(packages = packages.filter(isInstalled))

/** How many of a folder's apps are actually openable. */
fun Folder.installedCount(isInstalled: (String) -> Boolean): Int =
    packages.count(isInstalled)

/**
 * One row on a mode's home screen: either a single app, or a reference to a
 * [Folder] from the shared library.
 *
 * [enabled] is the per-mode switch. A folder turned off here is not just hidden:
 * under [GuardScope.ALLOWLIST] its apps are not reachable, so turning off
 * "Social" for Work is the same act as forbidding it.
 */
@Entity(tableName = "home_entries", indices = [Index("modeId"), Index("folderId")])
data class HomeEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modeId: String,
    val sortOrder: Int = 0,
    /** Set for a single-app row. */
    val packageName: String? = null,
    /** Set for a folder row, pointing into the shared library. */
    val folderId: Long? = null,
    val enabled: Boolean = true
)

/** A home entry with its folder resolved. What the launcher actually draws. */
data class HomeRow(
    val entry: HomeEntry,
    val folder: Folder?
) {
    val isFolder: Boolean get() = entry.folderId != null

    val name: String get() = folder?.name ?: entry.packageName.orEmpty()

    /** Folder contents, or the single app. Empty when switched off. */
    val packages: List<String>
        get() = when {
            !entry.enabled -> emptyList()
            folder != null -> folder.packages
            else -> listOfNotNull(entry.packageName)
        }

    /** Every package this row lets you reach. */
    val reachable: List<String> get() = packages
}

/**
 * Joins a mode's rows to the shared folder library, dropping rows that point at
 * a folder that no longer exists.
 */
fun resolveHomeRows(entries: List<HomeEntry>, folders: List<Folder>): List<HomeRow> {
    val byId = folders.associateBy { it.id }
    return entries
        .sortedBy { it.sortOrder }
        .mapNotNull { entry ->
            when {
                entry.folderId != null -> byId[entry.folderId]?.let { HomeRow(entry, it) }
                entry.packageName != null -> HomeRow(entry, null)
                else -> null
            }
        }
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

/** Something worth counting later. */
enum class EventKind {
    /** A mode started. detail = the mode it replaced. */
    MODE_CHANGED,
    /** The gate let a notification through. detail = its class. */
    NOTIF_ALLOWED,
    /** The gate held one. detail = its class. */
    NOTIF_HELD,
    /** The guard stopped you opening an app. detail = pause or home. */
    GUARD_STOPPED,
    /** You chose to go in anyway. */
    PASS_GRANTED,
    /** Held notifications were handed back. count = how many. */
    DIGEST_RELEASED,
    /** You opened an app from the home screen. */
    APP_OPENED
}

/**
 * One line in the ledger the Stats screen reads. Kept lean and pruned after a
 * month; this is for noticing patterns, not for surveillance.
 */
@Entity(tableName = "events", indices = [Index("at"), Index("kind")])
data class UsageEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val kind: EventKind,
    val modeId: String,
    val packageName: String? = null,
    val detail: String? = null,
    val count: Int = 1
)
