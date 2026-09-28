package dev.jaronwilson.modes.remote

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.util.Log
import dev.jaronwilson.modes.ModesApp
import dev.jaronwilson.modes.R

/**
 * Invisible go-between for [ApkInstaller]'s install session.
 *
 * The session's status callback has to open Android's own install screen, and
 * only an activity may launch another activity on modern Android; a broadcast
 * receiver is silently refused. So the session targets this translucent activity
 * instead: on STATUS_PENDING_USER_ACTION it launches the confirm screen, and on
 * success or failure it posts a notification (success taps through to open the
 * app). It draws nothing and finishes at once.
 */
class InstallLauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { startActivity(confirm) }
                        .onFailure { Log.w(TAG, "could not show install prompt", it) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                val open = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
                notify("Installed", if (open != null) "Tap to open." else "Done.", open)
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                notify("Install failed", message ?: "status $status", null)
            }
        }
    }

    private fun notify(title: String, text: String, open: Intent?) {
        runCatching {
            val builder = Notification.Builder(this, ModesApp.CH_REMOTE)
                .setSmallIcon(R.drawable.ic_stat_modes)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
            if (open != null) {
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        this, 0, open,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            }
            getSystemService(NotificationManager::class.java)
                .notify((title + text).hashCode(), builder.build())
        }
    }

    private companion object {
        const val TAG = "InstallLauncher"
    }
}
