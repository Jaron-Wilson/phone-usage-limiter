package dev.jaronwilson.modes.ui

import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

/**
 * A small app icon for pickers and tiles.
 *
 * Each drawable is rasterised once, at one size, and the bitmap is kept. The
 * package manager hands out one Drawable per app and it carries state, so
 * drawing the same instance at several sizes from several composables, each
 * setting its bounds, is a good way to get a smeared tile. Rasterising once
 * also means a home screen of forty icons decodes forty bitmaps, not forty per
 * redraw.
 */
private val rasterised = object : LruCache<Int, ImageBitmap>(160) {}

fun iconBitmapOf(drawable: Drawable?): ImageBitmap? {
    if (drawable == null) return null
    val key = System.identityHashCode(drawable)
    rasterised.get(key)?.let { return it }
    val bitmap = runCatching {
        // Work on a copy so the shared instance's bounds are never touched.
        val own = drawable.constantState?.newDrawable()?.mutate() ?: drawable.mutate()
        own.toBitmap(192, 192).asImageBitmap()
    }.getOrNull() ?: return null
    rasterised.put(key, bitmap)
    return bitmap
}

@Composable
fun AppIcon(drawable: Drawable?, size: Dp = 18.dp) {
    val bitmap = remember(drawable) { iconBitmapOf(drawable) } ?: return
    Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
}
