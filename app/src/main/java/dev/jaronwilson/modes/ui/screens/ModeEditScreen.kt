package dev.jaronwilson.modes.ui.screens

import android.app.NotificationManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.core.model.GuardMode
import dev.jaronwilson.modes.core.model.GuardScope
import dev.jaronwilson.modes.core.model.Mode
import dev.jaronwilson.modes.core.model.NotifClass
import dev.jaronwilson.modes.launcher.AppList
import dev.jaronwilson.modes.ui.AppIcon
import dev.jaronwilson.modes.ui.Panel
import dev.jaronwilson.modes.ui.ScreenScaffold
import dev.jaronwilson.modes.ui.SectionHeader
import dev.jaronwilson.modes.ui.SwitchRow
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModeEditScreen(modeId: String, onDone: () -> Unit, onEditHome: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<Mode?>(null) }

    LaunchedEffect(modeId) { mode = AppGraph.repo.mode(modeId) }

    val apps = remember { AppList.all(context, withIcons = true) }
    val current = mode ?: return

    fun update(block: (Mode) -> Mode) {
        val next = block(current)
        mode = next
        scope.launch {
            AppGraph.repo.modeDao.upsert(next)
            AppGraph.scheduler.reevaluate("mode edited")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenScaffold(
            title = "${current.glyph} ${current.name}".trim(),
            subtitle = describeDigest(current.digestTimes, current.digestEveryMinutes)
        ) {
            SectionHeader("What gets through")
            Panel {
                Text(
                    "Anything not ticked is held until the next delivery. " +
                        "Calls always come through, whatever you pick here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NotifClass.entries.forEach { cls ->
                        val on = cls in current.allowedClasses
                        FilterChip(
                            selected = on,
                            enabled = cls != NotifClass.CALL,
                            onClick = {
                                update { m ->
                                    m.copy(
                                        allowedClasses = if (on) m.allowedClasses - cls
                                        else m.allowedClasses + cls
                                    )
                                }
                            },
                            label = { Text(cls.label) }
                        )
                    }
                }
                SwitchRow(
                    title = "People on your list always get through",
                    subtitle = "Overrides everything above",
                    checked = current.vipsAlwaysThrough,
                    onChange = { v -> update { it.copy(vipsAlwaysThrough = v) } }
                )
            }

            SectionHeader("Apps set aside")
            Panel {
                Text(
                    "Held notifications, hidden from the minimal home screen, and " +
                        "a pause if you open one anyway.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                var blockQuery by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = blockQuery,
                    onValueChange = { blockQuery = it },
                    label = { Text("Find an app") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // Chosen ones first so they are never scrolled out of sight,
                // then whatever matches the search. A phone with a hundred apps
                // cannot be edited from an alphabetical slice of sixty.
                val shown = remember(blockQuery, apps, current.blockedPackages) {
                    val chosen = apps.filter { it.packageName in current.blockedPackages }
                    val rest = apps.filter { it.packageName !in current.blockedPackages }
                        .filter { blockQuery.isBlank() || it.label.contains(blockQuery, ignoreCase = true) }
                        .take(40)
                    chosen + rest
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEach { app ->
                        val on = app.packageName in current.blockedPackages
                        FilterChip(
                            selected = on,
                            onClick = {
                                update { m ->
                                    m.copy(
                                        blockedPackages = if (on) m.blockedPackages - app.packageName
                                        else m.blockedPackages + app.packageName
                                    )
                                }
                            },
                            label = { Text(app.label) },
                            leadingIcon = { AppIcon(app.icon) }
                        )
                    }
                }
            }

            SectionHeader("Home screen")
            Panel {
                Text(
                    "Folders, order, and what this mode is for.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onEditHome, modifier = Modifier.fillMaxWidth()) {
                    Text("Arrange home screen and folders")
                }
            }

            SectionHeader("If you reach for one anyway")
            Panel {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GuardMode.entries.forEach { g ->
                        FilterChip(
                            selected = current.guardMode == g,
                            onClick = { update { it.copy(guardMode = g) } },
                            label = {
                                Text(
                                    when (g) {
                                        GuardMode.OFF -> "Let me"
                                        GuardMode.SPEEDBUMP -> "Pause first"
                                        GuardMode.BLOCK -> "Send me home"
                                    }
                                )
                            }
                        )
                    }
                }
                Text(
                    "What counts as \"one anyway\":",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GuardScope.entries.forEach { scopeOption ->
                        FilterChip(
                            selected = current.guardScope == scopeOption,
                            onClick = { update { it.copy(guardScope = scopeOption) } },
                            label = {
                                Text(
                                    when (scopeOption) {
                                        GuardScope.BLOCKLIST -> "Only apps I set aside"
                                        GuardScope.ALLOWLIST -> "Anything not on my home screen"
                                    }
                                )
                            }
                        )
                    }
                }
                Text(
                    if (current.guardScope == GuardScope.ALLOWLIST) {
                        "Strict. An app you install next week is off-limits until you " +
                            "put it on this mode's home screen. Phone, Messages, Clock, " +
                            "Settings and your money apps are never stopped."
                    } else {
                        "Loose. Only the apps listed above are stopped, so anything new " +
                            "is allowed by default."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                NumberRow("Pause length, seconds", current.speedbumpSeconds) { v ->
                    update { it.copy(speedbumpSeconds = v) }
                }
                NumberRow("Pass length, minutes", current.passMinutes) { v ->
                    update { it.copy(passMinutes = v) }
                }
            }

            SectionHeader("Do Not Disturb")
            Panel {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        0 to "Leave alone",
                        NotificationManager.INTERRUPTION_FILTER_PRIORITY to "Priority only",
                        NotificationManager.INTERRUPTION_FILTER_ALARMS to "Alarms only",
                        NotificationManager.INTERRUPTION_FILTER_NONE to "Total silence"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = current.interruptionFilter == value,
                            onClick = { update { it.copy(interruptionFilter = value) } },
                            label = { Text(label) }
                        )
                    }
                }
                SwitchRow(
                    title = "Grey out the screen",
                    subtitle = "Android 15 and newer. Colour is most of what makes a " +
                        "phone hard to put down.",
                    checked = current.grayscale,
                    onChange = { v -> update { it.copy(grayscale = v) } }
                )
                SwitchRow(
                    title = "Dim the wallpaper",
                    checked = current.dimWallpaper,
                    onChange = { v -> update { it.copy(dimWallpaper = v) } }
                )
                SwitchRow(
                    title = "No always-on display",
                    checked = current.suppressAmbientDisplay,
                    onChange = { v -> update { it.copy(suppressAmbientDisplay = v) } }
                )
            }

            SectionHeader("When held things arrive")
            Panel {
                NumberRow(
                    "Every N minutes, 0 for fixed times",
                    current.digestEveryMinutes
                ) { v -> update { it.copy(digestEveryMinutes = v) } }

                var timesText by remember(current.id) {
                    mutableStateOf(current.digestTimes.joinToString(", ") {
                        "%02d:%02d".format(it / 60, it % 60)
                    })
                }
                OutlinedTextField(
                    value = timesText,
                    onValueChange = { timesText = it },
                    label = { Text("Fixed times, e.g. 12:30, 17:00") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val parsed = timesText.split(",").mapNotNull { chunk ->
                            val parts = chunk.trim().split(":")
                            val h = parts.getOrNull(0)?.trim()?.toIntOrNull()
                            val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                            if (h == null || h !in 0..23 || m !in 0..59) null else h * 60 + m
                        }.sorted()
                        update { it.copy(digestTimes = parsed) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save times") }

                SwitchRow(
                    title = "Also deliver when the mode ends",
                    checked = current.releaseOnModeExit,
                    onChange = { v -> update { it.copy(releaseOnModeExit = v) } }
                )
            }

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable
private fun NumberRow(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next.filter { it.isDigit() }
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}
