package dev.jaronwilson.modes.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.core.model.NotifClass
import dev.jaronwilson.modes.core.repo.SettingsStore
import dev.jaronwilson.modes.core.model.NotifRule
import dev.jaronwilson.modes.core.model.Vip
import dev.jaronwilson.modes.commute.CommuteScheduler
import dev.jaronwilson.modes.launcher.AppList
import dev.jaronwilson.modes.ui.AppIcon
import dev.jaronwilson.modes.ui.Panel
import dev.jaronwilson.modes.ui.SwitchRow
import dev.jaronwilson.modes.ui.RowItem
import dev.jaronwilson.modes.ui.ScreenScaffold
import dev.jaronwilson.modes.ui.SectionHeader
import androidx.compose.runtime.produceState
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesScreen() {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val modes by AppGraph.repo.modes.collectAsState(initial = emptyList())
    val calendarRules by AppGraph.repo.ruleDao.observeCalendarRules().collectAsState(initial = emptyList())
    val timeRules by AppGraph.repo.ruleDao.observeTimeRules().collectAsState(initial = emptyList())
    val notifRules by AppGraph.repo.ruleDao.observeNotifRules().collectAsState(initial = emptyList())
    val vips by AppGraph.repo.ruleDao.observeVips().collectAsState(initial = emptyList())

    fun modeName(id: String) = modes.firstOrNull { it.id == id }?.name ?: id

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenScaffold(
            title = "Rules",
            subtitle = "What decides the mode, and how notifications are sorted."
        ) {
            SectionHeader("People who always get through")
            Panel {
                Text(
                    "Matched against the sender name on the notification, so it works " +
                        "for texts, WhatsApp and Instagram DMs alike. Case does not matter.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                vips.forEach { vip ->
                    RowItem(
                        title = vip.pattern,
                        subtitle = vip.note.ifBlank { null },
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Switch(
                                    checked = vip.enabled,
                                    onCheckedChange = { v ->
                                        scope.launch {
                                            AppGraph.repo.ruleDao.upsert(vip.copy(enabled = v))
                                        }
                                    }
                                )
                                TextButton(onClick = {
                                    scope.launch { AppGraph.repo.ruleDao.delete(vip) }
                                }) { Text("Remove") }
                            }
                        }
                    )
                }
                var newVip by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = newVip,
                    onValueChange = { newVip = it },
                    label = { Text("A name, or part of one") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val v = newVip.trim()
                        if (v.isNotEmpty()) {
                            scope.launch { AppGraph.repo.ruleDao.upsert(Vip(pattern = v)) }
                            newVip = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add") }
            }

            SectionHeader("Apps that are always allowed")
            Panel {
                Text(
                    "No mode ever stops these, on top of the built-in essentials. " +
                        "For the things that are neither distraction nor emergency and " +
                        "still need to run whenever they like.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val allowed by AppGraph.repo.settings.alwaysAllowed
                    .collectAsState(initial = emptySet())
                var appQuery by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = appQuery,
                    onValueChange = { appQuery = it },
                    label = { Text("Find an app") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val apps = remember { AppList.all(context, withIcons = true) }
                val shown = remember(appQuery, apps, allowed) {
                    val on = apps.filter { it.packageName in allowed }
                    val rest = apps.filter { it.packageName !in allowed }
                        .filter { appQuery.isBlank() || it.label.contains(appQuery, ignoreCase = true) }
                        .take(30)
                    on + rest
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEach { app ->
                        val on = app.packageName in allowed
                        FilterChip(
                            selected = on,
                            onClick = {
                                scope.launch {
                                    AppGraph.repo.settings.setAlwaysAllowed(
                                        if (on) allowed - app.packageName
                                        else allowed + app.packageName
                                    )
                                }
                            },
                            label = { Text(app.label) },
                            leadingIcon = { AppIcon(app.icon) }
                        )
                    }
                }
            }

            SectionHeader("Leaving on time")
            Panel {
                val enabled by AppGraph.repo.settings.commuteEnabled.collectAsState(initial = false)
                Text(
                    "For calendar events that have a location, works backwards from " +
                        "the start time and tells you when to set off, with a button " +
                        "that opens directions. It offers; it never starts navigation " +
                        "on its own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SwitchRow(
                    title = "Tell me when to leave",
                    checked = enabled,
                    onChange = { v ->
                        scope.launch {
                            AppGraph.repo.settings.setCommuteEnabled(v)
                            CommuteScheduler(context).scheduleNext()
                        }
                    }
                )
                if (enabled) {
                    val arrive by AppGraph.repo.settings.arriveEarlyMinutes.collectAsState(initial = 10L)
                    val ready by AppGraph.repo.settings.getReadyMinutes.collectAsState(initial = 5L)
                    val travel by AppGraph.repo.settings.defaultTravelMinutes.collectAsState(initial = 20L)
                    MinutesField("Be there this many minutes early", arrive) { v ->
                        scope.launch {
                            AppGraph.repo.settings.setArriveEarlyMinutes(v)
                            CommuteScheduler(context).scheduleNext()
                        }
                    }
                    MinutesField("Warn me this long before setting off", ready) { v ->
                        scope.launch {
                            AppGraph.repo.settings.setGetReadyMinutes(v)
                            CommuteScheduler(context).scheduleNext()
                        }
                    }
                    MinutesField("Assume the drive takes this long", travel) { v ->
                        scope.launch {
                            AppGraph.repo.settings.setDefaultTravelMinutes(v)
                            CommuteScheduler(context).scheduleNext()
                        }
                    }
                    Text(
                        "The drive time is a flat guess, not live traffic. Reading real " +
                            "traffic needs a routing API key, which is a deliberate " +
                            "omission rather than an oversight.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionHeader("Calendars on this phone")
            Panel {
                Text(
                    "Only calendars switched on here have their events on the phone " +
                        "at all, which is what this app and every widget read. Google " +
                        "Calendar no longer exposes this, so a calendar can look enabled " +
                        "there and still be missing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                var calTick by remember { mutableStateOf(0) }
                val calendars by produceState(initialValue = emptyList<CalRow>(), calTick) {
                    val src = AppGraph.scheduler.calendar
                    value = src.calendars()
                        .map { CalRow(it.id, it.name, it.account, it.syncEvents, src.eventCount(it.id)) }
                        .sortedWith(compareByDescending<CalRow> { it.synced }.thenBy { it.name.lowercase() })
                }
                if (calendars.isEmpty()) {
                    Text(
                        "No calendars found. Grant calendar access, or see the Now tab.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                calendars.forEach { cal ->
                    RowItem(
                        title = cal.name,
                        subtitle = buildString {
                            append(cal.account)
                            if (cal.synced) append(" · ${cal.events} events in the next fortnight")
                        },
                        trailing = {
                            Switch(
                                checked = cal.synced,
                                onCheckedChange = { on ->
                                    scope.launch {
                                        AppGraph.scheduler.calendar.setSynced(cal.id, on)
                                        calTick++
                                        AppGraph.scheduler.reevaluate("calendar sync changed")
                                    }
                                }
                            )
                        }
                    )
                }
            }

            SectionHeader("Events worth noticing")
            Panel {
                Text(
                    "Events whose title matches this are drawn bold on the home " +
                        "screen, with a marked dot. Default is anything containing " +
                        "\"work\". A plain word is fine; it is matched as a regular " +
                        "expression, ignoring case.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val current by AppGraph.repo.settings.agendaHighlight
                    .collectAsState(initial = SettingsStore.DEFAULT_HIGHLIGHT)
                var draft by remember(current) { mutableStateOf(current) }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Highlight events matching") },
                    singleLine = true,
                    isError = runCatching { Regex(draft) }.isFailure,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (runCatching { Regex(draft) }.isSuccess) {
                            scope.launch { AppGraph.repo.settings.setAgendaHighlight(draft.trim()) }
                        }
                    },
                    enabled = runCatching { Regex(draft) }.isSuccess,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save") }
            }

            SectionHeader("From your calendar")
            Panel {
                Text(
                    "Checked top to bottom. The first match wins, and a shorter event " +
                        "beats a longer one it sits inside.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                calendarRules.forEach { rule ->
                    RowItem(
                        title = rule.titlePattern?.takeIf { it.isNotBlank() }
                            ?: "Any busy event",
                        subtitle = "${modeName(rule.modeId)} · ${rule.note}",
                        trailing = {
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { v ->
                                    scope.launch {
                                        AppGraph.repo.ruleDao.upsert(rule.copy(enabled = v))
                                    }
                                }
                            )
                        }
                    )
                }
            }

            SectionHeader("By time of day")
            Panel {
                timeRules.forEach { rule ->
                    RowItem(
                        title = "%02d:%02d to %02d:%02d".format(
                            rule.startMinute / 60, rule.startMinute % 60,
                            rule.endMinute / 60, rule.endMinute % 60
                        ),
                        subtitle = "${modeName(rule.modeId)} · ${daysLabel(rule.daysMask)}" +
                            rule.note.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                        trailing = {
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { v ->
                                    scope.launch {
                                        AppGraph.repo.ruleDao.upsert(rule.copy(enabled = v))
                                    }
                                }
                            )
                        }
                    )
                }
            }

            SectionHeader("Sorting notifications")
            Panel {
                Text(
                    "Instagram has no API for messages or stories, so these read the " +
                        "notifications it already posts. A notification with a reply box " +
                        "is treated as a direct message regardless of what it says, which " +
                        "is what catches DMs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                notifRules.forEach { rule ->
                    RowItem(
                        title = rule.note.ifBlank { rule.pattern.take(48) },
                        subtitle = "${rule.packageName ?: "any app"} -> ${rule.target.label}",
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { v ->
                                        scope.launch {
                                            AppGraph.repo.ruleDao.upsert(rule.copy(enabled = v))
                                        }
                                    }
                                )
                                TextButton(onClick = {
                                    scope.launch { AppGraph.repo.ruleDao.delete(rule) }
                                }) { Text("Remove") }
                            }
                        }
                    )
                }

                var pattern by remember { mutableStateOf("") }
                var pkg by remember { mutableStateOf("") }
                var target by remember { mutableStateOf(NotifClass.PROMO) }
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("Text to match, regular expression") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pkg,
                    onValueChange = { pkg = it },
                    label = { Text("Package name, or blank for any app") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NotifClass.entries.forEach { cls ->
                        FilterChip(
                            selected = target == cls,
                            onClick = { target = cls },
                            label = { Text(cls.label) }
                        )
                    }
                }
                Button(
                    onClick = {
                        val p = pattern.trim()
                        if (p.isNotEmpty() && runCatching { Regex(p) }.isSuccess) {
                            scope.launch {
                                AppGraph.repo.ruleDao.upsert(
                                    NotifRule(
                                        packageName = pkg.trim().ifBlank { null },
                                        pattern = p,
                                        target = target,
                                        priority = 160,
                                        note = "Yours"
                                    )
                                )
                            }
                            pattern = ""
                            pkg = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add rule") }
            }
        }
    }
}

private fun daysLabel(mask: Int): String {
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val on = names.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }
    return when {
        on.size == 7 -> "every day"
        on.isEmpty() -> "never"
        else -> on.joinToString(" ")
    }
}

/** One row of the calendar list, resolved off the main thread. */
private data class CalRow(
    val id: Long,
    val name: String,
    val account: String,
    val synced: Boolean,
    val events: Int
)

@Composable
private fun MinutesField(label: String, value: Long, onChange: (Long) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next.filter { it.isDigit() }.take(3)
            text.toLongOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}
