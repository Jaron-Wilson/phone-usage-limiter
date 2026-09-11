package dev.jaronwilson.modes.core

import android.app.NotificationManager
import dev.jaronwilson.modes.core.model.CalendarRule
import dev.jaronwilson.modes.core.model.GuardMode
import dev.jaronwilson.modes.core.model.Folder
import dev.jaronwilson.modes.core.model.GuardScope
import dev.jaronwilson.modes.core.model.HomeEntry
import dev.jaronwilson.modes.core.model.MatchField
import dev.jaronwilson.modes.core.model.Mode
import dev.jaronwilson.modes.core.model.NotifClass
import dev.jaronwilson.modes.core.model.NotifRule
import dev.jaronwilson.modes.core.model.TimeRule

/** Package names worth knowing by name. Everything else is discovered at runtime. */
object Pkg {
    const val DIALER = "com.google.android.dialer"
    const val MESSAGES = "com.google.android.apps.messaging"
    const val CONTACTS = "com.google.android.contacts"
    const val CLOCK = "com.google.android.deskclock"
    const val CALENDAR = "com.google.android.calendar"
    const val GMAIL = "com.google.android.gm"
    const val MAPS = "com.google.android.apps.maps"
    const val KEEP = "com.google.android.keep"
    const val CAMERA = "com.google.android.GoogleCamera"
    const val WALLET = "com.google.android.apps.walletnfcrel"
    const val CHROME = "com.android.chrome"
    const val SETTINGS = "com.android.settings"
    const val INSTAGRAM = "com.instagram.android"
    const val WHATSAPP = "com.whatsapp"
    const val SIGNAL = "org.thoughtcrime.securesms"
    const val MESSENGER = "com.facebook.orca"
    const val SLACK = "com.Slack"
    const val SPOTIFY = "com.spotify.music"
    const val YOUTUBE = "com.google.android.youtube"
    const val TIKTOK = "com.zhiliaoapp.musically"
    const val X = "com.twitter.android"
    const val REDDIT = "com.reddit.frontpage"
    const val DRIVE = "com.google.android.apps.docs"
    const val PHOTOS = "com.google.android.apps.photos"
    const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
    const val PODCASTS = "com.google.android.apps.podcasts"
    const val AUTHENTICATOR = "com.google.android.apps.authenticator2"

    // Banking and money. These are the common US apps; yours may differ, so
    // run tools/pull-apps.sh to see what is actually on the phone and edit the
    // Money folder in the app.
    const val CHASE = "com.chase.sig.android"
    const val BOFA = "com.infonow.bofa"
    const val WELLS_FARGO = "com.wf.wellsfargomobile"
    const val CAPITAL_ONE = "com.konylabs.capitalone"
    const val CITI = "com.citi.citimobile"
    const val ALLY = "com.ally.MobileBanking"
    const val DISCOVER = "com.discoverfinancial.mobile"
    const val AMEX = "com.americanexpress.android.acctsvcs.us"
    const val USAA = "com.usaa.mobile.android.usaa"
    const val PAYPAL = "com.paypal.android.p2pmobile"
    const val VENMO = "com.venmo"
    const val CASH_APP = "com.squareup.cash"
    const val ZELLE = "com.zellepay.zelle"
    const val ROBINHOOD = "com.robinhood.android"
    const val FIDELITY = "com.fidelity.android"
    const val SCHWAB = "com.schwab.mobile"
    const val CHIME = "com.onedebit.chime"
    const val SOFI = "com.sofi.mobile"
    const val CREDIT_KARMA = "com.creditkarma.mobile"

    /**
     * Money apps. Allowed to interrupt in every mode by default, and never
     * guarded: a fraud alert you did not see is worse than any distraction.
     */
    val FINANCE = setOf(
        CHASE, BOFA, WELLS_FARGO, CAPITAL_ONE, CITI, ALLY, DISCOVER, AMEX, USAA,
        PAYPAL, VENMO, CASH_APP, ZELLE, ROBINHOOD, FIDELITY, SCHWAB, CHIME,
        SOFI, CREDIT_KARMA, WALLET
    )

    /** Apps that should never be hidden or guarded: you always need a way out. */
    val ESSENTIAL = setOf(DIALER, MESSAGES, CLOCK, SETTINGS, CONTACTS, AUTHENTICATOR)

