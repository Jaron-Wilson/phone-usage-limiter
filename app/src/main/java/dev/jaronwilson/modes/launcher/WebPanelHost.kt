package dev.jaronwilson.modes.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Keeps one web view alive between openings.
 *
 * Composing the panel created the view and closing it threw the view away, so
 * every swipe reloaded the page and lost where you were. A dashboard you check
 * several times an hour should be where you left it, not starting again.
 *
 * One view per address, made the first time that address is opened and kept
 * until the launcher itself goes away. It is detached from the panel when the
 * panel closes, which stops it drawing without stopping it existing.
 */
class WebPanelHost {

    private var url: String? = null
    private var view: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun viewFor(
        context: Context,
        address: String,
        onExternalLink: (String) -> Unit
    ): WebView {
        view?.let { existing ->
            if (url == address) {
                // Detach from wherever it was so it can be added again.
                (existing.parent as? ViewGroup)?.removeView(existing)
                return existing
            }
            // A different address: the old page is of no further use.
            existing.destroy()
        }

        val created = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Nothing local is reachable from a page an edge panel loaded.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            settings.builtInZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val target = request?.url?.toString() ?: return false
                    val sameSite = runCatching {
                        android.net.Uri.parse(target).host == android.net.Uri.parse(address).host
                    }.getOrDefault(false)
                    if (sameSite) return false
                    // Anywhere else is the browser's job.
                    onExternalLink(target)
                    return true
                }
            }
            loadUrl(address)
        }
        url = address
        view = created
        return created
    }

    /** Back through the site's own history, if it has any. */
    fun goBack(): Boolean {
        val v = view ?: return false
        if (!v.canGoBack()) return false
        v.goBack()
        return true
    }

    /** Called when the launcher goes away, which for a home screen is rarely. */
    fun release() {
        view?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        view = null
        url = null
    }
}
