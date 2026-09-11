package dev.jaronwilson.modes.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.jaronwilson.modes.core.repo.ModeRepository

/** Getting the dump off the phone, with or without a computer. */
object ShareDump {

    suspend fun copyToClipboard(context: Context, repo: ModeRepository): Int {
        val text = AppListExport.build(context, repo)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Modes app dump", text))
        return text.length
    }

    /**
     * Shares the dump as a file where possible, falling back to plain text.
     * A file survives long lists: some share targets quietly truncate a large
     * EXTRA_TEXT, and this report runs to a few hundred lines on a full phone.
     */
    suspend fun share(context: Context, repo: ModeRepository) {
        val file = AppListExport.writeFiles(context, repo)
        val intent = runCatching {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.files", file
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Modes app dump")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }.getOrElse {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, file.readText())
                putExtra(Intent.EXTRA_SUBJECT, "Modes app dump")
            }
        }

        context.startActivity(
            Intent.createChooser(intent, "Send app list")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