    /** Sensible starting guess for apps worth putting behind friction. */
    val DISTRACTING = setOf(INSTAGRAM, YOUTUBE, TIKTOK, X, REDDIT)
}

/** The class an app's notifications fall into when no rule matches. */
val PACKAGE_DEFAULT_CLASS: Map<String, NotifClass> = mapOf(
    Pkg.DIALER to NotifClass.CALL,
    Pkg.MESSAGES to NotifClass.DIRECT,
    Pkg.WHATSAPP to NotifClass.DIRECT,
    Pkg.SIGNAL to NotifClass.DIRECT,
    Pkg.MESSENGER to NotifClass.DIRECT,
    Pkg.SLACK to NotifClass.MENTION,
    Pkg.GMAIL to NotifClass.OTHER,
    Pkg.CALENDAR to NotifClass.OTHER,
    // Instagram's baseline is noise. Rules and the reply-action check below
    // promote the handful of things that are not.
    Pkg.INSTAGRAM to NotifClass.SOCIAL,
    Pkg.YOUTUBE to NotifClass.PROMO,
    Pkg.TIKTOK to NotifClass.PROMO,
    Pkg.X to NotifClass.SOCIAL,
    Pkg.REDDIT to NotifClass.SOCIAL,
    "android" to NotifClass.SYSTEM,
    "com.google.android.gms" to NotifClass.SYSTEM
) + Pkg.FINANCE.associateWith { NotifClass.FINANCE }

private fun hm(h: Int, m: Int = 0) = h * 60 + m

object Defaults {

    const val MODE_OPEN = "open"
    const val MODE_WORK = "work"
    const val MODE_FOCUS = "focus"
    const val MODE_PERSONAL = "personal"
    const val MODE_SLEEP = "sleep"

    fun modes(): List<Mode> = listOf(
        Mode(
            id = MODE_OPEN,
            name = "Open",
            glyph = "○",
            sortOrder = 0,
            isDefault = true,
            allowedClasses = NotifClass.entries.toSet(),
            guardMode = GuardMode.OFF,
            guardScope = GuardScope.BLOCKLIST,
            interruptionFilter = 0,
            digestEveryMinutes = 0,
            releaseOnModeExit = true
        ),
        Mode(
            id = MODE_WORK,
            name = "Work",
            glyph = "◑",
            sortOrder = 1,
            allowedClasses = setOf(
                NotifClass.CALL, NotifClass.DIRECT, NotifClass.MENTION, NotifClass.FINANCE
            ),
            allowedPackages = setOf(Pkg.CALENDAR),
            blockedPackages = setOf(Pkg.INSTAGRAM, Pkg.YOUTUBE, Pkg.TIKTOK, Pkg.REDDIT, Pkg.X),
            // Anything not in a Work folder is off-limits, so an app you install
            // next week does not quietly become a new way to lose an afternoon.
            guardMode = GuardMode.SPEEDBUMP,
            guardScope = GuardScope.ALLOWLIST,
            speedbumpSeconds = 10,
            passMinutes = 5,
            interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            // Digest twice: after the morning block and at the end of the day.
            digestTimes = listOf(hm(12, 30), hm(17, 0))
        ),
        Mode(
            id = MODE_FOCUS,
            name = "Deep focus",
            glyph = "●",
            sortOrder = 2,
            // Calls only. A phone that can still be reached in an emergency is
            // a phone you can actually leave face-down.
            allowedClasses = setOf(NotifClass.CALL),
            blockedPackages = Pkg.DISTRACTING + setOf(Pkg.GMAIL, Pkg.SLACK, Pkg.CHROME),
            guardMode = GuardMode.BLOCK,
            guardScope = GuardScope.ALLOWLIST,
            speedbumpSeconds = 20,
            passMinutes = 3,
            interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            allowCallsFrom = 2, // starred contacts
            allowMessagesFrom = 0, // none
            allowRepeatCallers = true,
            grayscale = true,
            dimWallpaper = true,
            suppressAmbientDisplay = true,
            digestEveryMinutes = 0,
            releaseOnModeExit = true
        ),
        Mode(
            id = MODE_PERSONAL,
            name = "Personal",
            glyph = "◔",
            sortOrder = 3,
            allowedClasses = setOf(
                NotifClass.CALL, NotifClass.DIRECT, NotifClass.MENTION,
                NotifClass.STORY, NotifClass.FINANCE
            ),
            guardMode = GuardMode.SPEEDBUMP,
            guardScope = GuardScope.BLOCKLIST,
            interruptionFilter = 0,
            digestEveryMinutes = 90
        ),
        Mode(
            id = MODE_SLEEP,
            name = "Sleep",
            glyph = "◐",
            sortOrder = 4,
            allowedClasses = setOf(NotifClass.CALL),
            blockedPackages = Pkg.DISTRACTING,
            guardMode = GuardMode.SPEEDBUMP,
            guardScope = GuardScope.ALLOWLIST,
            speedbumpSeconds = 30,
            passMinutes = 5,
            interruptionFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            allowCallsFrom = 2,
            allowMessagesFrom = 0,
            allowRepeatCallers = true,
            grayscale = true,
            dimWallpaper = true,
            suppressAmbientDisplay = true,
            // Everything held overnight lands once, with breakfast.
            digestTimes = listOf(hm(7, 30)),
            releaseOnModeExit = false
        )
    )

