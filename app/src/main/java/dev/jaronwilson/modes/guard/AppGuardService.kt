package dev.jaronwilson.modes.guard

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.core.model.AppPass
import dev.jaronwilson.modes.core.model.EventKind
import dev.jaronwilson.modes.core.repo.Stats
import dev.jaronwilson.modes.core.model.GuardMode
import dev.jaronwilson.modes.core.model.Mode
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional. Watches which app comes to the foreground and holds you to the
 * current mode's blocklist.
 *
 * This is the part that makes the difference between a phone that suggests you
 * focus and one that actually helps. For most apps it reads nothing but the
 * package name of the foreground window. The one exception is a web browser:
 * there it also reads the address bar (see [WebGuard]) so a site you set aside
 * cannot simply be opened in Chrome instead. It reads no other screen content.
 */
class AppGuardService : AccessibilityService() {

    private val lastActed = ConcurrentHashMap<String, Long>()

    /** package -> (measured at, foreground millis today). Short-lived. */
    private val usageCache = ConcurrentHashMap<String, Pair<Long, Long>>()

    /** Throttles reading a browser's address bar; content changes fire often. */
    @Volatile
    private var lastBrowserAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppGraph.ensure(applicationContext)
        instance = this
        Log.i(TAG, "guard connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleForeground(pkg)
                if (WebGuard.isBrowser(pkg)) handleBrowser(pkg)
            }
            // A browser navigating within one tab changes content, not window.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (WebGuard.isBrowser(pkg)) handleBrowser(pkg)
        }
    }

    /** The app blocklist plus daily ceilings, for the app now in front. */
    private fun handleForeground(pkg: String) {
        val policy = AppGraph.repo.snapshot ?: return
        if (!policy.guardEnabled) return
        val mode = policy.mode
        if (mode.guardMode == GuardMode.OFF) return

        val baseGuard = GuardPolicy.shouldGuard(
            pkg = pkg,
            ownPackage = packageName,
            mode = mode,
            homePackages = policy.homePackages,
            isLaunchable = ::isLaunchable,
            alwaysAllowed = policy.alwaysAllowed
        )
        val maybeLimited = pkg in mode.dailyLimits
        if (!baseGuard && !maybeLimited) return

        val now = System.currentTimeMillis()
        // Window state changes fire several times as an app opens.
        val last = lastActed[pkg] ?: 0L
        if (now - last < DEBOUNCE_MS) return
        if (passes[pkg]?.let { it > now } == true) return

        lastActed[pkg] = now
        AppGraph.scope.launch {
            val stillPassed = AppGraph.repo.passDao.activeFor(pkg, now)
            if (stillPassed != null) {
                passes[pkg] = stillPassed.expiresAt
                return@launch
            }
            // The daily-limit check touches UsageStats, so keep it off the main
            // thread; the blocklist decision above needed none of that.
            val over = maybeLimited && overLimitToday(mode, pkg)
            val guard = baseGuard || GuardPolicy.shouldGuard(
                pkg = pkg,
                ownPackage = packageName,
                mode = mode,
                homePackages = policy.homePackages,
                isLaunchable = ::isLaunchable,
                alwaysAllowed = policy.alwaysAllowed,
                overLimitToday = if (over) setOf(pkg) else emptySet()
            )
            if (!guard) return@launch
            Stats.log(EventKind.GUARD_STOPPED, pkg, if (over) "LIMIT" else mode.guardMode.name)
            act(pkg, mode, hardBlock = over)
        }
    }

    /** The website side: a guarded app's site opened in a browser is guarded too. */
    private fun handleBrowser(browserPkg: String) {
        val policy = AppGraph.repo.snapshot ?: return
        if (!policy.guardEnabled) return
        val mode = policy.mode
        if (mode.guardMode == GuardMode.OFF) return

        val now = System.currentTimeMillis()
        if (now - lastBrowserAt < BROWSER_DEBOUNCE_MS) return
        lastBrowserAt = now

        val root = rootInActiveWindow ?: return
        val host = try {
            WebGuard.readHost(root, browserPkg)
        } finally {
            runCatching { root.recycle() }
        } ?: return
        val owner = WebGuard.appForHost(host) ?: return
        if (owner in policy.alwaysAllowed) return
        if (passes[owner]?.let { it > now } == true) return

        AppGraph.scope.launch {
            val pass = AppGraph.repo.passDao.activeFor(owner, now)
            if (pass != null) {
                passes[owner] = pass.expiresAt
                return@launch
            }
            val over = owner in mode.dailyLimits && overLimitToday(mode, owner)
            val guard = GuardPolicy.shouldGuard(
                pkg = owner,
                ownPackage = packageName,
                mode = mode,
                homePackages = policy.homePackages,
                isLaunchable = ::isLaunchable,
                alwaysAllowed = policy.alwaysAllowed,
                overLimitToday = if (over) setOf(owner) else emptySet()
            )
            if (!guard) return@launch
            Stats.log(EventKind.GUARD_STOPPED, owner, "WEB")
            act(owner, mode, hardBlock = true)
        }
    }

    /**
     * Send you home or pause you. [hardBlock] forces the send-home path even
     * when the mode would only pause: a spent daily limit or a blocked website
     * is a stop, not a nudge.
     */
    private fun act(pkg: String, mode: Mode, hardBlock: Boolean) {
        val effective = if (hardBlock) GuardMode.BLOCK else mode.guardMode
        when (effective) {
            GuardMode.BLOCK -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                InterstitialActivity.launch(this, pkg, mode.id, blocking = true)
            }
            GuardMode.SPEEDBUMP ->
                InterstitialActivity.launch(this, pkg, mode.id, blocking = false)
            GuardMode.OFF -> Unit
        }
    }

    private fun overLimitToday(mode: Mode, pkg: String): Boolean {
        val limitMinutes = mode.dailyLimits[pkg] ?: return false
        if (limitMinutes <= 0) return false
        return usedTodayMillis(pkg) >= limitMinutes * 60_000L
    }

    /** Foreground time today for [pkg], cached briefly to keep queries cheap. */
    private fun usedTodayMillis(pkg: String): Long {
        val now = System.currentTimeMillis()
        usageCache[pkg]?.let { (asOf, value) ->
            if (now - asOf < USAGE_TTL_MS) return value
        }
        val usm = getSystemService(UsageStatsManager::class.java) ?: return 0L
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfToday(), now)
        }.getOrNull().orEmpty()
        val total = stats.filter { it.packageName == pkg }.sumOf { it.totalTimeInForeground }
        usageCache[pkg] = now to total
        return total
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    override fun onInterrupt() = Unit

    private fun isLaunchable(pkg: String): Boolean = runCatching {
        packageManager.getLaunchIntentForPackage(pkg) != null
    }.getOrDefault(false)

    companion object {
        private const val TAG = "AppGuard"
        private const val DEBOUNCE_MS = 1500L
        private const val BROWSER_DEBOUNCE_MS = 700L
        private const val USAGE_TTL_MS = 20_000L

        @Volatile
        var instance: AppGuardService? = null
            private set

        /** Package -> when its pass expires. Mirrors the database for speed. */
        val passes = ConcurrentHashMap<String, Long>()

        suspend fun grantPass(pkg: String, modeId: String, minutes: Int) {
            val expires = System.currentTimeMillis() + minutes * 60_000L
            passes[pkg] = expires
            AppGraph.repo.passDao.upsert(AppPass(pkg, expires, modeId))
        }

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.split(':').any { it.startsWith(context.packageName) }
        }

        fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
}
