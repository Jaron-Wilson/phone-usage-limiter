package dev.jaronwilson.modes.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jaronwilson.modes.AppGraph
import kotlinx.coroutines.launch

/** "Deliver everything now", from a notification action or a quick tile. */
class DigestActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppGraph.ensure(context)
        val pending = goAsync()
        AppGraph.scope.launch {
            try {
                when (intent.action) {
                    ACTION_RELEASE_NOW ->
                        DigestPublisher(context, AppGraph.repo).releaseAll("Delivered on request")
                }
            } catch (t: Throwable) {
                Log.w("DigestAction", "failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RELEASE_NOW = "dev.jaronwilson.modes.RELEASE_NOW"
    }
}
