package dev.jaronwilson.modes.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jaronwilson.modes.core.model.HomeStyle
import dev.jaronwilson.modes.launcher.AppEntry
import dev.jaronwilson.modes.ui.theme.Brand

/**
 * Where the picker is being drawn, which decides its colours.
 *
 * The home screen is black; the app is paper. A picker has to take the room's
 * colours, or the moment of choosing an app is the one moment the screen looks
 * like a different program.
 */
enum class PickerPalette { LAUNCHER, APP }

/** The colours a picker or tile should use in a given room. */
data class TileColors(
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val surface: Color
)

@Composable
fun tileColors(palette: PickerPalette): TileColors = when (palette) {
    PickerPalette.LAUNCHER -> TileColors(
        ink = Brand.Launcher.ink,
        muted = Brand.Launcher.muted,
        faint = Brand.Launcher.faint,
        accent = Brand.Launcher.accent,
        surface = Brand.Launcher.surface
    )
    PickerPalette.APP -> TileColors(
        ink = MaterialTheme.colorScheme.onSurface,
        muted = MaterialTheme.colorScheme.onSurfaceVariant,
        faint = MaterialTheme.colorScheme.outline,
        accent = MaterialTheme.colorScheme.primary,
        surface = MaterialTheme.colorScheme.surfaceVariant
    )
}

/**
 * Choosing apps, in the style of the screen you are on.
 *
 * This exists because of one complaint: an icon home screen that opens a text
 * list to add an app, or a text home screen that opens an icon grid, feels
 * like falling through the floor. Every place an app is chosen now goes through
 * here, and the style is the room's style. The grid uses the same tile as the
 * icon home screen; the list uses the same row as the text one.
 */
@Composable
fun AppPicker(
    apps: List<AppEntry>,
    style: HomeStyle,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
    selected: Set<String> = emptySet(),
    palette: PickerPalette = PickerPalette.APP,
    limit: Int = 40,
    placeholder: String = "type a name",
    emptyText: String = "Nothing matches",
    autoFocus: Boolean = palette == PickerPalette.LAUNCHER
) {
    val colors = tileColors(palette)
    val focus = remember { FocusRequester() }
    // On the home screen the picker *is* the search, so the keyboard should be
    // up before the thumb goes looking for the field.
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    val shown = remember(apps, query, selected, limit) {
        val chosen = apps.filter { it.packageName in selected }
        val rest = apps.filter { it.packageName !in selected }
            .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
            .take(limit)
        chosen + rest
    }

    Column(modifier) {
        SearchField(query, onQueryChange, placeholder, colors, focus)
        Spacer(Modifier.height(14.dp))
        if (shown.isEmpty()) {
            Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            return@Column
        }
        when (style) {
            HomeStyle.ICONS -> LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.heightIn(max = 560.dp)
            ) {
                items(shown, key = { it.packageName }) { app ->
                    AppTileIcon(
                        label = app.label,
                        icon = app.icon,
                        selected = app.packageName in selected,
                        colors = colors,
                        onClick = { onPick(app) }
                    )
                }
            }
            HomeStyle.TEXT -> Column {
                shown.forEach { app ->
                    AppRowName(
                        label = app.label,
                        selected = app.packageName in selected,
                        colors = colors,
                        onClick = { onPick(app) }
                    )
                }
            }
        }
    }
}

/** A quiet search box: an underline on black, a bordered field on paper. */
@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    colors: TileColors,
    focus: FocusRequester
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(1.dp, colors.faint, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = colors.faint, fontSize = 16.sp, fontFamily = Brand.sans)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.ink, fontSize = 16.sp, fontFamily = Brand.sans),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
        )
    }
}

/**
 * The icon tile. One shape for the home screen, the picker and the editor, so
 * an app looks the same wherever it appears.
 */
@Composable
fun AppTileIcon(
    label: String,
    icon: Drawable?,
    colors: TileColors,
    onClick: () -> Unit,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    badge: (@Composable () -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .then(
                        if (selected) Modifier.border(2.dp, colors.accent, RoundedCornerShape(14.dp))
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (icon == null) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surface)
                    )
                } else {
                    AppIcon(icon, 44.dp)
                }
            }
            badge?.invoke()
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            fontSize = 11.5.sp,
            fontFamily = Brand.sans,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.accent else colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/** The text row. The same row the text home screen draws. */
@Composable
fun AppRowName(
    label: String,
    colors: TileColors,
    onClick: () -> Unit,
    selected: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp)
    ) {
        Text(
            label,
            fontSize = 21.sp,
            fontFamily = Brand.sans,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) colors.accent else colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

/** A folder's face: up to four of its icons in a soft square. */
@Composable
fun FolderGlyph(icons: List<Drawable?>, size: androidx.compose.ui.unit.Dp, colors: TileColors, selected: Boolean = false) {
    val mini = size * 0.32f
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(colors.surface)
            .then(
                if (selected) Modifier.border(2.dp, colors.accent, RoundedCornerShape(size * 0.28f))
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            icons.take(4).chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    pair.forEach { AppIcon(it, mini) }
                }
            }
        }
    }
}
