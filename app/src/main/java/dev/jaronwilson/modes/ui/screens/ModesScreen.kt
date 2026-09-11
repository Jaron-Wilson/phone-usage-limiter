package dev.jaronwilson.modes.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.core.model.GuardMode
import dev.jaronwilson.modes.ui.Panel
import dev.jaronwilson.modes.ui.RowItem
import dev.jaronwilson.modes.ui.ScreenScaffold
import dev.jaronwilson.modes.ui.SectionHeader
import kotlinx.coroutines.launch

@Composable
fun ModesScreen(onEdit: (String) -> Unit, onEditFolders: () -> Unit) {
    val modes by AppGraph.repo.modes.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenScaffold(
            title = "Modes",
            subtitle = "A mode is a set of answers to: what may interrupt me, " +
                "what is on my home screen, and what happens if I reach for " +
                "something else."
        ) {
            SectionHeader("Your modes")
            Panel {
                modes.forEach { mode ->
                    RowItem(
                        title = "${mode.glyph} ${mode.name}".trim(),
                        subtitle = buildString {
                            append(mode.allowedClasses.joinToString(", ") { it.label.lowercase() }
                                .ifBlank { "nothing gets through" })
                            if (mode.guardMode != GuardMode.OFF) {
                                append(" · ${mode.blockedPackages.size} apps set aside")
                            }
                        },
                        onClick = { onEdit(mode.id) }
                    )
                }
            }

            SectionHeader("Folders")
            Panel {
                Text(
                    "One shared library. Each mode switches folders on or off " +
                        "rather than keeping its own copy."
                )
                OutlinedButton(onClick = onEditFolders, modifier = Modifier.fillMaxWidth()) {
                    Text("Edit the folder library")
                }
            }

            SectionHeader("Tidy up")
            Panel {
                var removed by remember { mutableStateOf(-1) }
                Text(
                    "Removes rows that say the same thing twice, keeping the one you " +
                        "have been arranging. Nothing you set by hand is lost."
                )
                OutlinedButton(
                    onClick = {
                        scope.launch { removed = AppGraph.repo.dedupeHomeEntries() }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Remove duplicate rows") }
                if (removed >= 0) {
                    Text(
                        if (removed == 0) "Nothing duplicated." else "Removed $removed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            SectionHeader("Start over")
            Panel {
                Text(
                    "Puts the shipped modes and rules back the way they were. " +
                        "Anything being held right now is kept."
                )
                OutlinedButton(onClick = {
                    scope.launch {
                        AppGraph.repo.restoreDefaults()
                        AppGraph.scheduler.reevaluate("defaults restored")
                    }
                }) { Text("Restore defaults") }
            }
        }
    }
}
