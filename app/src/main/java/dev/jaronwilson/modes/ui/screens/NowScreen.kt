package dev.jaronwilson.modes.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.core.repo.SettingsStore
import dev.jaronwilson.modes.notify.DigestPublisher
import dev.jaronwilson.modes.tools.ShareDump
import dev.jaronwilson.modes.ui.Panel
import dev.jaronwilson.modes.ui.Perms
import dev.jaronwilson.modes.ui.RowItem
import dev.jaronwilson.modes.ui.ScreenScaffold
import dev.jaronwilson.modes.ui.SectionHeader
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NowScreen(onOpenModes: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val modes by AppGraph.repo.modes.collectAsState(initial = emptyList())
    val active by AppGraph.repo.settings.active.collectAsState(initial = SettingsStore.ActiveState())
    val held by AppGraph.repo.heldDao.observePendingCount().collectAsState(initial = 0)
    val manual by AppGraph.repo.settings.manualOverride.collectAsState(initial = null to 0L)

    var permsTick by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permsTick++ }
    val perms = remember(permsTick) { Perms.all(context) }
    val missingRequired = perms.filter { it.required && !it.granted }

    val activeMode = modes.firstOrNull { it.id == active.modeId }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenScaffold(
            title = activeMode?.let { "${it.glyph} ${it.name}".trim() } ?: "Modes",
            subtitle = buildString {
                append(active.reason.ifBlank { "Nothing scheduled" })
                if (active.until > 0) {
                    append(" · until ")
                    append(
                        DateTimeFormatter.ofPattern("HH:mm").format(
                            Instant.ofEpochMilli(active.until).atZone(ZoneId.systemDefault())
                        )
                    )
                }
            }
        ) {
            val roleLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { permsTick++ }

            if (missingRequired.isNotEmpty()) {
                SectionHeader("Activate")
                Panel {
                    Text(
                        "Editing happens here, in the app. Living with it happens on the " +
                            "home screen. Nothing is active until these are on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val home = missingRequired.firstOrNull { it.key == "home" }
                    if (home != null) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                home.intent?.let { runCatching { roleLauncher.launch(it) } }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Make Modes my home screen") }
                        Text(
                            "Your old home screen is not deleted, just no longer what the " +
                                "home button opens. Undo it from the same dialog any time.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    missingRequired.filter { it.key != "home" }.forEach { item ->
                        RowItem(
                            title = item.title,
                            subtitle = item.why,
                            trailing = {
                                item.intent?.let { intent ->
                                    OutlinedButton(onClick = {
                                        runCatching { context.startActivity(intent) }
                                    }) { Text("Open") }
                                }
                            }
                        )
                        item.note?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                }
            }

            if (missingRequired.isEmpty()) {
                SectionHeader("Active")
                Panel {
                    Text(
                        "Modes is your home screen and the gate is watching. Press home to " +
                            "see the simple side.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            SectionHeader("Switch by hand")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { mode ->
                    FilterChip(
                        selected = mode.id == active.modeId,
                        onClick = {
                            scope.launch {
                                val (pinned, _) = manual
                                if (pinned == mode.id) {
                                    AppGraph.repo.settings.setManualOverride(null, 0L)
                                } else {
                                    AppGraph.repo.settings.setManualOverride(mode.id, 0L)
                                }
                                AppGraph.scheduler.reevaluate("manual switch")
                                AppGraph.scheduler.scheduleNextBoundary()
                                AppGraph.scheduler.scheduleNextDigest()
                            }
                        },
                        label = { Text("${mode.glyph} ${mode.name}".trim()) }
                    )
                }
            }
            if (manual.first != null) {
                TextButton(onClick = {
                    scope.launch {
                        AppGraph.repo.settings.setManualOverride(null, 0L)
                        AppGraph.scheduler.reevaluate("override cleared")
                    }
                }) { Text("Back to the schedule") }
            }

            SectionHeader("Waiting")
            Panel {
                RowItem(
                    title = if (held == 0) "Nothing is being held" else "$held held back",
                    subtitle = activeMode?.let { describeDigest(it.digestTimes, it.digestEveryMinutes) }
                )
                if (held > 0) {
                    Button(
                        onClick = {
                            scope.launch {
                                DigestPublisher(context, AppGraph.repo)
                                    .releaseAll("Delivered on request")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Deliver everything now") }
                }
            }

            SectionHeader("Today")
            Panel {
                val calendarCount by produceState(initialValue = -1) {
                    value = runCatching { AppGraph.scheduler.calendar.calendars().size }.getOrDefault(0)
                }
                val hasCalendarPermission = AppGraph.scheduler.calendar.hasPermission
                val events by produceState(initialValue = emptyList<String>()) {
                    val now = System.currentTimeMillis()
                    value = AppGraph.scheduler.calendar
                        .events(now, now + 12 * 60 * 60 * 1000L)
                        .take(6)
                        .map {
                            val t = DateTimeFormatter.ofPattern("HH:mm").format(
                                Instant.ofEpochMilli(it.begin).atZone(ZoneId.systemDefault())
                            )
                            "$t  ${it.title}"
                        }
                }
                when {
                    !hasCalendarPermission -> Text(
                        "Calendar access is off. Grant it under Permissions below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    calendarCount == 0 -> {
                        Text(
                            "No calendar is synced to this phone, so there is nothing to " +
                                "read. If your events are in Google Calendar, turn on Calendar " +
                                "under that account's sync settings. If they are in Outlook, turn " +
                                "on \"Sync calendars\" in Outlook's account settings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_SYNC_SETTINGS)
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Open account sync settings") }
                    }
                    events.isEmpty() -> Text(
                        "Nothing more on the calendar today." +
                            if (calendarCount > 0) " $calendarCount calendars synced." else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (events.isNotEmpty()) {
                    events.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenModes) { Text("Edit modes") }
                AssistChip(
                    onClick = { runCatching { context.startActivity(Perms.appSettings(context)) } },
                    label = { Text("App settings") }
                )
            }

            SectionHeader("Dump your app list")
            Panel {
                Text(
                    "Every installed app with its package name, your folders, and " +
                        "each mode's home screen. Copy it, or send it somewhere with " +
                        "a keyboard, and build your folders from real names instead " +
                        "of guesses.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                var copied by remember { mutableStateOf(0) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            copied = ShareDump.copyToClipboard(context, AppGraph.repo)
                        }
                    }) { Text("Copy") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            runCatching { ShareDump.share(context, AppGraph.repo) }
                        }
                    }) { Text("Send") }
                }
                if (copied > 0) {
                    Text(
                        "Copied $copied characters to the clipboard. Paste it anywhere.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            SectionHeader("Permissions")
            Panel {
                perms.forEach { item ->
                    RowItem(
                        title = item.title,
                        subtitle = if (item.granted) "On" else item.why,
                        trailing = {
                            if (!item.granted && item.intent != null) {
                                TextButton(onClick = {
                                    runCatching {
                                        if (item.key == "home") roleLauncher.launch(item.intent)
                                        else context.startActivity(item.intent)
                                    }
                                }) { Text("Grant") }
                            } else {
                                Text(
                                    if (item.granted) "on" else "off",
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

internal fun describeDigest(times: List<Int>, everyMinutes: Int): String = when {
    everyMinutes > 0 -> "Delivered every $everyMinutes minutes"
    times.isNotEmpty() -> "Delivered at " + times.sorted().joinToString(", ") {
        "%02d:%02d".format(it / 60, it % 60)
    }
    else -> "Delivered when this mode ends"
}