    fun timeRules(): List<TimeRule> = listOf(
        TimeRule(
            daysMask = 0b1111111,
            startMinute = hm(22, 30),
            endMinute = hm(7, 0),
            modeId = MODE_SLEEP,
            priority = 10,
            note = "Nightly wind-down"
        ),
        TimeRule(
            daysMask = 0b0011111, // Mon-Fri
            startMinute = hm(9, 0),
            endMinute = hm(17, 30),
            modeId = MODE_WORK,
            priority = 1,
            note = "Default working hours, overridden by calendar rules"
        ),
        TimeRule(
            daysMask = 0b1100000, // Sat-Sun
            startMinute = hm(9, 0),
            endMinute = hm(22, 0),
            modeId = MODE_PERSONAL,
            priority = 1,
            note = "Weekends"
        )
    )

    fun calendarRules(): List<CalendarRule> = listOf(
        CalendarRule(
            titlePattern = "(?i)(deep work|focus|heads.?down|writing|no meetings|study|blocked)",
            busyOnly = false,
            modeId = MODE_FOCUS,
            priority = 100,
            note = "Any event you name for focus wins over everything else"
        ),
        CalendarRule(
            titlePattern = "(?i)(sleep|bed|rest)",
            busyOnly = false,
            modeId = MODE_SLEEP,
            priority = 90,
            note = "Explicit sleep events"
        ),
        CalendarRule(
            titlePattern = null,
            busyOnly = true,
            includeAllDay = false,
            modeId = MODE_WORK,
            priority = 10,
            note = "Any busy event switches to Work"
        )
    )

    // Seeded folders get fixed ids so the starting home screens can point at
    // them. Room only auto-assigns when the id is zero.
    const val FOLDER_EVERYDAY = 1L
    const val FOLDER_WORK = 2L
    const val FOLDER_MONEY = 3L
    const val FOLDER_SOCIAL = 4L
    const val FOLDER_MEDIA = 5L
    const val FOLDER_TOOLS = 6L
    const val FOLDER_HOUSE = 7L

    /**
     * The shared folder library.
     *
     * Defined once, switched on per mode. The grouping of your apps rarely
     * changes; what you should be able to reach at 10am on a Tuesday does.
     */
    fun folders(): List<Folder> = listOf(
        Folder(
            id = FOLDER_EVERYDAY, sortOrder = 0, name = "Everyday",
            packages = listOf(Pkg.GMAIL, Pkg.MAPS, Pkg.CHROME)
        ),
        Folder(
            id = FOLDER_WORK, sortOrder = 1, name = "Work",
            packages = listOf(Pkg.GMAIL, Pkg.SLACK, Pkg.KEEP, Pkg.DRIVE, Pkg.CALENDAR)
        ),
        Folder(
            id = FOLDER_MONEY, sortOrder = 2, name = "Money",
            packages = Pkg.FINANCE.toList()
        ),
        Folder(
            id = FOLDER_SOCIAL, sortOrder = 3, name = "Social",
            packages = listOf(Pkg.INSTAGRAM, Pkg.X, Pkg.REDDIT, Pkg.TIKTOK)
        ),
        Folder(
            id = FOLDER_MEDIA, sortOrder = 4, name = "Media",
            packages = listOf(Pkg.SPOTIFY, Pkg.YOUTUBE, Pkg.YOUTUBE_MUSIC, Pkg.PODCASTS)
        ),
        Folder(
            id = FOLDER_TOOLS, sortOrder = 5, name = "Tools",
            packages = listOf(Pkg.KEEP, Pkg.CALENDAR, Pkg.CLOCK, Pkg.AUTHENTICATOR)
        ),
        Folder(
            id = FOLDER_HOUSE, sortOrder = 6, name = "Odds and ends",
            packages = listOf(Pkg.PHOTOS, Pkg.WALLET, Pkg.CAMERA)
        )
    )

