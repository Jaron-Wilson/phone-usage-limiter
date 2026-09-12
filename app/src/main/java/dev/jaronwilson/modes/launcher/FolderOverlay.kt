package dev.jaronwilson.modes.launcher

import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jaronwilson.modes.core.model.Folder
import dev.jaronwilson.modes.core.model.HomeStyle
import dev.jaronwilson.modes.ui.AppPicker
import dev.jaronwilson.modes.ui.AppRowName
import dev.jaronwilson.modes.ui.AppTileIcon
import dev.jaronwilson.modes.ui.FolderGlyph
import dev.jaronwilson.modes.ui.PickerPalette
import dev.jaronwilson.modes.ui.TileColors
import dev.jaronwilson.modes.ui.tileColors
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.jaronwilson.modes.ui.FullBleedDialogWindow
import dev.jaronwilson.modes.ui.theme.Brand

/**
 * A folder, opened.
 *
 * The home screen behind it goes soft and a little lighter, and the folder
 * comes forward as a panel with its name over its contents: the One UI
 * gesture, because it is a good one. Nothing about the screen underneath has
 * to move to make room, so closing puts you back exactly where you were.
 *
 * The name is a text field dressed as a heading. Tap it to rename; a folder
 * made by dropping one app on another arrives called "Folder" and this is
 * where it gets a real name.
 *
 * "Edit" turns the panel into its own editor rather than sending you to a
 * settings screen: every tile grows a remove badge and a search field appears
 * underneath. A folder you opened because you were looking for something is
 * exactly where you want to fix what is in it, and walking to another screen
 * to do that loses your place.
 */
