package dev.jaronwilson.modes.remote

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads an app from a URL you control and installs it.
 *
 * Modes ships no app of its own to sideload; this only fetches from a link you
 * set (your private store of a limited Instagram, say) to your own phone. It
 * copes with a plain .apk and with a .zip of split APKs, which is how a patched
 * Instagram comes out, installing every split in one session so the parts never
 * get out of step. Android still shows its own install prompt: nothing here
 * installs without your tap, which is the point.
 */
object ApkInstaller {

    const val STATUS_ACTION = "dev.jaronwilson.modes.INSTALL_STATUS"

    sealed interface Progress {
        data class Downloading(val fraction: Float) : Progress
        data object Installing : Progress
    }

    /** Whether the phone will let Modes ask to install; if false, send the user to [unknownSourcesSettings]. */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettings(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    /**
     * Fetch [url] and hand it to the system installer. Returns a human-readable
     * message; the actual install confirmation is shown by Android afterwards.
     */
    suspend fun downloadAndInstall(
        context: Context,
        url: String,
        onProgress: (Progress) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val work = File(context.cacheDir, "limited-install").apply {
                deleteRecursively(); mkdirs()
            }
            val download = File(work, "payload")
            fetch(url, download, onProgress)

            onProgress(Progress.Installing)
            val apks = if (isZip(download)) unzipApks(download, work) else listOf(download)
            require(apks.isNotEmpty()) { "no APK found in the download" }
            install(context, apks)
            "Downloaded ${apks.size} file(s); confirm the install when Android asks."
        }
    }

    private fun fetch(url: String, into: File, onProgress: (Progress) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("server returned ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                into.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress(Progress.Downloading(done.toFloat() / total))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun isZip(file: File): Boolean = file.inputStream().use {
        val a = it.read(); val b = it.read()
        a == 'P'.code && b == 'K'.code
    }

    private fun unzipApks(zip: File, dir: File): List<File> {
        val out = mutableListOf<File>()
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = File(entry.name).name  // flatten any path
                if (!entry.isDirectory && name.endsWith(".apk", ignoreCase = true)) {
                    val target = File(dir, name)
                    target.outputStream().use { zis.copyTo(it) }
                    out.add(target)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return out
    }

    private fun install(context: Context, apks: List<File>) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            for (apk in apks) {
                session.openWrite(apk.name, 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
            }
            val intent = Intent(STATUS_ACTION).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pending.intentSender)
        }
    }
}