    /**
     * The starting home screens.
     *
     * Read these as answers to "what is this mode for". Under
     * [GuardScope.ALLOWLIST] they are also the mode's permission list, so a
     * folder switched off is a set of apps you cannot open.
     *
     * Single apps sit at the top level, because a folder you open twenty times
     * a day is just friction. Everything else is grouped.
     */
    fun homeEntries(): List<HomeEntry> {

        fun layout(modeId: String, build: MutableList<HomeEntry>.() -> Unit): List<HomeEntry> {
            val rows = mutableListOf<HomeEntry>()
            rows.build()
            return rows.mapIndexed { i, e -> e.copy(modeId = modeId, sortOrder = i) }
        }

        fun app(pkg: String) = HomeEntry(modeId = "", packageName = pkg)
        fun folder(id: Long, on: Boolean = true) =
            HomeEntry(modeId = "", folderId = id, enabled = on)

        return buildList {
            addAll(
                layout(MODE_OPEN) {
                    add(app(Pkg.DIALER))
                    add(app(Pkg.MESSAGES))
                    add(app(Pkg.CALENDAR))
                    add(app(Pkg.CAMERA))
                    add(folder(FOLDER_EVERYDAY))
                    add(folder(FOLDER_MONEY))
                    add(folder(FOLDER_SOCIAL))
                    add(folder(FOLDER_MEDIA))
                    add(folder(FOLDER_HOUSE))
                }
            )
            addAll(
                layout(MODE_WORK) {
                    add(app(Pkg.DIALER))
                    add(app(Pkg.MESSAGES))
                    add(app(Pkg.CALENDAR))
                    add(folder(FOLDER_WORK))
                    add(folder(FOLDER_EVERYDAY))
                    add(folder(FOLDER_MONEY))
                    add(folder(FOLDER_SOCIAL, on = false))
                    add(folder(FOLDER_MEDIA, on = false))
                }
            )
            addAll(
                layout(MODE_FOCUS) {
                    add(app(Pkg.DIALER))
                    add(app(Pkg.MESSAGES))
                    add(app(Pkg.CLOCK))
                    add(folder(FOLDER_TOOLS))
                    add(folder(FOLDER_MONEY))
                    add(folder(FOLDER_EVERYDAY, on = false))
                    add(folder(FOLDER_SOCIAL, on = false))
                    add(folder(FOLDER_MEDIA, on = false))
                }
            )
            addAll(
                layout(MODE_PERSONAL) {
                    add(app(Pkg.DIALER))
                    add(app(Pkg.MESSAGES))
                    add(app(Pkg.CALENDAR))
                    add(app(Pkg.CAMERA))
                    add(folder(FOLDER_EVERYDAY))
                    add(folder(FOLDER_MONEY))
                    add(folder(FOLDER_SOCIAL))
                    add(folder(FOLDER_MEDIA))
                    add(folder(FOLDER_HOUSE))
                }
            )
            addAll(
                layout(MODE_SLEEP) {
                    add(app(Pkg.CLOCK))
                    add(app(Pkg.DIALER))
                    add(app(Pkg.MESSAGES))
                    add(folder(FOLDER_MONEY))
                    add(folder(FOLDER_SOCIAL, on = false))
                    add(folder(FOLDER_MEDIA, on = false))
                }
            )
        }
    }

