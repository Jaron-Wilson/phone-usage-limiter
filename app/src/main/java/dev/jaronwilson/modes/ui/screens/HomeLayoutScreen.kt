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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.core.model.GuardScope
import dev.jaronwilson.modes.core.model.HomeEntry
import dev.jaronwilson.modes.core.model.Mode
import dev.jaronwilson.modes.core.model.resolveHomeRows
import dev.jaronwilson.modes.launcher.AppList
import dev.jaronwilson.modes.ui.AppIcon
import dev.jaronwilson.modes.ui.Panel
import dev.jaronwilson.modes.ui.ScreenScaffold
import dev.jaronwilson.modes.ui.SectionHeader
import kotlinx.coroutines.launch

/**
 * What one mode's home screen shows, and therefore what it lets you open.
 *
 * Folders come from the shared library, so this screen is mostly switches: the
 * same "Social" folder exists everywhere, and the difference between Work and
 * Personal is which switches are on. Under an allowlist guard a switch turned
 * off is not cosmetic, it is a set of apps that mode will not open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeLayoutScreen(modeId: String, onDone: () -> Unit, onEditFolders: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf<Mode?>(null) }
    LaunchedEffect(modeId) { mode = AppGraph.repo.mode(modeId) }

    val entries by AppGraph.repo.homeDao.observeForMode(modeId)
        .collectAsState(initial = emptyList())
    val folders by AppGraph.repo.folderDao.observeAll().collectAsState(initial = emptyList())
    val apps = remember { AppList.all(context, withIcons = true) }

    val current = mode ?: return
    val allowlist = current.guardScope == GuardScope.ALLOWLIST

    val rows = remember(entries, folders) { resolveHomeRows(entries, folders) }
    val reachable = rows.flatMap { it.reachable }.toSet()

    // Folders with no row on this mode yet. Shown as off, so the whole library
    // is visible as a set of switches rather than hidden behind an add button.
    val unlinked = remember(entries, folders) {
        folders.filter { f -> entries.none { it.folderId == f.id } }
    }

    fun renumber(list: List<HomeEntry>) {
        scope.launch {
            AppGraph.repo.homeDao.upsertAll(list.mapIndexed { i, e -> e.copy(sortOrder = i) })
        }
    }

    fun move(entry: HomeEntry, delta: Int) {
        val list = entries.sortedBy { it.sortOrder }.toMutableList()
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
                "Switched-on rows are the only apps this mode will open. " +
                    "${reachable.size} allowed right now."
            } else {
                "Shown on the minimal home screen, in this order."
            }
        ) {
            SectionHeader("On this mode's home screen")
            Panel {
                if (rows.isEmpty()) {
                    Text(
                        "Nothing here yet. Turn on a folder below, or add an app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                rows.forEach { row ->
                    val entry = row.entry
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (row.isFolder) row.name
                                else AppList.label(context, entry.packageName.orEmpty()),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                if (row.isFolder) {
                                    val contents = row.folder?.packages.orEmpty()
                                    "${contents.size} apps: " + contents.take(4).joinToString(", ") {
                                        AppList.label(context, it)
                                    } + if (contents.size > 4) "..." else ""
                                } else "single app",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        TextButton(onClick = { move(entry, -1) }) { Text("up") }
                        TextButton(onClick = { move(entry, 1) }) { Text("down") }
                        Switch(
                            checked = entry.enabled,
                            onCheckedChange = { on ->
                                scope.launch {
                                    AppGraph.repo.homeDao.upsert(entry.copy(enabled = on))
                                }
                            }
                        )
                    }
                    if (!row.isFolder) {
                        TextButton(onClick = {
                            scope.launch { AppGraph.repo.homeDao.delete(entry) }
                        }) { Text("Remove") }
                    }
                }
            }

            if (unlinked.isNotEmpty()) {
                SectionHeader("Folders not on this mode")
                Panel {
                    Text(
                        "From the shared library. Turning one on adds it to the bottom.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    unlinked.forEach { folder ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(folder.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${folder.packages.size} apps",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = false,
                                onCheckedChange = {
                                    scope.launch {
                                        AppGraph.repo.homeDao.upsert(
                                            HomeEntry(
                                                modeId = modeId,
                                                folderId = folder.id,
                                                sortOrder = entries.size,
                                                enabled = true
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
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
                            label = { Text(app.label) },
                            leadingIcon = { AppIcon(app.icon) }
                        )
                    }
                }
            }

            SectionHeader("The folders themselves")
            Panel {
                Text(
                    "Contents are shared by every mode. Change what is inside " +
                        "\"Social\" once and it changes everywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onEditFolders, modifier = Modifier.fillMaxWidth()) {
                    Text("Edit the folder library")
                }
            }

            if (allowlist) {
                SectionHeader("Not allowed in this mode")
                Panel {
                    val blocked = remember(apps, reachable) {
                        apps.filter { it.packageName !in reachable }
                    }
                    Text(
                        "${blocked.size} installed apps are not reachable here, so this " +
                            "mode will stop you opening them. Anything you install later " +
                            "starts in this list too.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        blocked.take(30).joinToString(", ") { it.label },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}
