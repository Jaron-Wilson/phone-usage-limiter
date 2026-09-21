package dev.jaronwilson.modes

import dev.jaronwilson.modes.remote.CommandProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The listener is reachable by anything that can route to the phone, so the
 * parts that decide "is this you" and "what did you ask for" are the parts
 * worth pinning. They live outside the Service precisely so they can be.
 */
class CommandProtocolTest {

    private fun head(vararg lines: String) = CommandProtocol.parseHead(lines.toList())

    @Test
    fun `reads method path and bearer token`() {
        val r = head(
            "POST /command HTTP/1.1",
            "Host: phone:8778",
            "Authorization: Bearer abc123",
            "Content-Length: 42",
        )!!
        assertEquals("POST", r.method)
        assertEquals("/command", r.path)
        assertEquals("abc123", r.token)
        assertEquals(42, r.contentLength)
    }

    @Test
    fun `header names are case insensitive`() {
        val r = head("POST /command HTTP/1.1", "AUTHORIZATION: bearer tok", "content-length: 7")!!
        assertEquals("tok", r.token)
        assertEquals(7, r.contentLength)
    }

    @Test
    fun `garbage does not blow up`() {
        // The port is open to the network; malformed input is routine and must
        // never take the listener down.
        assertNull(head(""))
        assertNull(head("GET"))
        assertNull(CommandProtocol.parseHead(emptyList()))
        assertEquals(0, head("POST / HTTP/1.1", "Content-Length: not-a-number")!!.contentLength)
        assertNull(head("POST / HTTP/1.1", "Authorization: Basic abc")!!.token)
        assertNull(head("POST / HTTP/1.1", ": novalue")!!.token)
    }

    @Test
    fun `token must match exactly`() {
        assertTrue(CommandProtocol.tokenMatches("s3cret", "s3cret"))
        assertFalse(CommandProtocol.tokenMatches("s3cret", "s3cre"))
        assertFalse(CommandProtocol.tokenMatches("s3cret", "s3crets"))
        assertFalse(CommandProtocol.tokenMatches("s3cret", "S3CRET"))
        assertFalse(CommandProtocol.tokenMatches("s3cret", "wrong"))
    }

    @Test
    fun `an unset token can never be satisfied`() {
        // Otherwise a fresh install with no token configured would accept
        // everything, which is the worst possible default for this feature.
        assertFalse(CommandProtocol.tokenMatches("", "anything"))
        assertFalse(CommandProtocol.tokenMatches(null, "anything"))
        assertFalse(CommandProtocol.tokenMatches("", ""))
        assertFalse(CommandProtocol.tokenMatches("real", null))
        assertFalse(CommandProtocol.tokenMatches("real", ""))
    }

    @Test
    fun `body yields command and params`() {
        val (cmd, params) = CommandProtocol.parseBody(
            """{"command":"open_app","params":{"package":"com.bambulab.bambuhandy"}}""")!!
        assertEquals("open_app", cmd)
        assertEquals("com.bambulab.bambuhandy", params.optString("package"))
    }

    @Test
    fun `body without params is still valid`() {
        val (cmd, params) = CommandProtocol.parseBody("""{"command":"ping"}""")!!
        assertEquals("ping", cmd)
        assertEquals(0, params.length())
    }

    @Test
    fun `body must name a command`() {
        assertNull(CommandProtocol.parseBody(""))
        assertNull(CommandProtocol.parseBody("{}"))
        assertNull(CommandProtocol.parseBody("""{"command":"  "}"""))
        assertNull(CommandProtocol.parseBody("not json at all"))
        assertNull(CommandProtocol.parseBody("""{"command":"x" """))
    }

    @Test
    fun `responses carry an accurate content length`() {
        val res = CommandProtocol.ok(org.json.JSONObject().put("ok", true))
        val body = res.substringAfter("\r\n\r\n")
        val declared = Regex("Content-Length: (\\d+)").find(res)!!.groupValues[1].toInt()
        assertEquals(body.toByteArray().size, declared)
        assertTrue(res.startsWith("HTTP/1.1 200 OK"))
        assertTrue(res.contains("Connection: close"))
    }

    @Test
    fun `error responses keep the json shape`() {
        val res = CommandProtocol.error(401, "Unauthorized", "bad or missing token")
        assertTrue(res.startsWith("HTTP/1.1 401 Unauthorized"))
        val body = org.json.JSONObject(res.substringAfter("\r\n\r\n"))
        assertFalse(body.getBoolean("ok"))
        assertEquals("bad or missing token", body.getString("error"))
    }

    @Test
    fun `only known commands are advertised`() {
        assertTrue("open_app" in CommandProtocol.SUPPORTED)
        assertTrue("install_app" in CommandProtocol.SUPPORTED)
        // Nothing that would need silent install rights or credential entry.
        assertFalse("sign_in" in CommandProtocol.SUPPORTED)
        assertFalse("shell" in CommandProtocol.SUPPORTED)
    }

    @Test
    fun `app list serialises as package and label pairs`() {
        val json = CommandProtocol.appsJson(
            listOf("com.a" to "Alpha", "com.b" to "Beta"))
        assertEquals(2, json.getInt("count"))
        assertEquals("com.a", json.getJSONArray("apps").getJSONObject(0).getString("package"))
        assertEquals("Beta", json.getJSONArray("apps").getJSONObject(1).getString("label"))
    }
}
