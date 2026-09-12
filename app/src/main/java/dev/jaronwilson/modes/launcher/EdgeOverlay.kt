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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.jaronwilson.modes.ui.FullBleedDialogWindow
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
    host: WebPanelHost,
    onDismiss: () -> Unit,
    onOpenInBrowser: (String) -> Unit
) {
    val context = LocalContext.current

    // Back goes back through the site's own history first, and only leaves
    // once there is nowhere left to go.
    BackHandler(enabled = site != null) {
        if (!host.goBack()) onDismiss()
    }

    if (site == null) return

    // Its own window. A composable drawn inside the home screen inherits the
    // home screen's gutter and insets, which is how this ended up with a black
    // border on every side however many times the padding was moved. A popup
    // is measured against the display instead, so full screen means it.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false
        )
    ) {
        FullBleedDialogWindow()
        Box(Modifier.fillMaxSize().background(Brand.Dark.paper)) {
            AnimatedVisibility(
                visible = true,
                enter = slideInHorizontally(tween(220)) { w -> if (edge == Edge.LEFT) -w else w }
            ) {
                val s = site

                Box(
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
                ) {
                    // Edge to edge, under the status bar and behind the
                    // navigation bar. The page gets the glass, all of it.
                    AndroidView(
                        // The same view every time, so the page is where you
                        // left it rather than loading again.
                        factory = { ctx -> host.viewFor(ctx, s.url, onOpenInBrowser) },
                        modifier = Modifier.fillMaxSize()
                    )

                    // No chrome at all. Anything floated over the page sits
                    // on whatever the site put underneath it, and a site's own
                    // controls have a better claim to that corner than a close
                    // button does. Back closes it, or shove it back towards the
                    // edge it came from.
                }
            }
        }
    }
}
