package dev.jaronwilson.modes.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire format for remote control, kept free of Android types.
 *
 * All of it lives here rather than in the service because the app's tests are
 * plain JUnit on the JVM with no Robolectric: logic inside a Service cannot be
 * tested at all, and the parts most worth testing are exactly the ones a
 * mistake would quietly open up — token comparison and request parsing.
 */
object CommandProtocol {

    /** Requests are tiny; anything larger is a probe or a mistake, not us. */
    const val MAX_BODY_BYTES = 8 * 1024

    /** What the listener will act on. Anything else is refused by name. */
    val SUPPORTED = setOf(
        "ping", "open_app", "open_url", "list_apps", "install_app", "notify", "speak",
        // Screen control, backed by the accessibility guard.
        "read_screen", "screenshot", "foreground_app",
        "tap", "type_text", "swipe", "scroll", "press_key",
        // Hands-free messaging, backed by the notification listener.
        "recent_messages", "reply_message",
    )

    data class Request(
        val method: String,
        val path: String,
        val token: String?,
        val contentLength: Int,
    )

    /**
     * Parse the request line and the headers we care about.
     *
     * Returns null for anything malformed rather than throwing: the socket is
     * open to the tailnet, so garbage is expected and must not take the
     * listener down.
     */
    fun parseHead(lines: List<String>): Request? {
        if (lines.isEmpty()) return null
        val parts = lines[0].trim().split(" ")
        if (parts.size < 2) return null
        var token: String? = null
        var length = 0
        for (raw in lines.drop(1)) {
            val i = raw.indexOf(':')
            if (i <= 0) continue
            val name = raw.substring(0, i).trim().lowercase()
            val value = raw.substring(i + 1).trim()
            when (name) {
                "authorization" ->
                    if (value.startsWith("Bearer ", ignoreCase = true)) {
                        token = value.substring(7).trim()
                    }
                "content-length" -> length = value.toIntOrNull() ?: 0
            }
        }
        return Request(parts[0].uppercase(), parts[1], token, length)
    }

    /**
     * Compare tokens without leaking length or position through timing.
     *
     * A plain `==` on strings short-circuits at the first differing byte. That
     * is a real, if slow, oracle for something guarding "launch anything on my
     * phone", and the fix costs nothing.
     */
    fun tokenMatches(expected: String?, supplied: String?): Boolean {
        if (expected.isNullOrEmpty() || supplied.isNullOrEmpty()) return false
        return java.security.MessageDigest.isEqual(
            expected.toByteArray(), supplied.toByteArray())
    }

    /** Pull `command` and `params` out of a request body. */
    fun parseBody(body: String): Pair<String, JSONObject>? {
        return try {
            val o = JSONObject(if (body.isBlank()) "{}" else body)
            val command = o.optString("command").trim()
            if (command.isEmpty()) return null
            Pair(command, o.optJSONObject("params") ?: JSONObject())
        } catch (_: Exception) {
            null
        }
    }

    fun ok(payload: JSONObject): String = http(200, "OK", payload)

    fun error(code: Int, reason: String, message: String): String =
        http(code, reason, JSONObject().put("ok", false).put("error", message))

    /**
     * An HTTP/1.1 response with the connection closed straight after.
     *
     * Keep-alive would mean tracking idle sockets on a phone that sleeps; one
     * request per connection is slower and completely predictable.
     */
    private fun http(code: Int, reason: String, payload: JSONObject): String {
        val body = payload.toString()
        val bytes = body.toByteArray().size
        return buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ").append(bytes).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
            append(body)
        }
    }

    fun appsJson(apps: List<Pair<String, String>>): JSONObject {
        val arr = JSONArray()
        for ((pkg, label) in apps) {
            arr.put(JSONObject().put("package", pkg).put("label", label))
        }
        return JSONObject().put("ok", true).put("count", arr.length()).put("apps", arr)
    }
}
