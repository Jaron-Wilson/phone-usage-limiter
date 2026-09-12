package dev.jaronwilson.modes.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.core.model.Folder
import dev.jaronwilson.modes.launcher.AppEntry
import dev.jaronwilson.modes.launcher.AppList
import dev.jaronwilson.modes.ui.AppIcon
import dev.jaronwilson.modes.ui.AppPicker
import dev.jaronwilson.modes.ui.PickerPalette
import dev.jaronwilson.modes.core.model.HomeStyle
import dev.jaronwilson.modes.ui.ScreenScaffold
import kotlinx.coroutines.launch

/**
 * The shared folder library.
 *
 * One card per folder, closed until you want it. The previous version laid
 * every control and a wall of app chips out at once, which made eight folders
 * look like a settings dump rather than a short list of eight things.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FoldersScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val folders by AppGraph.repo.folderDao.observeAll().collectAsState(initial = emptyList())
    val apps = remember { AppList.all(context, withIcons = true) }
    val iconOf = remember(apps) { apps.associateBy { it.packageName } }

    var openFolder by remember { mutableStateOf<Long?>(null) }
    var newName by remember { mutableStateOf("") }

    fun save(folder: Folder) = scope.launch { AppGraph.repo.folderDao.upsert(folder) }

    fun reorder(folder: Folder, delta: Int) {
        val list = folders.sortedBy { it.sortOrder }.toMutableList()
        val from = list.indexOfFirst { it.id == folder.id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        scope.launch {
            AppGraph.repo.folderDao.upsertAll(list.mapIndexed { i, f -> f.copy(sortOrder = i) })
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenScaffold(
            title = "Folders",
            subtitle = "Defined once, switched on per mode. Tap one to open it."
        ) {
            Spacer(Modifier.height(12.dp))

            folders.forEachIndexed { index, folder ->
                FolderCard(
                    folder = folder,
                    apps = apps,
                    iconOf = iconOf,
                    expanded = openFolder == folder.id,
                    isFirst = index == 0,
                    isLast = index == folders.lastIndex,
                    onToggle = { openFolder = if (openFolder == folder.id) null else folder.id },
                    onSave = { save(it) },
                    onMove = { delta -> reorder(folder, delta) },
                    onDelete = {
                        scope.launch {
                            AppGraph.repo.homeDao.clearFolderRefs(folder.id)
                            AppGraph.repo.folderDao.delete(folder)
                            openFolder = null
                        }
                    }
                )
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("New folder", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            placeholder = { Text("Errands") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = newName.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    AppGraph.repo.folderDao.upsert(
                                        Folder(name = newName.trim(), sortOrder = folders.size)
                                    )
                                }
                                newName = ""
                            }
                        ) { Icon(Icons.Default.Add, contentDescription = "Create") }
                    }
                    Text(
                        "Starts switched off everywhere. Turn it on from a mode's home screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderCard(
    folder: Folder,
    apps: List<AppEntry>,
    iconOf: Map<String, AppEntry>,
    expanded: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: () -> Unit,
    onSave: (Folder) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val installed = folder.packages.filter { AppList.isOpenable(context, it) }
    var query by remember(folder.id) { mutableStateOf("") }
    var name by remember(folder.id, folder.name) { mutableStateOf(folder.name) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (expanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        // Header: a glance at what is inside, and how many.
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    installed.take(4).chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            pair.forEach { AppIcon(iconOf[it]?.icon, 14.dp) }
                        }
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(folder.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (installed.isEmpty()) "nothing installed"
                    else installed.joinToString(", ") { AppList.label(context, it) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "${installed.size}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    trailingIcon = {
                        if (name.trim() != folder.name && name.isNotBlank()) {
                            TextButton(onClick = { onSave(folder.copy(name = name.trim())) }) {
                                Text("Save")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (folder.packages.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Label("In this folder, in order")
                    folder.packages.forEachIndexed { i, pkg ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(iconOf[pkg]?.icon, 22.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                AppList.label(context, pkg),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            SmallIcon(Icons.Default.KeyboardArrowUp, "Move up", i > 0) {
                                val m = folder.packages.toMutableList()
                                m.add(i - 1, m.removeAt(i))
                                onSave(folder.copy(packages = m))
                            }
                            SmallIcon(
                                Icons.Default.KeyboardArrowDown, "Move down",
                                i < folder.packages.lastIndex
                            ) {
                                val m = folder.packages.toMutableList()
                                m.add(i + 1, m.removeAt(i))
                                onSave(folder.copy(packages = m))
                            }
                            SmallIcon(Icons.Default.Close, "Remove", true) {
                                onSave(folder.copy(packages = folder.packages - pkg))
                            }
                        }
                    }
                    Text(
                        "The first one is under your thumb when the folder opens.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))
                Label("Add an app")
                AppPicker(
                    apps = apps.filter { it.packageName !in folder.packages },
                    style = HomeStyle.ICONS,
                    query = query,
                    onQueryChange = { query = it },
                    palette = PickerPalette.APP,
                    limit = if (query.isBlank()) 12 else 30,
                    placeholder = "Search",
                    onPick = { app -> onSave(folder.copy(packages = folder.packages + app.packageName)) }
                )

                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Position",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SmallIcon(Icons.Default.KeyboardArrowUp, "Move folder up", !isFirst) { onMove(-1) }
                    SmallIcon(Icons.Default.KeyboardArrowDown, "Move folder down", !isLast) { onMove(1) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun SmallIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(34.dp)) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(19.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        )
    }
}
