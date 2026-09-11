package dev.jaronwilson.modes.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable? = null,
    /** False when the app is installed but has no home screen icon of its own. */
    val launchable: Boolean = true,
    val system: Boolean = false
)

/**
 * Everything installed, cached because it rarely changes.
 *
 * Enumerating apps on a modern Android is fussier than it looks. Two traps,
 * both of which this hit:
 *
 *  - `MATCH_DEFAULT_ONLY` seems like the right flag and is not. It restricts
 *    results to activities declaring `CATEGORY_DEFAULT`, which a launcher entry
 *    has no reason to declare, so it silently drops real apps.
 *  - An app can be installed, resolvable by package name, and still absent from
 *    the launcher query: archived apps are the common case.
 *
 * So the launcher query is unioned with the installed-application list, and the
 * result records whether each app actually has an icon.
 */
object AppList {

    @Volatile
    private var cache: List<AppEntry>? = null

    /** Apps you can open. What the launcher and the pickers show. */
    fun all(context: Context, withIcons: Boolean = false): List<AppEntry> {
        if (!withIcons) cache?.let { return it }
        val entries = enumerate(context, withIcons).filter { it.launchable }
        if (!withIcons) cache = entries
        return entries
    }

    /**
     * Everything installed, including apps with no launcher icon. Used by the
     * dump, where knowing an app exists is the point even if it is archived.
     */
    fun installed(context: Context): List<AppEntry> = enumerate(context, withIcons = false)

    private fun enumerate(context: Context, withIcons: Boolean): List<AppEntry> {
        val pm = context.packageManager
        val byPackage = LinkedHashMap<String, AppEntry>()

        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // Flags deliberately zero: see the note above about MATCH_DEFAULT_ONLY.
        val resolved = runCatching { pm.queryIntentActivities(intent, 0) }
            .getOrDefault(emptyList())

        for (info in resolved) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg == context.packageName) continue
            byPackage[pkg] = AppEntry(
                packageName = pkg,
                label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg),
                icon = if (withIcons) runCatching { info.loadIcon(pm) }.getOrNull() else null,
                launchable = true,
                system = info.activityInfo?.applicationInfo?.isSystem() ?: false
            )
        }

        // Catch anything installed that the launcher query missed.
        val installed = runCatching { pm.getInstalledApplications(0) }
            .getOrDefault(emptyList())
        for (app in installed) {
            val pkg = app.packageName
            if (pkg == context.packageName || byPackage.containsKey(pkg)) continue
            val hasLaunch = runCatching { pm.getLaunchIntentForPackage(pkg) != null }
                .getOrDefault(false)
            // System packages without an icon are plumbing, not apps.
            if (!hasLaunch && app.isSystem()) continue
            byPackage[pkg] = AppEntry(
                packageName = pkg,
                label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(pkg),
                launchable = hasLaunch,
                system = app.isSystem()
            )
        }

        return byPackage.values.sortedBy { it.label.lowercase() }
    }

    private fun ApplicationInfo.isSystem(): Boolean =
        flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    fun label(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
    }.getOrDefault(false)

    /** Installed and openable. A folder row needs both. */
    fun isOpenable(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getLaunchIntentForPackage(pkg) != null
    }.getOrDefault(false)

    fun launch(context: Context, pkg: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        runCatching { context.startActivity(intent) }
    }

    fun invalidate() {
        cache = null
    }
}
