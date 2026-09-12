package dev.jaronwilson.modes.launcher

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.jaronwilson.modes.ui.PickerPalette
import dev.jaronwilson.modes.ui.theme.Brand
import dev.jaronwilson.modes.ui.tileColors

/**
 * A site, on the edge of the home screen.
 *
 * Swiping in from a side brings it over the home screen rather than handing
 * you to a browser, because the point of putting a dashboard on an edge is to
 * read one number and put the phone down, and a browser tab is a thing you
 * then have to leave.
 *
 * The web view is deliberately plain: no file access, no content access, no
 * downloads, and every link that leaves the site you configured is handed to
 * the real browser. An edge panel is a window onto one page, not a way to end
 * up somewhere else inside a launcher.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EdgeWebPanel(
    site: EdgeTarget.Site?,
    edge: Edge,
    onDismiss: () -> Unit,
    onOpenInBrowser: (String) -> Unit
) {
    val colors = tileColors(PickerPalette.LAUNCHER)

    AnimatedVisibility(
        visible = site != null,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(120))
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = if (edge == Edge.LEFT) Alignment.CenterStart else Alignment.CenterEnd
        ) {
            AnimatedVisibility(
                visible = site != null,
                enter = slideInHorizontally(tween(200)) { w -> if (edge == Edge.LEFT) -w else w },
                exit = slideOutHorizontally(tween(150)) { w -> if (edge == Edge.LEFT) -w else w }
            ) {
                val s = site ?: return@AnimatedVisibility
                Column(
                    Modifier
                        .fillMaxWidth(0.92f)
                        .fillMaxHeight(0.86f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Brand.Dark.surface)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            s.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.ink,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "open",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.accent,
                            modifier = Modifier
                                .clickable { onOpenInBrowser(s.url) }
                                .padding(8.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "close",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.faint,
                            modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp)
                        )
                    }
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
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
