package dev.jaronwilson.modes.guard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.core.model.AppPass
import dev.jaronwilson.modes.core.model.GuardMode
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional. Watches which app comes to the foreground and holds you to the
 * current mode's blocklist.
 *
 * This is the part that makes the difference between a phone that suggests you
 * focus and one that actually helps. It reads nothing but the package name of
 * the foreground window: [android.R.attr.canRetrieveWindowContent] is false, so
 * it cannot see anything on screen.
 */
class AppGuardService : AccessibilityService() {

    private val lastActed = ConcurrentHashMap<String, Long>()

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
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val policy = AppGraph.repo.snapshot ?: return
        if (!policy.guardEnabled) return
        val mode = policy.mode
        if (mode.guardMode == GuardMode.OFF) return
        if (!GuardPolicy.shouldGuard(
                pkg = pkg,
                ownPackage = packageName,
                mode = mode,
                homePackages = policy.homePackages,
                isLaunchable = ::isLaunchable
            )
        ) return

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
            when (mode.guardMode) {
                GuardMode.BLOCK -> {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    InterstitialActivity.launch(
                        this@AppGuardService, pkg, mode.id, blocking = true
                    )
                }
                GuardMode.SPEEDBUMP -> {
                    InterstitialActivity.launch(
                        this@AppGuardService, pkg, mode.id, blocking = false
                    )
                }
                GuardMode.OFF -> Unit
            }
        }
    }

    override fun onInterrupt() = Unit

    private fun isLaunchable(pkg: String): Boolean = runCatching {
        packageManager.getLaunchIntentForPackage(pkg) != null
    }.getOrDefault(false)

    companion object {
        private const val TAG = "AppGuard"
        private const val DEBOUNCE_MS = 1500L

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