@Composable
fun FolderOverlay(
    visible: Boolean,
    folder: Folder?,
    trail: List<String>,
    style: HomeStyle,
    foldersById: Map<Long, Folder>,
    allApps: List<AppEntry>,
    iconFor: (String) -> Drawable?,
    onOpenSub: (Long) -> Unit,
    onLaunch: (String) -> Unit,
    onRename: (Folder, String) -> Unit,
    onAddApp: (Folder, String) -> Unit,
    onRemoveApp: (Folder, String) -> Unit,
    onRemoveSub: (Folder, Long) -> Unit,
    onReorder: (Folder, List<String>) -> Unit,
    onDeleteFolder: (Folder) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val colors = tileColors(PickerPalette.LAUNCHER)

    if (!visible || folder == null) return

    var editMode by remember(folder.id) { mutableStateOf(false) }
    var pickQuery by remember(folder.id) { mutableStateOf("") }

    // Its own window, for the same reason as the web panel: anything drawn
    // inside the home screen inherits the home screen's gutter.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false
        )
    ) {
        FullBleedDialogWindow()
        // The fog. Light rather than dark, so the screen behind reads as
        // stepped back rather than switched off.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = true,
                enter = scaleIn(tween(180), initialScale = 0.92f) + fadeIn(tween(180))
            ) {
                val f = folder
                val apps = remember(f.packages) { f.packages.filter { AppList.isOpenable(context, it) } }
                val subs = remember(f.subFolders, foldersById) { f.subFolders.mapNotNull { foldersById[it] } }
                // Anything already in here is not offered again, so the search
                // is a list of what you could add rather than what you have.
                val addable = remember(allApps, f.packages) {
                    allApps.filterNot { it.packageName in f.packages }
                }
                val listMax = if (editMode) 210.dp else 420.dp

                Column(
                    Modifier
                        .fillMaxWidth(0.86f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Brand.Dark.surface)
                        .border(1.dp, Brand.Dark.border, RoundedCornerShape(22.dp))
                        // Taps inside stay inside.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .padding(horizontal = 22.dp, vertical = 20.dp)
                ) {
                    if (trail.size > 1) {
                        Text(
                            trail.dropLast(1).joinToString("  >  ").uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.faint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(6.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            FolderTitle(f.name) { newName -> onRename(f, newName) }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (editMode) "done" else "edit",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (editMode) colors.accent else colors.faint,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { editMode = !editMode; pickQuery = "" }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))

                    if (subs.isNotEmpty()) {
                        if (style == HomeStyle.ICONS) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                subs.take(4).forEach { sub ->
                                    Box(Modifier.weight(1f)) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable(enabled = !editMode) { onOpenSub(sub.id) }
                                                .padding(vertical = 6.dp)
                                        ) {
                                            FolderGlyph(sub.packages.take(4).map(iconFor), 52.dp, colors)
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                sub.name,
                                                fontSize = 11.5.sp,
                                                fontFamily = Brand.sans,
                                                color = colors.muted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (editMode) Box(Modifier.align(Alignment.TopEnd)) {
                                            RemoveBadge(colors) { onRemoveSub(f, sub.id) }
                                        }
                                    }
                                }
                            }
                        } else {
                            subs.forEach { sub ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) {
                                        AppRowName(
                                            label = sub.name,
                                            colors = colors,
                                            onClick = { if (!editMode) onOpenSub(sub.id) }
                                        )
                                    }
                                    if (editMode) RemoveText(colors) { onRemoveSub(f, sub.id) }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    // The working order. It follows the folder until you pick
                    // a tile up, and then it follows your thumb, so the grid
                    // rearranges under the drag instead of after it.
                    val gridState = rememberLazyGridState()
                    var order by remember(apps) { mutableStateOf(apps) }
                    var dragFrom by remember(f.id) { mutableIntStateOf(-1) }
                    var pointer by remember { mutableStateOf(Offset.Zero) }

                    fun indexUnder(pt: Offset): Int = gridState.layoutInfo.visibleItemsInfo
                        .firstOrNull { info ->
                            pt.x >= info.offset.x && pt.x <= info.offset.x + info.size.width &&
                                pt.y >= info.offset.y && pt.y <= info.offset.y + info.size.height
                        }?.index ?: -1

                    when (style) {
                        HomeStyle.ICONS -> LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            state = gridState,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .heightIn(max = listMax)
                                .then(
                                    // Only while editing, so an ordinary tap on a
                                    // folder's app still just opens the app.
                                    if (!editMode) Modifier else Modifier.pointerInput(order) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { start ->
                                                dragFrom = indexUnder(start)
                                                pointer = start
                                            },
                                            onDrag = { change, delta ->
                                                change.consume()
                                                pointer += delta
                                                val over = indexUnder(pointer)
                                                if (over >= 0 && dragFrom >= 0 && over != dragFrom) {
                                                    order = order.toMutableList().apply {
                                                        add(over, removeAt(dragFrom))
                                                    }
                                                    dragFrom = over
                                                }
                                            },
                                            onDragEnd = {
                                                if (dragFrom >= 0) onReorder(f, order)
                                                dragFrom = -1
                                            },
                                            onDragCancel = { dragFrom = -1 }
                                        )
                                    }
                                )
                        ) {
                            items(order.size, key = { i -> order[i] }) { i ->
                                val pkg = order[i]
                                Box(Modifier.alpha(if (i == dragFrom) 0.4f else 1f)) {
                                    AppTileIcon(
                                        label = AppList.label(context, pkg),
                                        icon = iconFor(pkg),
                                        colors = colors,
                                        onClick = { if (!editMode) onLaunch(pkg) },
                                        badge = if (!editMode) null else {
                                            { RemoveBadge(colors) { onRemoveApp(f, pkg) } }
                                        }
                                    )
                                }
                            }
                        }
                        HomeStyle.TEXT -> Column(
                            Modifier
                                .heightIn(max = listMax)
                                .verticalScroll(rememberScrollState())
                        ) {
                            order.forEachIndexed { i, pkg ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) {
                                        AppRowName(
                                            label = AppList.label(context, pkg),
                                            colors = colors,
                                            onClick = { if (!editMode) onLaunch(pkg) }
                                        )
                                    }
                                    if (editMode) {
                                        Arrow("\u2191", colors, i > 0) {
                                            onReorder(f, order.toMutableList().apply {
                                                add(i - 1, removeAt(i))
                                            })
                                        }
                                        Arrow("\u2193", colors, i < order.size - 1) {
                                            onReorder(f, order.toMutableList().apply {
                                                add(i + 1, removeAt(i))
                                            })
                                        }
                                        RemoveText(colors) { onRemoveApp(f, pkg) }
                                    }
                                }
                            }
                        }
                    }

                    if (apps.isEmpty() && subs.isEmpty()) {
                        Text(
                            "Nothing in here yet.",
                            color = colors.muted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (editMode) {
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = Brand.Dark.border)
                        Spacer(Modifier.height(14.dp))
                        AppPicker(
                            apps = addable,
                            style = style,
                            query = pickQuery,
                            onQueryChange = { pickQuery = it },
                            onPick = { app -> onAddApp(f, app.packageName) },
                            palette = PickerPalette.LAUNCHER,
                            // Everything, not a sample: search finds a name you
                            // remember, scrolling finds the one you do not.
                            limit = Int.MAX_VALUE,
                            placeholder = "search apps to add",
                            emptyText = "No app by that name",
                            autoFocus = false,
                            scrollable = true,
                            modifier = Modifier.heightIn(max = 300.dp)
                        )
                        Spacer(Modifier.height(14.dp))
                        // Last, and only while editing, because a folder you
                        // emptied by mistake should not be one tap from gone.
                        Text(
                            "delete this folder",
                            fontSize = 12.5.sp,
                            fontFamily = Brand.sans,
                            color = colors.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onDeleteFolder(f) }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/** The little cross that takes something out of a folder. */
@Composable
private fun RemoveBadge(colors: TileColors, onClick: () -> Unit) {
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(colors.accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // A drawn cross, not the multiplication sign: that glyph carries its own
        // side bearings and sits low and left however the box is aligned.
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Remove",
            tint = Brand.Launcher.background,
            modifier = Modifier.size(14.dp)
        )
    }
}

/** One nudge up or down the list, for the style that has no tiles to drag. */
@Composable
private fun Arrow(glyph: String, colors: TileColors, enabled: Boolean, onClick: () -> Unit) {
    Text(
        glyph,
        fontSize = 15.sp,
        fontFamily = Brand.sans,
        color = if (enabled) colors.muted else colors.faint,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    )
}

/** The same thing for a text row, where a badge would have nothing to sit on. */
@Composable
private fun RemoveText(colors: TileColors, onClick: () -> Unit) {
    Text(
        "remove",
        fontSize = 12.sp,
        fontFamily = Brand.sans,
        color = colors.accent,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

/** The folder's name as a heading you can type into. Saves when you leave it. */
@Composable
private fun FolderTitle(name: String, onRename: (String) -> Unit) {
    var draft by remember(name) { mutableStateOf(name) }
    var editing by remember { mutableStateOf(false) }
    val colors = tileColors(PickerPalette.LAUNCHER)

    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = draft,
            onValueChange = { draft = it.take(28) },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineMedium.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { state ->
                    if (editing && !state.isFocused && draft.isNotBlank() && draft != name) {
                        onRename(draft.trim())
                    }
                    editing = state.isFocused
                }
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (editing) "save" else "rename",
            style = MaterialTheme.typography.labelSmall,
            color = if (editing) colors.accent else colors.faint,
            modifier = Modifier.clickable {
                if (editing && draft.isNotBlank() && draft != name) onRename(draft.trim())
                editing = !editing
            }
        )
    }
}
