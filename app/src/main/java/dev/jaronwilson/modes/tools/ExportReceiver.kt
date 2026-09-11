package dev.jaronwilson.modes.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jaronwilson.modes.AppGraph
import kotlinx.coroutines.launch

/**
 * Lets tools/pull-apps.sh ask for a dump over adb, without opening the app.
 *
 *   adb shell am broadcast -a dev.jaronwilson.modes.EXPORT_APPS \
 *     -n dev.jaronwilson.modes/.tools.ExportReceiver
 *
 * The same report is available inside the app, under Now, for when there is no
 * computer to hand.
 */
class ExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXPORT) return
        AppGraph.ensure(context)
        val pending = goAsync()
        AppGraph.scope.launch {
            try {
                val file = AppListExport.writeFiles(context, AppGraph.repo)
                Log.i(TAG, "exported to ${file.absolutePath}")
            } catch (t: Throwable) {
                Log.w(TAG, "export failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_EXPORT = "dev.jaronwilson.modes.EXPORT_APPS"
        private const val TAG = "ExportReceiver"
    }
}
