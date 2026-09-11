package dev.jaronwilson.modes.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.launcher.AppList
import kotlinx.coroutines.launch
import java.io.File

/**
 * Dumps the installed app list to this app's own files directory, where adb can
 * read it without root.
 *
 * The point is planning: package names are the one thing you cannot guess from
 * a desktop, and the defaults shipped with this app are educated guesses about
 * a phone nobody has seen. Run tools/pull-apps.sh, look at what is actually
 * installed, then build your folders from real names.
 *
 *   adb shell am broadcast -a dev.jaronwilson.modes.EXPORT_APPS \
 *     -n dev.jaronwilson.modes/.tools.ExportReceiver
 */
class ExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXPORT) return
        AppGraph.ensure(context)
        val pending = goAsync()
        AppGraph.scope.launch {
            try {
                val dir = context.getExternalFilesDir(null) ?: context.filesDir
                val apps = AppList.all(context).sortedBy { it.label.lowercase() }
                val modes = AppGraph.repo.modeDao.getAll()
                val entries = modes.associate { m -> m.id to AppGraph.repo.homeDao.forMode(m.id) }

                File(dir, "apps.json").writeText(buildJson(apps))
                File(dir, "apps.txt").writeText(buildText(apps))
                File(dir, "layout.txt").writeText(buildLayout(context, modes.map { it.id to it.name }, entries))

                Log.i(TAG, "exported ${apps.size} apps to ${dir.absolutePath}")
            } catch (t: Throwable) {
                Log.w(TAG, "export failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun buildJson(apps: List<dev.jaronwilson.modes.launcher.AppEntry>): String =
        apps.joinToString(",\n  ", prefix = "[\n  ", postfix = "\n]") { app ->
            """{"label": ${quote(app.label)}, "package": ${quote(app.packageName)}}"""
        }

    private fun buildText(apps: List<dev.jaronwilson.modes.launcher.AppEntry>): String {
        val width = apps.maxOfOrNull { it.label.length } ?: 0
        return buildString {
            appendLine("# ${apps.size} launchable apps")
            appendLine()
            apps.forEach { app ->
                appendLine(app.label.padEnd(width + 2) + app.packageName)
            }
        }
    }

    private fun buildLayout(
        context: Context,
        modes: List<Pair<String, String>>,
        entries: Map<String, List<dev.jaronwilson.modes.core.model.HomeEntry>>
    ): String = buildString {
        appendLine("# Current home screens")
        modes.forEach { (id, name) ->
            appendLine()
            appendLine("## $name ($id)")
            entries[id].orEmpty().sortedBy { it.sortOrder }.forEach { entry ->
                if (entry.isFolder) {
                    appendLine("  [${entry.folderName}]")
                    entry.packages.forEach {
                        appendLine("      ${AppList.label(context, it)}  ($it)")
                    }
                } else {
                    val pkg = entry.packageName.orEmpty()
                    appendLine("  ${AppList.label(context, pkg)}  ($pkg)")
                }
            }
        }
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    companion object {
        const val ACTION_EXPORT = "dev.jaronwilson.modes.EXPORT_APPS"
        private const val TAG = "ExportReceiver"
    }
}