    /**
     * Text rules that sort real messages from engagement bait.
     *
     * There is no Instagram API for DMs or stories, so this reads the
     * notifications Instagram already posts. Two things to know:
     *  - Instagram only notifies you about a story if you have notifications
     *    turned on for that person, so turn those on for the handful you care
     *    about and the STORY class does the rest.
     *  - DMs are detected in code, not here: a notification carrying an inline
     *    reply box is a DM, whatever its wording. See [Classifier].
     */
    fun notifRules(): List<NotifRule> = listOf(
        // ---- universal ----
        NotifRule(
            pattern = "(?i)\\b(incoming call|missed call|is calling|calling you)\\b",
            target = NotifClass.CALL, alwaysThrough = true,
            priority = 200, note = "Calls always win"
        ),
        NotifRule(
            pattern = "(?i)\\b(verification|security|login|access|one.time) code\\b|" +
                "\\b\\d{4,8} is your\\b|\\bdo not share this code\\b",
            target = NotifClass.DIRECT, alwaysThrough = true,
            priority = 195, note = "One-time codes, even at 3am"
        ),

        // ---- Instagram ----
        NotifRule(
            packageName = Pkg.INSTAGRAM,
            pattern = "(?i)\\b(mentioned you|tagged you in|commented|replied to your|answered your|" +
                "responded to your|sent you a request)\\b",
            target = NotifClass.MENTION, priority = 150, note = "Someone is talking to you"
        ),
        NotifRule(
            packageName = Pkg.INSTAGRAM,
            pattern = "(?i)\\b(posted a story|added to (their|his|her) story|shared a story|" +
                "is live now|started a live video|just went live|posted for the first time)\\b",
            target = NotifClass.STORY, priority = 140, note = "Stories and lives"
        ),
        NotifRule(
            packageName = Pkg.INSTAGRAM,
            pattern = "(?i)\\b(liked your|liked a|reacted to|started following you|" +
                "requested to follow|accepted your follow|and \\d+ others)\\b",
            target = NotifClass.SOCIAL, priority = 130, note = "Likes and follows"
        ),
        NotifRule(
            packageName = Pkg.INSTAGRAM,
            pattern = "(?i)(suggested for you|you might (like|know)|new posts? (from|for)|" +
                "see what .{1,40} has been up to|is on instagram|check (out|these)|" +
                "reels? (for|you)|popular (post|reel)|trending|based on your activity|" +
                "don.t miss|recently shared|back on instagram|you.ve been missed|" +
                "complete your profile|turn on notifications)",
            target = NotifClass.PROMO, priority = 145, note = "Engagement bait"
        ),

        // ---- money ----
        // Fraud is the one thing worth waking up for, so these break through
        // every mode, including Sleep and Deep focus.
        NotifRule(
            pattern = "(?i)\\b(fraud|suspicious (activity|charge|transaction)|unusual (activity|sign.?in)|" +
                "unauthori[sz]ed|card (was )?declined|security alert|account (locked|frozen|compromised)|" +
                "did you (make|try) this|verify this (charge|transaction)|" +
                "(overdraft|insufficient funds|payment (failed|declined)))\\b",
            target = NotifClass.FINANCE, alwaysThrough = true,
            priority = 185, note = "Fraud and account trouble"
        ),
        NotifRule(
            pattern = "(?i)\\b(deposit|direct deposit|payment (received|due|posted|sent)|" +
                "transaction|withdrawal|transfer|statement (is )?(ready|available)|" +
                "balance|you (paid|received|sent)|charged \\$|\\bautopay\\b)\\b",
            target = NotifClass.FINANCE,
            priority = 120, note = "Ordinary money movement"
        ),

        // ---- generic noise ----
        NotifRule(
            pattern = "(?i)\\b(\\d+% off|flash sale|limited time|deal of the|coupon|promo code|" +
                "offer ends|last chance|shop now|free trial|upgrade to)\\b",
            target = NotifClass.PROMO, priority = 40, note = "Marketing"
        ),
        NotifRule(
            field = MatchField.CHANNEL,
            pattern = "(?i)(promo|marketing|offers|recommend|suggestion|digest|newsletter)",
            target = NotifClass.PROMO, priority = 35, note = "Channels that name themselves"
        ),
        NotifRule(
            pattern = "(?i)\\b(sync (complete|failed)|backup|update available|now available|" +
                "storage (is )?(full|low)|download (complete|finished))\\b",
            target = NotifClass.SYSTEM, priority = 30, note = "Housekeeping"
        )
    )
}
