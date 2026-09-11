package dev.jaronwilson.modes.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/**
 * A small app icon for pickers. Icons belong on the editing side, where you
 * are choosing between forty apps and a picture is faster than a name. They
 * stay off the home screen on purpose.
 */
@Composable
fun AppIcon(drawable: Drawable?, size: Dp = 18.dp) {
    val bitmap = remember(drawable) {
        runCatching { drawable?.toBitmap(64, 64)?.asImageBitmap() }.getOrNull()
    } ?: return
    Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
}
