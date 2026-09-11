package dev.jaronwilson.modes.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable? = null
)

/** Everything with a launcher entry, cached because it rarely changes. */
object AppList {

    @Volatile
    private var cache: List<AppEntry>? = null

    fun all(context: Context, withIcons: Boolean = false): List<AppEntry> {
        cache?.takeIf { !withIcons }?.let { return it }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())

        val entries = resolved
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                AppEntry(
                    packageName = pkg,
                    label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg),
                    icon = if (withIcons) runCatching { info.loadIcon(pm) }.getOrNull() else null
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }

        if (!withIcons) cache = entries
        return entries
    }

    fun label(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
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
