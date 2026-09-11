package dev.jaronwilson.modes.ui

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.jaronwilson.modes.guard.AppGuardService
import dev.jaronwilson.modes.notify.NotificationGate

data class PermItem(
    val key: String,
    val title: String,
    val why: String,
    val granted: Boolean,
    val required: Boolean,
    val intent: Intent?,
    val note: String? = null
)

/**
 * Every switch this app needs, why it needs it, and the screen that grants it.
 *
 * Two of these are awkward on a sideloaded build and the notes say so, because
 * finding out by trial and error is miserable.
 */
object Perms {

    fun all(context: Context): List<PermItem> {
        val nm = context.getSystemService(NotificationManager::class.java)
        val am = context.getSystemService(AlarmManager::class.java)

        return listOf(
            PermItem(
                key = "listener",
                title = "Notification access",
                why = "Lets the gate see what arrives and hold what you do not need yet. " +
                    "Nothing works without this.",
                granted = NotificationGate.isEnabled(context),
                required = true,
                intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                note = "Sideloaded apps are blocked from this switch until you open " +
                    "App info for Modes, tap the three dots and choose " +
                    "\"Allow restricted settings\"."
            ),
            PermItem(
                key = "dnd",
                title = "Do Not Disturb access",
                why = "Lets each mode set its own Do Not Disturb rule, so your modes show " +
                    "up in Android's own settings and survive reboots.",
                granted = runCatching { nm.isNotificationPolicyAccessGranted }.getOrDefault(false),
                required = true,
                intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            ),
            PermItem(
                key = "calendar",
                title = "Calendar",
                why = "Reads event titles and times so a meeting can switch you into Work " +
                    "and a block named \"deep work\" can switch you into focus.",
                granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_CALENDAR
                ) == PackageManager.PERMISSION_GRANTED,
                required = true,
                intent = appSettings(context)
            ),
            PermItem(
                key = "post",
                title = "Post notifications",
                why = "So the digest and the current-mode line can appear.",
                granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED,
                required = true,
                intent = appSettings(context)
            ),
            PermItem(
                key = "alarms",
                title = "Exact alarms",
                why = "Makes mode changes land on the minute instead of whenever the " +
                    "system feels like it.",
                granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    runCatching { am.canScheduleExactAlarms() }.getOrDefault(false),
                required = false,
                intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.parse("package:${context.packageName}"))
                } else null
            ),
            PermItem(
                key = "guard",
                title = "App guard",
                why = "Optional. Shows a pause when you open an app the current mode has " +
                    "set aside. It only reads which app is in front, never what is on screen.",
                granted = AppGuardService.isEnabled(context),
                required = false,
                intent = AppGuardService.settingsIntent(),
                note = "Same restricted-settings step as notification access."
            ),
            PermItem(
                key = "contacts",
                title = "Contacts",
                why = "Optional. Only used to suggest names when you build your list of " +
                    "people who always get through.",
                granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED,
                required = false,
                intent = appSettings(context)
            ),
            PermItem(
                key = "home",
                title = "Home screen",
                why = "Replaces your home screen with the current mode's: today's " +
                    "calendar and the apps the mode is for, on black. This is where " +
                    "the simplicity actually lives.",
                granted = isDefaultHome(context),
                required = true,
                intent = homeRoleIntent(context)
            ),
            PermItem(
                key = "usage",
                title = "Usage access",
                why = "Optional. Lets the Stats screen show screen time per app, " +
                    "from the system's own counters.",
                granted = hasUsageAccess(context),
                required = false,
                intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            )
        )
    }

    /**
     * The system's "set as home" dialog. Much better than dropping people in
     * Settings > Default apps and hoping. Falls back to the settings screen on
     * the rare device where the role is not available.
     */
    fun homeRoleIntent(context: Context): Intent {
        val rm = context.getSystemService(RoleManager::class.java)
        return if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
            rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
        } else {
            Intent(Settings.ACTION_HOME_SETTINGS)
        }
    }

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun appSettings(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))

    fun isDefaultHome(context: Context): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(
            intent, PackageManager.MATCH_DEFAULT_ONLY
        )
        resolved?.activityInfo?.packageName == context.packageName
    }.getOrDefault(false)

    val runtimePermissions: Array<String> = buildList {
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
}
