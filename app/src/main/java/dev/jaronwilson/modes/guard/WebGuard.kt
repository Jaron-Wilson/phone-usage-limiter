package dev.jaronwilson.modes.guard

import android.view.accessibility.AccessibilityNodeInfo

/**
 * The website side of the app guard.
 *
 * Setting an app aside is easy to slip past by opening the same thing in a
 * browser, so when a mode stops an app it stops that app's website too. This is
 * the one place the guard reads more than a package name: for a known browser it
 * reads the address bar, works out which host is on screen, and maps it back to
 * the app that owns it. It reads nothing for any app that is not a browser.
 *
 * The address bar is found by each browser's own view id, so no page content is
 * touched. If a browser is not in [URL_BAR_IDS] its web traffic is not guarded.
 */
object WebGuard {

    /** Which app a hostname belongs to. Add a pair to cover another site. */
    private val DOMAINS: Map<String, List<String>> = mapOf(
        "com.instagram.android" to listOf("instagram.com"),
        "com.google.android.youtube" to listOf("youtube.com", "youtu.be")
    )

    /** Browser package -> the resource-id name of its address bar. */
    private val URL_BAR_IDS: Map<String, String> = mapOf(
        "com.android.chrome" to "url_bar",
        "com.chrome.beta" to "url_bar",
        "com.chrome.dev" to "url_bar",
        "com.chrome.canary" to "url_bar",
        "com.brave.browser" to "url_bar",
        "com.microsoft.emmx" to "url_bar",
        "com.vivaldi.browser" to "url_bar",
        "com.kiwibrowser.browser" to "url_bar",
        "com.opera.browser" to "url_field",
        "com.opera.mini.native" to "url_field",
        "com.duckduckgo.mobile.android" to "omnibarTextInput",
        "org.mozilla.firefox" to "mozac_browser_toolbar_url_view",
        "org.mozilla.focus" to "mozac_browser_toolbar_url_view",
        "com.sec.android.app.sbrowser" to "location_bar_edit_text"
    )

    fun isBrowser(pkg: String): Boolean = pkg in URL_BAR_IDS

    /** The app that owns [host], or null if no rule covers it. */
    fun appForHost(host: String): String? {
        val h = host.removePrefix("www.")
        return DOMAINS.entries.firstOrNull { (_, domains) ->
            domains.any { h == it || h.endsWith(".$it") }
        }?.key
    }

    /**
     * The host shown in [browserPkg]'s address bar, or null if it cannot be read
     * or is not a URL (a search box, say). Recycles every node it touches.
     */
    fun readHost(root: AccessibilityNodeInfo, browserPkg: String): String? {
        val idName = URL_BAR_IDS[browserPkg] ?: return null
        val nodes = runCatching {
            root.findAccessibilityNodeInfosByViewId("$browserPkg:id/$idName")
        }.getOrNull().orEmpty()
        try {
            val text = nodes.firstNotNullOfOrNull { it.text?.toString()?.trim()?.ifEmpty { null } }
                ?: return null
            return hostOf(text)
        } finally {
            nodes.forEach { runCatching { it.recycle() } }
        }
    }

    /** Pull a bare hostname out of whatever the address bar is showing. */
    fun hostOf(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty() || s.contains(' ')) return null // a search query, not a URL
        s = s.substringAfter("://", s)
        s = s.substringBefore('/')
        s = s.substringBefore('?').substringBefore('#')
        s = s.substringBefore(':') // drop any port
        if (s.isEmpty() || '.' !in s) return null
        return s.lowercase()
    }
}
