package dev.jaronwilson.modes.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.core.model.Folder
import dev.jaronwilson.modes.core.model.installedCount
import dev.jaronwilson.modes.core.model.pruned
import dev.jaronwilson.modes.launcher.AppList
import dev.jaronwilson.modes.ui.Panel
import dev.jaronwilson.modes.ui.ScreenScaffold
import dev.jaronwilson.modes.ui.SectionHeader
import kotlinx.coroutines.launch

/**
 * The shared folder library.
 *
 * One definition of "Social", used by every mode. Editing the contents here
 * changes it everywhere at once, which is the point: the grouping of your apps
 * is stable, and what differs between modes is only which folders are switched
 * on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FoldersScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val folders by AppGraph.repo.folderDao.observeAll().collectAsState(initial = emptyList())
    val apps = remember { AppList.all(context) }

    var editing by remember { mutableStateOf<Long?>(null) }
    var newName by remember { mutableStateOf("") }
    var pickerQuery by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenScaffold(
            title = "Folders",
            subtitle = "Defined once, switched on per mode."
        ) {
            val missing = remember(folders) {
                folders.sumOf { f -> f.packages.count { !AppList.isOpenable(context, it) } }
            }
            if (missing > 0) {
                SectionHeader("Tidy up")
                Panel {
                    Text(
                        "$missing entries across your folders name apps that are not " +
                            "on this phone. They are already hidden from the home " +
                            "screen, but they make the counts lie.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                AppGraph.repo.folderDao.upsertAll(
                                    folders.map { f ->
                                        f.pruned { AppList.isOpenable(context, it) }
                                    }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Remove the $missing that are not installed") }
                }
            }

            SectionHeader("Library")
            Panel {
                folders.forEach { folder ->
                    val open = editing == folder.id
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val here = folder.installedCount { AppList.isOpenable(context, it) }
                            Text(
                                if (here == folder.packages.size) folder.name
                                else "${folder.name}   $here of ${folder.packages.size} installed",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                folder.packages
                                    .filter { AppList.isOpenable(context, it) }
                                    .joinToString(", ") { AppList.label(context, it) }
                                    .ifBlank { "nothing installed" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        TextButton(onClick = {
                            editing = if (open) null else folder.id
                            pickerQuery = ""
                        }) { Text(if (open) "close" else "edit") }
                    }

                    if (open) {
                        var name by remember(folder.id) { mutableStateOf(folder.name) }
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Folder name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(onClick = {
                            scope.launch {
                                AppGraph.repo.folderDao.upsert(folder.copy(name = name.trim()))
                            }
                        }) { Text("Rename") }

                        OutlinedTextField(
                            value = pickerQuery,
                            onValueChange = { pickerQuery = it },
                            label = { Text("Find an app") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        val shown = remember(pickerQuery, apps, folder.packages) {
                            val inside = apps.filter { it.packageName in folder.packages }
                            val rest = apps.filter { it.packageName !in folder.packages }
                                .filter {
                                    pickerQuery.isBlank() ||
                                        it.label.contains(pickerQuery, ignoreCase = true)
                                }
                                .take(40)
                            inside + rest
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            shown.forEach { app ->
                                val inside = app.packageName in folder.packages
                                FilterChip(
                                    selected = inside,
                                    onClick = {
                                        scope.launch {
                                            AppGraph.repo.folderDao.upsert(
                                                folder.copy(
                                                    packages = if (inside) {
                                                        folder.packages - app.packageName
                                                    } else {
                                                        folder.packages + app.packageName
                                                    }
                                                )
                                            )
                                        }
                                    },
                                    label = { Text(app.label) }
                                )
                            }
                        }
                        TextButton(onClick = {
                            scope.launch {
                                // Drop the rows pointing at it first, so no mode
                                // is left referencing a folder that is gone.
                                AppGraph.repo.homeDao.clearFolderRefs(folder.id)
                                AppGraph.repo.folderDao.delete(folder)
                                editing = null
                            }
                        }) { Text("Delete this folder, in every mode") }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            SectionHeader("New folder")
            Panel {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name, e.g. Errands") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val n = newName.trim()
                        if (n.isNotEmpty()) {
                            scope.launch {
                                AppGraph.repo.folderDao.upsert(
                                    Folder(name = n, sortOrder = folders.size)
                                )
                            }
                            newName = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Create") }
                Text(
                    "New folders start switched off everywhere. Turn them on from " +
                        "each mode's home screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}
