package dev.jaronwilson.modes.remote

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.ModesApp
import dev.jaronwilson.modes.R
import dev.jaronwilson.modes.guard.AppGuardService
import dev.jaronwilson.modes.launcher.AppList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * A small HTTP listener so Odysseus can ask this phone to do things.
 *
 * Why a listener and not a push: a push wakes the *browser*, which cannot
 * launch another app. Something has to be running here to act, and this is the
 * least of it — no third-party automation app, and no wireless debugging left
 * switched on for every network the phone joins.
 *
 * Why foreground: Android kills background services quickly and will not let a
 * background process start an activity at all, which is the one thing this
 * exists to do. The ongoing notification is also the honest signal that the
 * phone is listening, and the only way to notice it is on.
 *
 * Reachability is Tailscale's job, not this service's. It binds every
 * interface because the phone's tailnet address appears and disappears as the
 * VPN comes and goes, and re-binding on each change would be a lot of
 * machinery to get wrong. The token is what makes that safe: without it every
 * request is refused, and it is minted by Odysseus rather than chosen here.
 */
class RemoteControlService : Service() {

    private var server: ServerSocket? = null
    private var token: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppGraph.ensure(this)
        startForeground(NOTIFICATION_ID, buildNotification(), foregroundType())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server != null) return START_STICKY

        token = runBlocking { AppGraph.repo.settings.remoteToken() }
        if (token.isBlank()) {
            // Refusing to listen without a token is the whole safety model. An
            // open port here would let anything on the network launch apps.
            Log.w(TAG, "No remote token set; not listening. Set one in Settings.")
            stopSelf()
            return START_NOT_STICKY
        }

        AppGraph.scope.launch(Dispatchers.IO) { serve() }
        return START_STICKY
    }

    private fun serve() {
        val s = try {
            ServerSocket(PORT)
        } catch (e: Exception) {
            Log.e(TAG, "Could not bind port $PORT: ${e.message}")
            stopSelf()
            return
        }
        server = s
        Log.i(TAG, "listening on $PORT")
        while (!s.isClosed) {
            val client = try {
                s.accept()
            } catch (_: Exception) {
                break   // closed during shutdown
            }
            try {
                handle(client)
            } catch (e: Exception) {
                Log.w(TAG, "request failed: ${e.message}")
            } finally {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5000
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val head = ArrayList<String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            head.add(line)
            if (head.size > 40) break   // header flood
        }
        val out = client.getOutputStream()

        fun respond(text: String) {
            out.write(text.toByteArray())
            out.flush()
        }

        val req = CommandProtocol.parseHead(head)
        if (req == null) {
            respond(CommandProtocol.error(400, "Bad Request", "could not parse request"))
            return
        }
        if (!CommandProtocol.tokenMatches(token, req.token)) {
            // Deliberately vague to the caller, specific in the log: a probe
            // should learn nothing, but you should be able to see it happened.
            Log.w(TAG, "rejected request from ${client.inetAddress}: bad or missing token")
            respond(CommandProtocol.error(401, "Unauthorized", "bad or missing token"))
            return
        }
        if (req.method != "POST") {
            respond(CommandProtocol.error(405, "Method Not Allowed", "use POST /command"))
            return
        }
        if (req.contentLength > CommandProtocol.MAX_BODY_BYTES) {
            respond(CommandProtocol.error(413, "Payload Too Large", "body too large"))
            return
        }

        val buf = CharArray(req.contentLength.coerceAtLeast(0))
        if (buf.isNotEmpty()) reader.read(buf)
        val parsed = CommandProtocol.parseBody(String(buf))
        if (parsed == null) {
            respond(CommandProtocol.error(400, "Bad Request",
                """body must be {"command": "...", "params": {...}}"""))
            return
        }
        val (command, params) = parsed
        respond(dispatch(command, params))
    }

    private fun dispatch(command: String, params: JSONObject): String {
        if (command !in CommandProtocol.SUPPORTED) {
            return CommandProtocol.error(400, "Bad Request",
                "unknown command '$command'. Supported: " +
                    CommandProtocol.SUPPORTED.sorted().joinToString(", "))
        }
        return when (command) {
            "ping" -> CommandProtocol.ok(
                JSONObject().put("ok", true).put("app", "modes")
                    .put("version", dev.jaronwilson.modes.BuildConfig.VERSION_NAME))

            "list_apps" -> CommandProtocol.ok(
                CommandProtocol.appsJson(
                    AppList.installed(this).map { it.packageName to it.label }))

            "open_app" -> {
                val pkg = params.optString("package").trim()
                when {
                    pkg.isEmpty() ->
                        CommandProtocol.error(400, "Bad Request", "params.package is required")
                    !AppList.isInstalled(this, pkg) ->
                        // Say which, so the caller can offer to install rather
                        // than reporting a generic failure.
                        CommandProtocol.error(404, "Not Found",
                            "$pkg is not installed. Use install_app to open its store page.")
                    !AppList.isOpenable(this, pkg) ->
                        CommandProtocol.error(409, "Conflict",
                            "$pkg is installed but has no launchable screen.")
                    else -> {
                        AppList.launch(this, pkg)
                        CommandProtocol.ok(JSONObject().put("ok", true).put("launched", pkg))
                    }
                }
            }

            "install_app" -> {
                val pkg = params.optString("package").trim()
                if (pkg.isEmpty()) {
                    CommandProtocol.error(400, "Bad Request", "params.package is required")
                } else if (AppList.isInstalled(this, pkg)) {
                    CommandProtocol.ok(JSONObject().put("ok", true)
                        .put("already_installed", pkg))
                } else {
                    // Android will not install silently for good reason, so
                    // this opens the store page and the tap is the user's.
                    // Saying so in the response stops the model claiming the
                    // app is installed when it is merely offered.
                    view("market://details?id=$pkg")
                    CommandProtocol.ok(JSONObject().put("ok", true).put("opened_store_for", pkg)
                        .put("note", "The Play Store page is open. Installing still needs a tap "
                            + "on the phone; nothing can install an app without one."))
                }
            }

            "open_url" -> {
                val url = params.optString("url").trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    CommandProtocol.error(400, "Bad Request", "params.url must be http(s)")
                } else {
                    view(url)
                    CommandProtocol.ok(JSONObject().put("ok", true).put("opened", url))
                }
            }

            "notify", "speak" -> {
                val text = params.optString("text").ifBlank { params.optString("message") }
                // Optional: a link opened when the notification is tapped (the
                // chat a finished reply is in), and a title. A notification
                // with no tap action did nothing when tapped.
                val url = params.optString("url")
                val link = url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                val title = params.optString("title").ifBlank { "Modes" }
                if (text.isBlank()) {
                    CommandProtocol.error(400, "Bad Request", "params.text is required")
                } else if (url.isNotBlank() && link == null) {
                    CommandProtocol.error(400, "Bad Request", "params.url must be http(s)")
                } else {
                    notify(text, title, link)
                    val out = JSONObject().put("ok", true).put("shown", text)
                    if (link != null) out.put("opens", link)
                    CommandProtocol.ok(out)
                }
            }

            "foreground_app", "read_screen", "screenshot",
            "tap", "type_text", "swipe", "scroll", "press_key" ->
                screenCommand(command, params)

            else -> CommandProtocol.error(500, "Internal Server Error", "unhandled: $command")
        }
    }

    /**
     * The screen-control commands, all of which need the accessibility guard to
     * be running. Kept together because they share that one precondition and the
     * same failure when it is off.
     */
    private fun screenCommand(command: String, params: JSONObject): String {
        val svc = AppGuardService.instance
            ?: return CommandProtocol.error(409, "Conflict",
                "the Modes app guard (accessibility) is off; turn it on to read or " +
                    "control the screen")
        return when (command) {
            "foreground_app" -> CommandProtocol.ok(
                JSONObject().put("ok", true).put("package", UiControl.foreground(svc)))

            "read_screen" -> CommandProtocol.ok(
                JSONObject().put("ok", true)
                    .put("package", UiControl.foreground(svc))
                    .put("elements", UiControl.dump(svc)))

            "screenshot" -> {
                val b64 = UiControl.screenshotBase64(svc)
                if (b64 == null) {
                    CommandProtocol.error(500, "Internal Server Error",
                        "screenshot failed; the screen may be off or protected")
                } else {
                    CommandProtocol.ok(JSONObject().put("ok", true)
                        .put("format", "png").put("base64", b64))
                }
            }

            "tap" -> {
                val text = params.optString("text").trim()
                val done = if (text.isNotEmpty()) {
                    UiControl.tapText(svc, text)
                } else if (params.has("x") && params.has("y")) {
                    UiControl.tap(svc, params.optInt("x"), params.optInt("y"))
                } else {
                    return CommandProtocol.error(400, "Bad Request",
                        "tap needs params.text, or params.x and params.y")
                }
                actionResult(done, if (text.isNotEmpty()) "no match for '$text' on screen"
                    else "tap not dispatched")
            }

            "type_text" -> {
                val text = params.optString("text")
                if (text.isEmpty()) {
                    CommandProtocol.error(400, "Bad Request", "params.text is required")
                } else {
                    actionResult(UiControl.typeText(svc, text), "no editable field is focused")
                }
            }

            "swipe" -> {
                val keys = listOf("x1", "y1", "x2", "y2")
                if (!keys.all { params.has(it) }) {
                    CommandProtocol.error(400, "Bad Request", "swipe needs x1, y1, x2, y2")
                } else {
                    actionResult(
                        UiControl.swipe(svc, params.optInt("x1"), params.optInt("y1"),
                            params.optInt("x2"), params.optInt("y2"),
                            params.optInt("duration_ms", 300)),
                        "swipe not dispatched")
                }
            }

            "scroll" -> {
                val dir = params.optString("direction").trim()
                if (dir.isEmpty()) {
                    CommandProtocol.error(400, "Bad Request",
                        "params.direction must be up, down, left or right")
                } else {
                    actionResult(
                        UiControl.scroll(svc, dir, params.optDouble("amount", 0.6).toFloat()),
                        "scroll not dispatched; check direction")
                }
            }

            "press_key" -> {
                val name = params.optString("key").trim()
                if (name.isEmpty()) {
                    CommandProtocol.error(400, "Bad Request",
                        "params.key must be back, home or recents")
                } else {
                    actionResult(UiControl.key(svc, name), "unknown key '$name'")
                }
            }

            else -> CommandProtocol.error(500, "Internal Server Error", "unhandled: $command")
        }
    }

    private fun actionResult(done: Boolean, failMessage: String): String =
        if (done) CommandProtocol.ok(JSONObject().put("ok", true))
        else CommandProtocol.error(422, "Unprocessable Entity", failMessage)

    private fun view(uri: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun notify(text: String, title: String = "Modes", link: String? = null) {
        val id = (title + text).hashCode()
        val b = Notification.Builder(this, ModesApp.CH_REMOTE)
            .setSmallIcon(R.drawable.ic_stat_modes)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        if (link != null) {
            val open = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            b.setContentIntent(PendingIntent.getActivity(
                this, id, open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        getSystemService(android.app.NotificationManager::class.java).notify(id, b.build())
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, ModesApp.CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_modes)
            .setContentTitle("Remote control on")
            .setContentText("Listening on port $PORT for your own server.")
            .setOngoing(true)
            .build()

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    override fun onDestroy() {
        runCatching { server?.close() }
        server = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RemoteControl"
        const val PORT = 8778
        private const val NOTIFICATION_ID = 8778

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, RemoteControlService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteControlService::class.java))
        }
    }
}
