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

    /** Whether the phone will let Modes ask to install; if false, send the user to [unknownSourcesSettings]. */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettings(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    /** The installed versionName of [pkg], or null if it is not installed. */
    fun installedVersion(context: Context, pkg: String): String? = runCatching {
        context.packageManager.getPackageInfo(pkg, 0).versionName
    }.getOrNull()

    /**
     * Fetch [url] and stage its APK(s) on disk, ready to install. Kept separate
     * from [install] on purpose: a big download blows past the ~10s window in
     * which Android will let an app raise the install prompt, so the prompt has
     * to fire from a fresh tap once the bytes are already here.
     */
    suspend fun download(
        context: Context,
        url: String,
        onProgress: (Float) -> Unit
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        runCatching {
            // filesDir, not cacheDir: the download plus unpacked splits are
            // hundreds of megabytes and the cache quota is tiny, so cacheDir
            // gets purged out from under the install.
            val work = File(context.filesDir, "limited-install").apply {
                deleteRecursively(); mkdirs()
            }
            val payload = File(work, "payload")
            fetch(url, payload, onProgress)
            val apks = if (isZip(payload)) {
                val extracted = unzipApks(payload, work)
                payload.delete()   // reclaim the zip before staging the session
                extracted
            } else {
                listOf(payload)
            }
            require(apks.isNotEmpty()) { "no APK found in the download" }
            apks
        }
    }

    private fun fetch(url: String, into: File, onProgress: (Float) -> Unit) {
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
                        if (total > 0) onProgress(done.toFloat() / total)
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

    /**
     * Commit [apks] (from [download]) to a single install session. Call this on
     * a fresh user tap so the confirm prompt is inside Android's launch window.
     * The staged files are copied into the session and then deleted.
     */
    suspend fun install(context: Context, apks: List<File>): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
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
                    // The status callback must launch the system's confirm
                    // screen. A broadcast receiver cannot start an activity on
                    // Android 14+, so target a translucent trampoline activity.
                    val intent = Intent(context, InstallLauncherActivity::class.java)
                        .setAction(STATUS_ACTION)
                    val pending = PendingIntent.getActivity(
                        context, sessionId, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    )
                    session.commit(pending.intentSender)
                }
                // The session has its own copy now; free the staging space.
                File(context.filesDir, "limited-install").deleteRecursively()
                Unit
            }
        }
}
