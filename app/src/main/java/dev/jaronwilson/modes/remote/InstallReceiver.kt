package dev.jaronwilson.modes.remote

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import dev.jaronwilson.modes.ModesApp
import dev.jaronwilson.modes.R

/**
 * Hears back from [ApkInstaller]'s install session.
 *
 * The one message that matters is STATUS_PENDING_USER_ACTION: the system is
 * ready and wants the user to confirm, handing back the very intent that shows
 * that prompt. We just launch it. Success and failure are surfaced as a quiet
 * notification so a background download does not fail silently.
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { Log.w(TAG, "could not show install prompt", it) }
            }
            PackageInstaller.STATUS_SUCCESS ->
                notify(context, "Installed", "The limited app is installed.")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                notify(context, "Install failed", message ?: "status $status")
            }
        }
    }

    private fun notify(context: Context, title: String, text: String) {
        runCatching {
            val n = android.app.Notification.Builder(context, ModesApp.CH_REMOTE)
                .setSmallIcon(R.drawable.ic_stat_modes)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify((title + text).hashCode(), n)
        }
    }

    private companion object {
        const val TAG = "InstallReceiver"
    }
}
