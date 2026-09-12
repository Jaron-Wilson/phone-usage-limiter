package dev.jaronwilson.modes.launcher

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.jaronwilson.modes.ui.theme.Brand

/**
 * A site, held against the side of the screen.
 *
 * It takes the whole screen. Earlier versions floated it as a card, first with
 * its own title bar and then held against one side, and both read as a browser
 * window that had been made small: black showing round the edges, the page
 * squeezed, a sliver of home screen doing nothing useful.
 *
 * A site you put on an edge is a site you want to read. So it slides in from
 * its edge and then it is simply the screen, with one thin strip along the
 * bottom for the way out, at thumb height rather than at the top of something
 * this tall.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EdgeWebPanel(
    site: EdgeTarget.Site?,
    edge: Edge,
    onDismiss: () -> Unit,
    onOpenInBrowser: (String) -> Unit
) {
    val context = LocalContext.current
    var webView by remember(site) { mutableStateOf<WebView?>(null) }

    // Back goes back through the site's own history first, and only leaves
    // once there is nowhere left to go.
    BackHandler(enabled = site != null) {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onDismiss()
    }

    AnimatedVisibility(
        visible = site != null,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120))
    ) {
        Box(Modifier.fillMaxSize().background(Brand.Dark.paper)) {
            AnimatedVisibility(
                visible = site != null,
                enter = slideInHorizontally(tween(220)) { w -> if (edge == Edge.LEFT) -w else w },
                exit = slideOutHorizontally(tween(160)) { w -> if (edge == Edge.LEFT) -w else w }
            ) {
                val s = site ?: return@AnimatedVisibility

                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Brand.Dark.paper)
                        // Shove it back towards its own edge to dismiss, the
                        // way it arrived.
                        .pointerInput(edge) {
                            var travelled = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { travelled = 0f },
                                onHorizontalDrag = { change, delta ->
                                    travelled += delta
                                    change.consume()
                                },
                                onDragEnd = {
                                    val away = if (edge == Edge.LEFT) -travelled else travelled
                                    if (away > size.width * 0.25f) onDismiss()
                                }
                            )
                        }
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                // Nothing local is reachable from a page an
                                // edge panel loaded.
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
                                            android.net.Uri.parse(target).host ==
                                                android.net.Uri.parse(s.url).host
                                        }.getOrDefault(false)
                                        if (sameSite) return false
                                        // Anywhere else is the browser's job.
                                        onOpenInBrowser(target)
                                        return true
                                    }
                                }
                                loadUrl(s.url)
                                webView = this
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )

                    // The only chrome, at the bottom, where the thumb is.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .background(Brand.Dark.surface)
                            .padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            s.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = Brand.Dark.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "browser",
                            style = MaterialTheme.typography.labelSmall,
                            color = Brand.Dark.accent,
                            modifier = Modifier
                                .clickable { onOpenInBrowser(s.url) }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                        Text(
                            "close",
                            style = MaterialTheme.typography.labelSmall,
                            color = Brand.Dark.muted,
                            modifier = Modifier
                                .clickable(onClick = onDismiss)
                                .padding(start = 10.dp, top = 8.dp, bottom = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
