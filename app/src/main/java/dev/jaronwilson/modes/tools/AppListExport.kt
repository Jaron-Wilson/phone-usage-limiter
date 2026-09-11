package dev.jaronwilson.modes.tools

import android.content.Context
import android.os.Build
import dev.jaronwilson.modes.core.repo.ModeRepository
import dev.jaronwilson.modes.core.model.resolveHomeRows
import dev.jaronwilson.modes.launcher.AppList
import java.io.File

/**
 * Builds a plain-text picture of the phone: every launchable app with its
 * package name, and each mode's home screen as it currently stands.
 *
 * Package names are the one thing you cannot guess from a desktop, and the
 * shipped folders are educated guesses about a phone nobody has seen. This is
 * how you close that gap: dump the list, read it somewhere with a keyboard,
 * then build folders from real names.
 */
object AppListExport {

    /** The whole report, ready to paste or share. */
    suspend fun build(context: Context, repo: ModeRepository): String {
        val apps = AppList.all(context).sortedBy { it.label.lowercase() }
        val folders = repo.folderDao.getAll()
        val modes = repo.modeDao.getAll()
        val width = (apps.maxOfOrNull { it.label.length } ?: 0).coerceAtMost(38)

        return buildString {
            appendLine("# Modes app dump")
            appendLine("# ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            appendLine("# ${apps.size} launchable apps, ${folders.size} folders, ${modes.size} modes")
            appendLine()

            appendLine("## Installed apps")
            appendLine()
            apps.forEach { app ->
                appendLine(app.label.padEnd(width + 2) + app.packageName)
            }

            appendLine()
            appendLine("## Folders")
            folders.forEach { folder ->
                appendLine()
                appendLine("[${folder.name}]  (${folder.packages.size} apps)")
                folder.packages.forEach { pkg ->
                    val installed = if (AppList.isInstalled(context, pkg)) "" else "   NOT INSTALLED"
                    appendLine("    ${AppList.label(context, pkg).padEnd(width)} $pkg$installed")
                }
            }

            appendLine()
            appendLine("## Home screens")
            modes.forEach { mode ->
                appendLine()
                appendLine("### ${mode.name}  (${mode.id}, guard ${mode.guardScope})")
                val rows = resolveHomeRows(repo.homeDao.forMode(mode.id), folders)
                if (rows.isEmpty()) appendLine("    (empty)")
                rows.forEach { row ->
                    val mark = if (row.entry.enabled) "on " else "off"
                    if (row.isFolder) {
                        appendLine("    [$mark] [${row.name}]  ${row.folder?.packages?.size ?: 0} apps")
                    } else {
                        val pkg = row.entry.packageName.orEmpty()
                        appendLine("    [$mark] ${AppList.label(context, pkg)}  ($pkg)")
                    }
                }
            }
        }
    }

    /** Just the app table, for the adb script. */
    suspend fun buildAppsOnly(context: Context): String {
        val apps = AppList.all(context).sortedBy { it.label.lowercase() }
        val width = apps.maxOfOrNull { it.label.length } ?: 0
        return buildString {
            appendLine("# ${apps.size} launchable apps")
            appendLine()
            apps.forEach { appendLine(it.label.padEnd(width + 2) + it.packageName) }
        }
    }

    suspend fun buildJson(context: Context): String =
        AppList.all(context).sortedBy { it.label.lowercase() }
            .joinToString(",\n  ", prefix = "[\n  ", postfix = "\n]") { app ->
                """{"label": ${quote(app.label)}, "package": ${quote(app.packageName)}}"""
            }

    /**
     * Writes the report to this app's own files directory, where adb can read
     * it without root, and returns the file for sharing.
     */
    suspend fun writeFiles(context: Context, repo: ModeRepository): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val report = File(dir, "modes-dump.txt")
        report.writeText(build(context, repo))
        File(dir, "apps.txt").writeText(buildAppsOnly(context))
        File(dir, "apps.json").writeText(buildJson(context))
        return report
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
}
