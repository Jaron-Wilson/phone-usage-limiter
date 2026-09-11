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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.core.model.GuardScope
import dev.jaronwilson.modes.core.model.HomeEntry
import dev.jaronwilson.modes.core.model.Mode
import dev.jaronwilson.modes.launcher.AppList
import dev.jaronwilson.modes.ui.Panel
import dev.jaronwilson.modes.ui.ScreenScaffold
import dev.jaronwilson.modes.ui.SectionHeader
import kotlinx.coroutines.launch

/**
 * Arranges one mode's home screen.
 *
 * Worth knowing while you are in here: when the mode's guard is set to
 * allowlist, this screen is also its permission list. An app in no folder is an
 * app that mode will not let you open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeLayoutScreen(modeId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf<Mode?>(null) }
    LaunchedEffect(modeId) { mode = AppGraph.repo.mode(modeId) }

    val entries by AppGraph.repo.homeDao.observeForMode(modeId)
        .collectAsState(initial = emptyList())
    val apps = remember { AppList.all(context) }

    var editing by remember { mutableStateOf<Long?>(null) }
    var newFolderName by remember { mutableStateOf("") }
    var pickerQuery by remember { mutableStateOf("") }

    val current = mode ?: return
    val allowlist = current.guardScope == GuardScope.ALLOWLIST
    val reachable = entries.flatMap { it.reachable }.toSet()

    fun renumber(list: List<HomeEntry>) {
        scope.launch {
            AppGraph.repo.homeDao.upsertAll(
                list.mapIndexed { i, e -> e.copy(sortOrder = i) }
            )
        }
    }

    fun move(entry: HomeEntry, delta: Int) {
        val list = entries.toMutableList()
        val from = list.indexOfFirst { it.id == entry.id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        renumber(list)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenScaffold(
            title = "${current.glyph} ${current.name} home".trim(),
            subtitle = if (allowlist) {
                "This mode only lets you open what is on this screen. " +
                    "${reachable.size} apps allowed."
            } else {
                "Shown on the minimal home screen, in this order."
            }
        ) {
            SectionHeader("Rows")
            Panel {
                if (entries.isEmpty()) {
                    Text(
                        "Nothing here yet. Add an app or a folder below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                entries.forEach { entry ->
                    val isOpen = editing == entry.id
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (entry.isFolder) entry.folderName
                                else AppList.label(context, entry.packageName.orEmpty()),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                if (entry.isFolder) {
                                    entry.packages.joinToString(", ") {
                                        AppList.label(context, it)
                                    }.ifBlank { "empty folder" }
                                } else "single app",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        TextButton(onClick = { move(entry, -1) }) { Text("up") }
                        TextButton(onClick = { move(entry, 1) }) { Text("down") }
                        TextButton(onClick = {
                            editing = if (isOpen) null else entry.id
                        }) { Text(if (isOpen) "close" else "edit") }
                    }

                    if (isOpen) {
                        if (entry.isFolder) {
                            var name by remember(entry.id) { mutableStateOf(entry.folderName) }
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("Folder name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        AppGraph.repo.homeDao.upsert(
                                            entry.copy(folderName = name.trim())
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Rename") }

                            OutlinedTextField(
                                value = pickerQuery,
                                onValueChange = { pickerQuery = it },
                                label = { Text("Find an app") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            val shown = remember(pickerQuery, apps, entry.packages) {
                                val inFolder = apps.filter { it.packageName in entry.packages }
                                val rest = apps.filter { it.packageName !in entry.packages }
                                    .filter {
                                        pickerQuery.isBlank() ||
                                            it.label.contains(pickerQuery, ignoreCase = true)
                                    }
                                    .take(40)
                                inFolder + rest
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                shown.forEach { app ->
                                    val inFolder = app.packageName in entry.packages
                                    FilterChip(
                                        selected = inFolder,
                                        onClick = {
                                            scope.launch {
                                                AppGraph.repo.homeDao.upsert(
                                                    entry.copy(
                                                        packages = if (inFolder) {
                                                            entry.packages - app.packageName
                                                        } else {
                                                            entry.packages + app.packageName
                                                        }
                                                    )
                                                )
                                            }
                                        },
                                        label = { Text(app.label) }
                                    )
                                }
                            }
                        }
                        TextButton(onClick = {
                            scope.launch {
                                AppGraph.repo.homeDao.delete(entry)
                                editing = null
                            }
                        }) { Text("Remove this row") }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            SectionHeader("Add a folder")
            Panel {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Name, e.g. Everyday") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val name = newFolderName.trim()
                        if (name.isNotEmpty()) {
                            scope.launch {
                                AppGraph.repo.homeDao.upsert(
                                    HomeEntry(
                                        modeId = modeId,
                                        folderName = name,
                                        sortOrder = entries.size
                                    )
                                )
                            }
                            newFolderName = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Create folder") }
            }

            SectionHeader("Add a single app")
            Panel {
                Text(
                    "Apps you reach for constantly. A folder you open twenty times " +
                        "a day is just friction.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                var query by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Find an app") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val candidates = remember(query, apps, entries) {
                    apps.filter { app -> entries.none { it.packageName == app.packageName } }
                        .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
                        .take(40)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    candidates.forEach { app ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                scope.launch {
                                    AppGraph.repo.homeDao.upsert(
                                        HomeEntry(
                                            modeId = modeId,
                                            packageName = app.packageName,
                                            sortOrder = entries.size
                                        )
                                    )
                                }
                            },
                            label = { Text(app.label) }
                        )
                    }
                }
            }

            if (allowlist) {
                SectionHeader("Not allowed in this mode")
                Panel {
                    val blocked = remember(apps, reachable) {
                        apps.filter { it.packageName !in reachable }
                    }
                    Text(
                        "${blocked.size} installed apps are not on this screen, so this " +
                            "mode will stop you opening them. Anything you install later " +
                            "starts here too.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        blocked.take(25).joinToString(", ") { it.label },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}
