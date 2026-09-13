package dev.jaronwilson.modes.launcher

import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.jaronwilson.modes.core.model.Folder
import dev.jaronwilson.modes.ui.AppTileIcon
import dev.jaronwilson.modes.ui.FolderGlyph
import dev.jaronwilson.modes.ui.FullBleedDialogWindow
import dev.jaronwilson.modes.ui.PickerPalette
import dev.jaronwilson.modes.ui.theme.Brand
import dev.jaronwilson.modes.ui.tileColors

/** One cell of the drawer: an app, or one of your folders. */
sealed interface DrawerItem {
    data class App(val entry: AppEntry) : DrawerItem
    data class Group(val folder: Folder) : DrawerItem
}

/**
 * Every app, with folders.
 *
 * The Pixel launcher will not let you put a folder in the app drawer; Samsung
 * will, and it is the difference between a drawer you organise once and a
 * drawer you scroll forever. Drop one app on another here and they become a
 * folder, exactly as on the home screen, and that folder joins the same shared
 * library, so something grouped while rummaging can then be switched on for a
 * mode.
 *
 * Apps inside a folder are not also listed loose below it: the point is a
 * shorter list, and a drawer that shows both is longer than one that shows
 * neither.
 */
@Composable
fun AppDrawer(
    visible: Boolean,
    apps: List<AppEntry>,
    folders: List<Folder>,
    iconFor: (String) -> Drawable?,
    query: String,
    onQueryChange: (String) -> Unit,
    onLaunch: (String) -> Unit,
    onOpenFolder: (Folder) -> Unit,
    onMerge: (dragged: DrawerItem, target: DrawerItem) -> Unit,
    onAddToHome: (DrawerItem) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    val context = LocalContext.current
    val colors = tileColors(PickerPalette.LAUNCHER)

    val inFolders = remember(folders) { folders.flatMap { it.packages }.toSet() }
    val items = remember(apps, folders, inFolders, query) {
        if (query.isNotBlank()) {
            // Searching looks through everything, folders included, because
            // hunting for an app you filed away should still find it.
            apps.filter { it.label.contains(query, ignoreCase = true) }.map { DrawerItem.App(it) }
        } else {
            folders.map { DrawerItem.Group(it) } +
                apps.filterNot { it.packageName in inFolders }.map { DrawerItem.App(it) }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true
        )
    ) {
        FullBleedDialogWindow()
        Box(Modifier.fillMaxSize().background(Brand.Launcher.background)) {
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(tween(220)) { h -> h / 4 } + fadeIn(tween(200))
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 20.dp)
                ) {
                    Spacer(Modifier.height(18.dp))
                    SearchBar(query, onQueryChange, colors)
                    Spacer(Modifier.height(4.dp))

                    val gridState = rememberLazyGridState()
                    var dragFrom by remember { mutableIntStateOf(-1) }
                    var pointer by remember { mutableStateOf(Offset.Zero) }
                    var hover by remember { mutableIntStateOf(-1) }
                    var hoverSince by remember { mutableLongStateOf(0L) }
                    var mergeTarget by remember { mutableIntStateOf(-1) }
                    var dragDx by remember { mutableFloatStateOf(0f) }
                    var dragDy by remember { mutableFloatStateOf(0f) }

                    // Dragging past the top of the grid means the home screen.
                    // The hint line is already sitting there saying nothing
                    // useful mid-drag, so it becomes the place to drop.
                    val dragging = dragFrom >= 0
                    val overHome = dragging && pointer.y < -12f
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .then(
                                if (dragging) Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (overHome) colors.accent.copy(alpha = 0.20f)
                                        else colors.surface
                                    )
                                    .border(
                                        1.dp,
                                        if (overHome) colors.accent else colors.surface,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(vertical = 10.dp)
                                else Modifier.padding(vertical = 2.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (dragging) "drag up here to put it on the home screen"
                            else "hold and drop one on another to make a folder",
                            fontSize = 11.5.sp,
                            fontFamily = Brand.sans,
                            color = if (overHome) colors.accent else colors.faint
                        )
                    }

                    fun indexUnder(point: Offset): Int = gridState.layoutInfo.visibleItemsInfo
                        .firstOrNull { info ->
                            point.x >= info.offset.x && point.x <= info.offset.x + info.size.width &&
                                point.y >= info.offset.y && point.y <= info.offset.y + info.size.height
                        }?.index ?: -1

                    fun endDrag() {
                        val toHome = dragFrom >= 0 && pointer.y < -12f
                        if (toHome && dragFrom in items.indices) {
                            onAddToHome(items[dragFrom])
                        } else if (mergeTarget >= 0 && dragFrom >= 0 &&
                            dragFrom in items.indices && mergeTarget in items.indices
                        ) {
                            onMerge(items[dragFrom], items[mergeTarget])
                        }
                        dragFrom = -1; hover = -1; mergeTarget = -1; dragDx = 0f; dragDy = 0f
                    }

                    // Swipe the whole tray down to go back home. Once the grid
                    // is scrolled to the top it stops taking the drag, so the
                    // leftover downward pull collects here and, past a thumb's
                    // travel, closes the tray. Swiping down from the top is how
                    // you dismissed it, so it is how you leave.
                    val dismissPull = remember(onDismiss) {
                        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                            var pulled = 0f
                            override fun onPostScroll(
                                consumed: Offset,
                                available: Offset,
                                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource
                            ): Offset {
                                if (available.y > 0f) {
                                    pulled += available.y
                                    if (pulled > 220f) { pulled = 0f; onDismiss() }
                                } else if (available.y < 0f) {
                                    pulled = 0f
                                }
                                return Offset.Zero
                            }
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        state = gridState,
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 96.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .nestedScroll(dismissPull)
                            // After a long press, so an ordinary flick still
                            // scrolls the list.
                            .pointerInput(items.size) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { start ->
                                        dragFrom = indexUnder(start)
                                        pointer = start
                                        dragDx = 0f; dragDy = 0f
                                        hover = -1; mergeTarget = -1
                                    },
                                    onDrag = { change, delta ->
                                        change.consume()
                                        pointer += delta
                                        dragDx += delta.x
                                        dragDy += delta.y
                                        val over = indexUnder(pointer)
                                        if (pointer.y < -12f) {
                                            hover = -1; mergeTarget = -1
                                        } else if (over != dragFrom && over >= 0) {
                                            val now = System.currentTimeMillis()
                                            if (hover != over) {
                                                hover = over; hoverSince = now; mergeTarget = -1
                                            } else if (now - hoverSince > 400) {
                                                mergeTarget = over
                                            }
                                        } else {
                                            hover = -1; mergeTarget = -1
                                        }
                                    },
                                    onDragEnd = { endDrag() },
                                    onDragCancel = { endDrag() }
                                )
                            }
                    ) {
                        itemsIndexed(
                            items,
                            key = { _, item ->
                                when (item) {
                                    is DrawerItem.App -> "a:" + item.entry.packageName
                                    is DrawerItem.Group -> "f:" + item.folder.id
                                }
                            }
                        ) { index, item ->
                            val dragged = index == dragFrom
                            val target = index == mergeTarget
                            Box(
                                Modifier
                                    .alpha(if (dragged) 0.35f else 1f)
                                    .then(
                                        if (target) Modifier
                                            .clip(RoundedCornerShape(14.dp))
                                            .border(2.dp, colors.accent, RoundedCornerShape(14.dp))
                                        else Modifier
                                    )
                            ) {
                                when (item) {
                                    is DrawerItem.App -> AppTileIcon(
                                        label = item.entry.label,
                                        icon = item.entry.icon ?: iconFor(item.entry.packageName),
                                        colors = colors,
                                        onClick = { onLaunch(item.entry.packageName) }
                                    )
                                    is DrawerItem.Group -> Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .padding(vertical = 6.dp)
                                    ) {
                                        FolderGlyph(
                                            item.folder.packages.take(4).map { pkg ->
                                                apps.firstOrNull { it.packageName == pkg }?.icon
                                                    ?: iconFor(pkg)
                                            },
                                            52.dp,
                                            colors
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            item.folder.name,
                                            fontSize = 11.5.sp,
                                            fontFamily = Brand.sans,
                                            color = colors.muted,
                                            maxLines = 1
                                        )
                                    }
                                }
                                if (item is DrawerItem.Group) {
                                    Box(
                                        Modifier
                                            .matchParentSize()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { onOpenFolder(item.folder) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    value: String,
    onChange: (String) -> Unit,
    colors: dev.jaronwilson.modes.ui.TileColors
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        if (value.isEmpty()) {
            Text("search all apps", color = colors.faint, fontSize = 16.sp, fontFamily = Brand.sans)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = colors.ink, fontSize = 16.sp, fontFamily = Brand.sans),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
