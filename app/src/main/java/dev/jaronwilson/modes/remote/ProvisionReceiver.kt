package dev.jaronwilson.modes.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jaronwilson.modes.AppGraph
import kotlinx.coroutines.launch

/**
 * Set the remote-control token over adb, instead of typing it.
 *
 * The token is 43 random characters. Typing that on a phone keyboard is
 * miserable and easy to get subtly wrong, and a wrong token fails as a flat
 * 401 with nothing to tell you which end is at fault. Odysseus already mints
 * it, so provisioning it directly removes the transcription step entirely.
 *
 *     adb shell am broadcast -a dev.jaronwilson.modes.SET_REMOTE_TOKEN \
 *       -n dev.jaronwilson.modes/.remote.ProvisionReceiver \
 *       --es token "<token>" --ez enabled true
 *
 * This mirrors [dev.jaronwilson.modes.tools.ExportReceiver], which the app
 * already exposes for adb the same way, and carries the same caveat: an
 * exported receiver can be triggered by any app on the device, not only by
 * adb. What that buys an attacker is small — it can set a token but never
 * read one, and an app that could abuse the resulting listener to launch
 * something could simply launch it itself. Rotating the token from Odysseus
 * locks out anyone who tried.
 */
class ProvisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.ensure(context)
        val token = intent.getStringExtra("token")?.trim().orEmpty()
        if (token.isEmpty()) {
            Log.w(TAG, "ignored: no token extra (pass --es token \"<token>\")")
            return
        }
        // Default true: provisioning a token and leaving the listener off is
        // almost never what was meant, and `--ez enabled false` still says so.
        val enable = intent.getBooleanExtra("enabled", true)
        val pending = goAsync()
        AppGraph.scope.launch {
            try {
                AppGraph.repo.settings.setRemoteToken(token)
                AppGraph.repo.settings.setRemoteEnabled(enable)
                if (enable) RemoteControlService.start(context)
                else RemoteControlService.stop(context)
                // Length only. Logcat is readable by anything with adb, so the
                // token itself must never appear there.
                Log.i(TAG, "remote token set (${token.length} chars), listening=$enable")
            } catch (t: Throwable) {
                Log.w(TAG, "could not set remote token", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "RemoteProvision"
    }
}
