package dev.jaronwilson.modes.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.jaronwilson.modes.core.model.HomeRow
import androidx.compose.ui.platform.LocalContext
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.ui.AppIcon
import dev.jaronwilson.modes.ui.AppPicker
import dev.jaronwilson.modes.ui.FolderGlyph
import dev.jaronwilson.modes.ui.PickerPalette
import dev.jaronwilson.modes.ui.tileColors
import dev.jaronwilson.modes.ui.theme.Brand
import dev.jaronwilson.modes.core.model.HomeStyle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val Ink = Brand.Launcher.ink
private val InkBright = Brand.Launcher.ink
private val InkDim = Brand.Launcher.muted
private val InkFaint = Brand.Launcher.faint
private val Accent = Brand.Launcher.accent

/**
 * Rearranging the home screen where you actually use it.
 *
 * Rows are a fixed height on purpose. Knowing the size of a row up front turns
 * "which row is under my finger" into arithmetic instead of a hit test against
 * a layout that is still settling, and the drag stays exact when the list
 * reorders underneath the finger mid-gesture.
 *
 * The item moves as you drag rather than on release, so the gap you are aiming
 * at is the gap you get.
 */
private val RowHeight = 52.dp

/**
 * How long the finger must rest over a folder before the drag stops meaning
 * "move past this" and starts meaning "put it in here".
 *
 * Without a pause the two gestures are the same gesture, and you could never
 * drag an app past a folder without it being swallowed.
 */
private const val AbsorbDwellMs = 450L

@Composable
fun EditBar(
    modeName: String,
    onAddApp: () -> Unit,
    onAddFolder: () -> Unit,
    onDone: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Brand.Launcher.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("editing $modeName", fontSize = 12.sp, letterSpacing = 1.sp, color = Accent)
        Spacer(Modifier.weight(1f))
        Text(
            "+ app",
            fontSize = 13.sp,
            color = Ink,
            modifier = Modifier.clickable(onClick = onAddApp).padding(horizontal = 8.dp)
        )
        Text(
            "+ folder",
            fontSize = 13.sp,
            color = Ink,
            modifier = Modifier.clickable(onClick = onAddFolder).padding(horizontal = 8.dp)
        )
        Text(
            "done",
            fontSize = 13.sp,
            color = Accent,
            modifier = Modifier.clickable(onClick = onDone).padding(start = 8.dp)
        )
    }
}

/**
 * The text home screen, in edit mode.
 *
 * @param onMove called live as the drag crosses a row boundary
 * @param onRemove takes the row off this mode, leaving the folder library alone
 */
@Composable
fun EditableRowList(
    rows: List<HomeRow>,
    label: (HomeRow) -> String,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (HomeRow) -> Unit,
    onOpen: (HomeRow) -> Unit,
    onDropInto: (dragged: HomeRow, folder: HomeRow) -> Unit
) {
    val density = LocalDensity.current
    val rowPx = with(density) { RowHeight.toPx() }

    var dragging by remember { mutableIntStateOf(-1) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var hoverIndex by remember { mutableIntStateOf(-1) }
    var hoverSince by remember { mutableLongStateOf(0L) }
    var absorbInto by remember { mutableIntStateOf(-1) }

    fun endDrag() {
        if (absorbInto >= 0 && dragging >= 0 &&
            dragging in rows.indices && absorbInto in rows.indices
        ) {
            onDropInto(rows[dragging], rows[absorbInto])
        }
        dragging = -1; offsetY = 0f; hoverIndex = -1; absorbInto = -1
    }

    Column {
        rows.forEachIndexed { index, row ->
            val isDragged = dragging == index
            val isAbsorbTarget = absorbInto == index
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RowHeight)
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer { if (isDragged) translationY = offsetY }
                    .alpha(if (isDragged) 0.9f else 1f)
                    .then(
                        if (isAbsorbTarget) Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Brand.Launcher.accent.copy(alpha = 0.22f))
                        else Modifier
                    )
                    .pointerInput(rows.size, index) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragging = index; offsetY = 0f
                                hoverIndex = -1; absorbInto = -1
                            },
                            onDrag = { change, delta ->
                                change.consume()
                                offsetY += delta.y
                                val from = dragging
                                val steps = (offsetY / rowPx).roundToInt()
                                val to = (from + steps).coerceIn(0, rows.lastIndex)
                                val draggedIsApp = rows.getOrNull(from)?.isFolder == false
                                val targetIsFolder = rows.getOrNull(to)?.isFolder == true

                                if (to != from && targetIsFolder && draggedIsApp) {
                                    // Hovering a folder: wait, then absorb.
                                    val now = System.currentTimeMillis()
                                    if (hoverIndex != to) {
                                        hoverIndex = to; hoverSince = now; absorbInto = -1
                                    } else if (now - hoverSince > AbsorbDwellMs) {
                                        absorbInto = to
                                    }
                                } else if (to != from) {
                                    onMove(from, to)
                                    dragging = to
                                    offsetY -= (to - from) * rowPx
                                    hoverIndex = -1; absorbInto = -1
                                }
                            },
                            onDragEnd = { endDrag() },
                            onDragCancel = { endDrag() }
                        )
                    }
            ) {
                Text("::", fontSize = 15.sp, color = if (isDragged) Accent else InkFaint)
                Spacer(Modifier.width(14.dp))
                Text(
                    label(row) + if (isAbsorbTarget) "   drop in" else "",
                    fontSize = 21.sp,
                    color = when {
                        isAbsorbTarget -> Accent
                        isDragged -> InkBright
                        else -> Ink
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpen(row) }
                )
                if (row.isFolder) {
                    Text("${row.packages.size}", fontSize = 12.sp, color = InkFaint)
                    Spacer(Modifier.width(10.dp))
                }
                RemoveBadge { onRemove(row) }
            }
        }
    }
}

