package dev.jaronwilson.modes.launcher

import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.jaronwilson.modes.core.model.HomeRow
import dev.jaronwilson.modes.ui.AppTileIcon
import dev.jaronwilson.modes.ui.FolderGlyph
import dev.jaronwilson.modes.ui.PickerPalette
import dev.jaronwilson.modes.ui.tileColors
import dev.jaronwilson.modes.ui.theme.Brand

/**
 * The home screen for modes where the phone is yours.
 *
 * Icons, because they are faster to hit and pleasant to look at. That is
 * precisely the argument against them in Work, School or Sleep, where the same
 * qualities are what pull you in, so those modes keep the text list. The two
 * layouts read the same data; only the invitation differs.
 */
@Composable
fun IconHome(
    rows: List<HomeRow>,
    icons: Map<String, Drawable?>,
    openFolder: Long?,
    onToggleFolder: (Long) -> Unit,
    onEditHome: () -> Unit,
    onLaunch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val expanded = rows.firstOrNull { it.entry.id == openFolder && it.isFolder }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        items(rows, key = { it.entry.id }) { row ->
            val installed = row.packages.filter { AppList.isOpenable(context, it) }
            if (installed.isEmpty()) return@items

            if (row.isFolder) {
                FolderTile(
                    name = row.name,
                    icons = installed.take(4).map { icons[it] },
                    onClick = { onToggleFolder(row.entry.id) },
                    onLongClick = onEditHome
                )
            } else {
                val pkg = installed.first()
                AppTile(
                    label = AppList.label(context, pkg),
                    icon = icons[pkg],
                    onClick = { onLaunch(pkg) },
                    onLongClick = onEditHome
                )
            }
        }

        // The open folder's contents, inline under the grid rather than in a
        // dialog: a dialog would need dismissing, and this is a home screen.
        if (expanded != null) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(4) }) {
                Spacer(Modifier.height(4.dp))
            }
            val contents = expanded.packages.filter { AppList.isOpenable(context, it) }
            items(contents, key = { "open:$it" }) { pkg ->
                AppTile(
                    label = AppList.label(context, pkg),
                    icon = icons[pkg],
                    onClick = { onLaunch(pkg) },
                    onLongClick = onEditHome
                )
            }
        }
    }
}

@Composable
private fun AppTile(
    label: String,
    icon: Drawable?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    AppTileIcon(
        label = label,
        icon = icon,
        colors = tileColors(PickerPalette.LAUNCHER),
        onClick = onClick,
        modifier = Modifier.tileClick(onClick, onLongClick)
    )
}

@Composable
private fun FolderTile(
    name: String,
    icons: List<Drawable?>,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val colors = tileColors(PickerPalette.LAUNCHER)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.tileClick(onClick, onLongClick).padding(vertical = 6.dp)
    ) {
        FolderGlyph(icons, 52.dp, colors)
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            fontSize = 11.5.sp,
            fontFamily = Brand.sans,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AppIconImage(drawable: Drawable?, size: Dp) {
    val bitmap: ImageBitmap? = remember(drawable) {
        runCatching { drawable?.toBitmap(128, 128)?.asImageBitmap() }.getOrNull()
    }
    if (bitmap == null) {
        // An app with no icon still deserves a shape, so the grid stays aligned.
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 4))
                .background(Color(0x22FFFFFF))
        )
    } else {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(size))
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.tileClick(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this
        .clip(RoundedCornerShape(12.dp))
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
