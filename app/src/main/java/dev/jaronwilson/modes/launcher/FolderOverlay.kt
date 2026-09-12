package dev.jaronwilson.modes.launcher

import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jaronwilson.modes.core.model.Folder
import dev.jaronwilson.modes.core.model.HomeStyle
import dev.jaronwilson.modes.ui.AppRowName
import dev.jaronwilson.modes.ui.AppTileIcon
import dev.jaronwilson.modes.ui.FolderGlyph
import dev.jaronwilson.modes.ui.PickerPalette
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
 */
@Composable
fun FolderOverlay(
    visible: Boolean,
    folder: Folder?,
    trail: List<String>,
    style: HomeStyle,
    foldersById: Map<Long, Folder>,
    iconFor: (String) -> Drawable?,
    onOpenSub: (Long) -> Unit,
    onLaunch: (String) -> Unit,
    onRename: (Folder, String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val colors = tileColors(PickerPalette.LAUNCHER)

    if (!visible || folder == null) return

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

                    FolderTitle(f.name) { newName -> onRename(f, newName) }
                    Spacer(Modifier.height(14.dp))

                    if (subs.isNotEmpty()) {
                        if (style == HomeStyle.ICONS) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                subs.take(4).forEach { sub ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { onOpenSub(sub.id) }
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
                                }
                            }
                        } else {
                            subs.forEach { sub ->
                                AppRowName(
                                    label = sub.name,
                                    colors = colors,
                                    onClick = { onOpenSub(sub.id) }
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    when (style) {
                        HomeStyle.ICONS -> LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.heightIn(max = 420.dp)
                        ) {
                            items(apps, key = { it }) { pkg ->
                                AppTileIcon(
                                    label = AppList.label(context, pkg),
                                    icon = iconFor(pkg),
                                    colors = colors,
                                    onClick = { onLaunch(pkg) }
                                )
                            }
                        }
                        HomeStyle.TEXT -> Column {
                            apps.forEach { pkg ->
                                AppRowName(
                                    label = AppList.label(context, pkg),
                                    colors = colors,
                                    onClick = { onLaunch(pkg) }
                                )
                            }
                        }
                    }

                    if (apps.isEmpty() && subs.isEmpty()) {
                        Text("Nothing in here yet.", color = colors.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
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
