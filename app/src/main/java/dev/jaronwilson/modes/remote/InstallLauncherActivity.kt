package dev.jaronwilson.modes.remote

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Invisible go-between for [ApkInstaller]'s install session.
 *
 * The session's status callback has to open Android's own install screen, and
 * only an activity may launch another activity on modern Android; a broadcast
 * receiver is silently refused. So the session targets this translucent activity
 * instead: on STATUS_PENDING_USER_ACTION it launches the confirm screen, and on
 * success or failure it shows a short toast. It draws nothing and finishes at
 * once, so it never appears as a screen of its own.
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
            PackageInstaller.STATUS_SUCCESS ->
                toast("Installed.")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                toast("Install failed: ${message ?: "status $status"}")
            }
        }
    }

    private fun toast(text: String) {
        runCatching { Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show() }
    }

    private companion object {
        const val TAG = "InstallLauncher"
    }
}
