package dev.jaronwilson.modes.guard

import dev.jaronwilson.modes.core.Pkg
import dev.jaronwilson.modes.core.model.GuardMode
import dev.jaronwilson.modes.core.model.GuardScope
import dev.jaronwilson.modes.core.model.Mode

/**
 * Decides whether opening an app should be stopped.
 *
 * Kept free of Android types on purpose. This is the rule that can lock you out
 * of your own phone if it is wrong, so it is worth being able to test it
 * exhaustively without a device.
 */
object GuardPolicy {

    /**
     * Surfaces that must always open. Stopping any of these either breaks the
     * phone or removes the means of undoing the mode, which would be a trap
     * rather than a tool.
     */
    val NEVER_GUARD: Set<String> = setOf(
        "com.android.systemui",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.android.intentresolver",
        "com.google.android.gms",
        "com.android.vending",
        "com.google.android.inputmethod.latin",
        "com.android.phone",
        "com.google.android.dialer"
    )

    /**
     * @param homePackages every package reachable from the mode's home screen,
     *   folders included. Under [GuardScope.ALLOWLIST] this is the permission
     *   list, which is why an app in no folder is an app you cannot open.
     * @param isLaunchable whether the package is something you could have
     *   opened deliberately. Keeps the pause off share sheets and the like.
     */
    fun shouldGuard(
        pkg: String,
        ownPackage: String,
        mode: Mode,
        homePackages: Set<String>,
        isLaunchable: (String) -> Boolean
    ): Boolean {
        if (mode.guardMode == GuardMode.OFF) return false
        if (pkg == ownPackage) return false
        if (pkg in NEVER_GUARD) return false
        if (pkg in Pkg.ESSENTIAL) return false
        // Money is never guarded. A fraud alert you cannot act on is worse than
        // any distraction it might cause.
        if (pkg in Pkg.FINANCE) return false

        // Explicitly set aside: always stopped, whatever the scope.
        if (pkg in mode.blockedPackages) return true
        if (mode.guardScope == GuardScope.BLOCKLIST) return false

        if (pkg in mode.allowedPackages) return false
        if (pkg in homePackages) return false
        return isLaunchable(pkg)
    }
}
