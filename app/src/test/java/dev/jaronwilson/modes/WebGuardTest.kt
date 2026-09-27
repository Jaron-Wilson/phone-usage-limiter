package dev.jaronwilson.modes

import dev.jaronwilson.modes.guard.WebGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebGuardTest {

    @Test
    fun `a plain domain maps to its app`() {
        assertEquals("com.instagram.android", WebGuard.appForHost("instagram.com"))
        assertEquals("com.google.android.youtube", WebGuard.appForHost("youtube.com"))
    }

    @Test
    fun `www and subdomains still map`() {
        assertEquals("com.instagram.android", WebGuard.appForHost("www.instagram.com"))
        assertEquals("com.google.android.youtube", WebGuard.appForHost("m.youtube.com"))
    }

    @Test
    fun `an unrelated host maps to nothing`() {
        assertNull(WebGuard.appForHost("example.com"))
        assertNull(WebGuard.appForHost("notinstagram.com"))
    }

    @Test
    fun `hostOf strips scheme, path and query`() {
        assertEquals("instagram.com", WebGuard.hostOf("https://instagram.com/reels/"))
        assertEquals("instagram.com", WebGuard.hostOf("instagram.com/x?y=1#z"))
        assertEquals("www.instagram.com", WebGuard.hostOf("http://www.instagram.com"))
    }

    @Test
    fun `hostOf ignores a search box`() {
        assertNull(WebGuard.hostOf("how to stop doom scrolling"))
        assertNull(WebGuard.hostOf(""))
    }

    @Test
    fun `hostOf needs a dotted host`() {
        assertNull(WebGuard.hostOf("localhost"))
    }
}