/** The icon home screen, in edit mode. Same arithmetic, two dimensions. */
@Composable
fun EditableIconGrid(
    rows: List<HomeRow>,
    columns: Int,
    iconFor: (String) -> android.graphics.drawable.Drawable?,
    label: (HomeRow) -> String,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: (HomeRow) -> Unit,
    onOpen: (HomeRow) -> Unit,
    onDropInto: (dragged: HomeRow, folder: HomeRow) -> Unit
) {
    val density = LocalDensity.current
    var cellWidthPx by remember { mutableFloatStateOf(0f) }
    val cellHeight = 88.dp
    val cellHeightPx = with(density) { cellHeight.toPx() }

    var dragging by remember { mutableIntStateOf(-1) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var hoverIndex by remember { mutableIntStateOf(-1) }
    var hoverSince by remember { mutableLongStateOf(0L) }
    var absorbInto by remember { mutableIntStateOf(-1) }

    fun endGridDrag() {
        if (absorbInto >= 0 && dragging >= 0 &&
            dragging in rows.indices && absorbInto in rows.indices
        ) {
            onDropInto(rows[dragging], rows[absorbInto])
        }
        dragging = -1; offsetX = 0f; offsetY = 0f; hoverIndex = -1; absorbInto = -1
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        rows.chunked(columns).forEachIndexed { rowIndex, chunk ->
            Row(Modifier.fillMaxWidth()) {
                chunk.forEachIndexed { colIndex, row ->
                    val index = rowIndex * columns + colIndex
                    val isDragged = dragging == index
                    Box(
                        Modifier
                            .weight(1f)
                            .height(cellHeight)
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer {
                                if (cellWidthPx == 0f) cellWidthPx = size.width
                                if (isDragged) {
                                    translationX = offsetX
                                    translationY = offsetY
                                }
                            }
                            .pointerInput(rows.size, index) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragging = index; offsetX = 0f; offsetY = 0f
                                        hoverIndex = -1; absorbInto = -1
                                    },
                                    onDrag = { change, delta ->
                                        change.consume()
                                        offsetX += delta.x
                                        offsetY += delta.y
                                        val w = if (cellWidthPx > 0f) cellWidthPx else 1f
                                        val step = (offsetX / w).roundToInt() +
                                            (offsetY / cellHeightPx).roundToInt() * columns
                                        val from = dragging
                                        val to = (from + step).coerceIn(0, rows.lastIndex)
                                        val draggedIsApp = rows.getOrNull(from)?.isFolder == false
                                        val targetIsFolder = rows.getOrNull(to)?.isFolder == true
                                        if (to != from && targetIsFolder && draggedIsApp) {
                                            val now = System.currentTimeMillis()
                                            if (hoverIndex != to) {
                                                hoverIndex = to; hoverSince = now; absorbInto = -1
                                            } else if (now - hoverSince > AbsorbDwellMs) {
                                                absorbInto = to
                                            }
                                        } else if (to != from) {
                                            onMove(from, to)
                                            dragging = to
                                            val moved = to - from
                                            offsetX -= (moved % columns) * w
                                            offsetY -= (moved / columns) * cellHeightPx
                                            hoverIndex = -1; absorbInto = -1
                                        }
                                    },
                                    onDragEnd = { endGridDrag() },
                                    onDragCancel = { endGridDrag() }
                                )
                            }
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Box(
                                    Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(13.dp))
                                        .background(
                                            if (absorbInto == index) Brand.Launcher.accent.copy(alpha = 0.4f)
                                            else Brand.Launcher.surface
                                        )
                                        .clickable { onOpen(row) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    val first = row.packages.firstOrNull()
                                    if (row.isFolder) {
                                        // A glance at the contents, as outside
                                        // edit mode: a count alone tells you
                                        // nothing about which folder this is.
                                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            row.packages.take(4).chunked(2).forEach { pair ->
                                                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                                    pair.forEach { AppIcon(iconFor(it), 15.dp) }
                                                }
                                            }
                                        }
                                    } else if (first != null) {
                                        AppIcon(iconFor(first), 34.dp)
                                    }
                                }
                                RemoveBadge { onRemove(row) }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                label(row),
                                fontSize = 11.sp,
                                color = if (isDragged) InkBright else InkDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RemoveBadge(onClick: () -> Unit) {
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Brand.Launcher.accent.copy(alpha = 0.18f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("x", fontSize = 13.sp, color = Brand.Launcher.accent)
    }
}

/** Moves an item within a list, returning a new list. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    val out = toMutableList()
    out.add(to, out.removeAt(from))
    return out
}

/** What the picker is currently for. */
sealed interface Picking {
    /** Add a single app to this mode's home screen. */
    data object App : Picking

    /** Put one of the shared folders on this mode's home screen. */
    data object Folder : Picking

    /** Change what is inside a folder. Shared, so this changes every mode. */
    data class InFolder(val folderId: Long) : Picking
}

/**
 * Adding things, without leaving the home screen.
 *
 * Editing a folder's contents here is the one action that reaches beyond this
 * mode, because folders are shared, so it says so rather than surprising you
 * later.
 */
@Composable
fun PickerPanel(
    picking: Picking,
    modeId: String,
    existingRows: List<HomeRow>,
    apps: List<AppEntry>,
    style: HomeStyle,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val folders by AppGraph.repo.folderDao.observeAll().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (picking) {
                    Picking.App -> "add an app"
                    Picking.Folder -> "add a folder"
                    is Picking.InFolder -> "what is in this folder"
                },
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = Accent
            )
            Spacer(Modifier.weight(1f))
            Text(
                "back",
                fontSize = 13.sp,
                color = Ink,
                modifier = Modifier.clickable(onClick = onClose)
            )
        }
        Spacer(Modifier.height(14.dp))

        when (picking) {
            Picking.Folder -> {
                val used = existingRows.mapNotNull { it.entry.folderId }.toSet()
                val available = folders.filterNot { it.id in used }
                if (available.isEmpty()) {
                    Text("Every folder is already here.", fontSize = 15.sp, color = InkDim)
                }
                available.forEach { folder ->
                    PickRow("${folder.name}   ${folder.packages.size}") {
                        scope.launch {
                            AppGraph.repo.homeDao.upsert(
                                dev.jaronwilson.modes.core.model.HomeEntry(
                                    modeId = modeId,
                                    folderId = folder.id,
                                    sortOrder = existingRows.size
                                )
                            )
                        }
                        onClose()
                    }
                }
            }

            Picking.App -> {
                val used = existingRows.mapNotNull { it.entry.packageName }.toSet()
                AppPicker(
                    apps = apps.filterNot { it.packageName in used },
                    style = style,
                    query = query,
                    onQueryChange = { query = it },
                    palette = PickerPalette.LAUNCHER,
                    limit = 24,
                    onPick = { app ->
                        scope.launch {
                            AppGraph.repo.homeDao.upsert(
                                dev.jaronwilson.modes.core.model.HomeEntry(
                                    modeId = modeId,
                                    packageName = app.packageName,
                                    sortOrder = existingRows.size
                                )
                            )
                        }
                        onClose()
                    }
                )
            }

            is Picking.InFolder -> {
                val folder = folders.firstOrNull { it.id == picking.folderId }
                if (folder == null) {
                    Text("That folder is gone.", fontSize = 15.sp, color = InkDim)
                    return@Column
                }
                Text(
                    "Shared: this changes ${folder.name} in every mode that uses it.",
                    fontSize = 12.sp,
                    color = InkDim
                )
                Spacer(Modifier.height(12.dp))
                folder.packages.forEach { pkg ->
                    Row(
                        Modifier.fillMaxWidth().height(RowHeight),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            AppList.label(context, pkg),
                            fontSize = 19.sp,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        RemoveBadge {
                            scope.launch {
                                AppGraph.repo.folderDao.upsert(
                                    folder.copy(packages = folder.packages - pkg)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("ADD", fontSize = 11.sp, letterSpacing = 2.sp, color = InkFaint)
                Spacer(Modifier.height(8.dp))
                AppPicker(
                    apps = apps.filterNot { it.packageName in folder.packages },
                    style = style,
                    query = query,
                    onQueryChange = { query = it },
                    palette = PickerPalette.LAUNCHER,
                    limit = 20,
                    onPick = { app ->
                        scope.launch {
                            AppGraph.repo.folderDao.upsert(
                                folder.copy(packages = folder.packages + app.packageName)
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PickRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 19.sp,
        color = Ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp)
    )
}
