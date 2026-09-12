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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.jaronwilson.modes.ui.theme.Brand
import kotlin.math.roundToInt

/**
 * A site, held against the side of the screen.
 *
 * The first version of this was a floating card with its own title bar, which
 * put a heading directly above the site's own heading and left black gaps at
 * top and bottom. It read as a browser window someone had shrunk.
 *
 * This one behaves like the thing it is: a panel attached to an edge. Full
 * height, square against the side it came from and rounded on the inner one,
 * the page filling all of it. The only chrome is a thin strip along the
 * bottom, where a thumb already is, rather than at the top of a panel tall
 * enough that the top is out of reach.
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
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = if (edge == Edge.LEFT) Alignment.CenterStart else Alignment.CenterEnd
        ) {
            AnimatedVisibility(
                visible = site != null,
                enter = slideInHorizontally(tween(220)) { w -> if (edge == Edge.LEFT) -w else w },
                exit = slideOutHorizontally(tween(160)) { w -> if (edge == Edge.LEFT) -w else w }
            ) {
                val s = site ?: return@AnimatedVisibility
                // Square where it meets the screen edge, rounded on the side
                // that faces in: the shape says which edge it belongs to.
                val shape = if (edge == Edge.LEFT) {
                    RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp)
                } else {
                    RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp)
                }

                Column(
                    Modifier
                        .fillMaxWidth(0.93f)
                        .fillMaxHeight()
                        .clip(shape)
                        .background(Brand.Dark.paper)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
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
