package dev.jaronwilson.modes.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import dev.jaronwilson.modes.core.repo.SettingsStore
import dev.jaronwilson.modes.schedule.AgendaOrder
import dev.jaronwilson.modes.schedule.LocationGate
import dev.jaronwilson.modes.core.model.NotifRule
import dev.jaronwilson.modes.core.model.Vip
import dev.jaronwilson.modes.commute.CommuteScheduler
import dev.jaronwilson.modes.commute.Destination
import dev.jaronwilson.modes.commute.Destinations
import dev.jaronwilson.modes.launcher.AppList
import dev.jaronwilson.modes.ui.AppIcon
import dev.jaronwilson.modes.ui.AppPicker
import dev.jaronwilson.modes.ui.PickerPalette
import dev.jaronwilson.modes.core.model.HomeStyle
import dev.jaronwilson.modes.launcher.Edge
import dev.jaronwilson.modes.launcher.EdgePanels
import dev.jaronwilson.modes.launcher.EdgeTarget
import dev.jaronwilson.modes.ui.GhostButton
import dev.jaronwilson.modes.ui.Panel
import dev.jaronwilson.modes.ui.PrimaryButton
import dev.jaronwilson.modes.ui.Prose
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
    val places by AppGraph.repo.placeDao.observeAll().collectAsState(initial = emptyList())

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
                val apps = remember { AppList.all(context, withIcons = true) }
                AppPicker(
                    apps = apps,
                    style = HomeStyle.ICONS,
                    query = appQuery,
                    onQueryChange = { appQuery = it },
                    selected = allowed,
                    palette = PickerPalette.APP,
                    limit = 30,
                    placeholder = "Find an app",
                    onPick = { app ->
                        val on = app.packageName in allowed
                        scope.launch {
                            AppGraph.repo.settings.setAlwaysAllowed(
                                if (on) allowed - app.packageName else allowed + app.packageName
                            )
                        }
                    }
                )
            }

            SectionHeader("Screen edges")
            Panel {
                Prose(
                    "Swipe in from the very edge of the home screen. An app is " +
                        "launched; a site opens in a panel over the home screen, so " +
                        "checking one number does not mean leaving a browser tab open."
                )
                Edge.entries.forEach { edge ->
                    val target by (if (edge == Edge.LEFT) AppGraph.repo.settings.leftEdge
                    else AppGraph.repo.settings.rightEdge).collectAsState(initial = null)
                    var draft by remember(target) {
                        mutableStateOf(
                            when (val t = target) {
                                is EdgeTarget.Site -> t.url
                                else -> ""
                            }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (edge == Edge.LEFT) "SWIPE IN FROM THE LEFT" else "SWIPE IN FROM THE RIGHT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        when (val t = target) {
                            is EdgeTarget.App -> AppList.label(context, t.packageName)
                            is EdgeTarget.Site -> t.title + "  " + t.url
                            null -> "Nothing yet"
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("A site, e.g. finance.jaronwilson.dev") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(
                            text = "Use this site",
                            enabled = draft.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    AppGraph.repo.settings.setEdge(
                                        edge,
                                        EdgeTarget.Site(
                                            EdgePanels.normalise(draft),
                                            EdgePanels.hostOf(draft)
                                        )
                                    )
                                }
                            }
                        )
                        GhostButton(
                            text = "Clear",
                            onClick = { scope.launch { AppGraph.repo.settings.setEdge(edge, null) } }
                        )
                    }
                    var appQ by remember { mutableStateOf("") }
                    AppPicker(
                        apps = remember { AppList.all(context, withIcons = true) },
                        style = HomeStyle.ICONS,
                        query = appQ,
                        onQueryChange = { appQ = it },
                        selected = setOfNotNull((target as? EdgeTarget.App)?.packageName),
                        palette = PickerPalette.APP,
                        limit = 12,
                        placeholder = "or pick an app",
                        onPick = { app ->
                            scope.launch {
                                AppGraph.repo.settings.setEdge(edge, EdgeTarget.App(app.packageName))
                            }
                        }
                    )
                }
            }

            SectionHeader("Tap to share")
            Panel {
                Prose(
                    "Makes the phone read like an NFC tag holding one link. Tap it to " +
                        "another phone and it sees your portfolio, with nothing to " +
                        "install at the other end. Read only: nobody can write to your " +
                        "phone by touching it."
                )
                val tapUrl by AppGraph.repo.settings.tapCardUrl.collectAsState(initial = "")
                var tapDraft by remember(tapUrl) { mutableStateOf(tapUrl) }
                OutlinedTextField(
                    value = tapDraft,
                    onValueChange = { tapDraft = it },
                    label = { Text("jaronwilson.org, or a LinkedIn URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                PrimaryButton(
                    text = "Save",
                    enabled = tapDraft.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            AppGraph.repo.settings.setTapCardUrl(EdgePanels.normalise(tapDraft))
                        }
                    }
                )
                Prose(
                    "Android removed Beam, so a tap cannot push a link by itself any " +
                        "more. Pretending to be a tag is what still works, and works " +
                        "with readers too."
                )
                GhostButton(
                    text = "NFC settings",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runCatching {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NFC_SETTINGS))
                        }
                    }
                )
            }

            SectionHeader("Always on")
            Panel {
                Prose(
                    "No app can replace Android's always-on display: that belongs to " +
                        "the system and there is no API for it. The screensaver slot is " +
                        "the nearest thing an app is allowed to fill, and it runs for as " +
                        "long as the phone is charging or docked."
                )
                Prose("Modes puts the time, the mode and what is next on a black screen, dimmed.")
                GhostButton(
                    text = "Choose the screen saver",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent("android.settings.DREAM_SETTINGS")
                            )
                        }
                    }
                )
            }

            SectionHeader("Places worth one tap")
            Panel {
                Text(
                    "Starts directions straight away, no searching. Google Maps keeps " +
                        "its own Home and Work and gives no way to read them, so type " +
                        "each once here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val places by AppGraph.repo.settings.destinations.collectAsState(initial = emptyList())
                places.forEach { place ->
                    RowItem(
                        title = place.label,
                        subtitle = place.query,
                        trailing = {
                            Row {
                                TextButton(onClick = {
                                    runCatching {
                                        context.startActivity(
                                            CommuteScheduler.navigationIntentAnyApp(place.query)
                                        )
                                    }
                                }) { Text("Test") }
                                TextButton(onClick = {
                                    scope.launch {
                                        AppGraph.repo.settings.setDestinations(
                                            Destinations.remove(places, place.label)
                                        )
                                    }
                                }) { Text("Remove") }
                            }
                        }
                    )
                }
                var label by remember { mutableStateOf("") }
                var address by remember { mutableStateOf("") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Destinations.SUGGESTED.filter { s -> places.none { it.label.equals(s, true) } }
                        .forEach { suggestion ->
                            FilterChip(
                                selected = label == suggestion,
                                onClick = { label = suggestion },
                                label = { Text(suggestion) }
                            )
                        }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name, e.g. School") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Address, or whatever you would search for") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = label.isNotBlank() && address.isNotBlank(),
                    onClick = {
                        scope.launch {
                            AppGraph.repo.settings.setDestinations(
                                Destinations.upsert(places, Destination(label.trim(), address.trim()))
                            )
                        }
                        label = ""
                        address = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add") }
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

            SectionHeader("Getting to work")
            Panel {
                val workAlarms by AppGraph.repo.settings.workAlarmsEnabled
                    .collectAsState(initial = false)
                Text(
                    "For your next event that says work: an alarm an hour before it " +
                        "starts, another thirty minutes before, and the thirty-minute " +
                        "one opens the drive there. Set a Work place under \"Places worth " +
                        "one tap\" for where it navigates, or it uses the event's own " +
                        "location.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SwitchRow(
                    title = "Alarms before work, then open the map",
                    checked = workAlarms,
                    onChange = { v ->
                        scope.launch {
                            AppGraph.repo.settings.setWorkAlarmsEnabled(v)
                            dev.jaronwilson.modes.commute.WorkRunUpScheduler(context).scheduleNext()
                        }
                    }
                )
                if (workAlarms) {
                    Text(
                        "The thirty-minute alarm can open Maps on its own only if " +
                            "Android lets it. If it does not on your phone, its " +
                            "notification still opens the drive in one tap.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionHeader("The day, on your home screen")
            Panel {
                val showTomorrow by AppGraph.repo.settings.agendaShowTomorrow
                    .collectAsState(initial = true)
                val todayLimit by AppGraph.repo.settings.agendaTodayLimit
                    .collectAsState(initial = 4)
                Text(
                    "How much of the day the black home screen shows. What is happening " +
                        "now or next is always there; the rest is up to you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Today", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        0 to "Just now/next", 3 to "3", 5 to "5", 8 to "All"
                    ).forEach { (n, label) ->
                        FilterChip(
                            selected = todayLimit == n,
                            onClick = { scope.launch { AppGraph.repo.settings.setAgendaTodayLimit(n) } },
                            label = { Text(label) }
                        )
                    }
                }
                SwitchRow(
                    title = "Show tomorrow too",
                    checked = showTomorrow,
                    onChange = { v -> scope.launch { AppGraph.repo.settings.setAgendaShowTomorrow(v) } }
                )
            }

            SectionHeader("Calendars on this phone")
            Panel {
                Text(
                    "Only calendars switched on here have their events on the phone " +
                        "at all, which is what this app and every widget read. Google " +
                        "Calendar no longer exposes this, so one can look enabled there " +
                        "and still be missing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Order decides which wins when two events start at the same minute, " +
                        "and which one the headline shows. Dots match the colours you " +
                        "gave them in Google Calendar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                var calTick by remember { mutableStateOf(0) }
                val priority by AppGraph.repo.settings.calendarPriority
                    .collectAsState(initial = emptyList())
                val calendars by produceState(initialValue = emptyList<CalRow>(), calTick, priority) {
                    val src = AppGraph.scheduler.calendar
                    val all = src.calendars()
                        .map { CalRow(it.id, it.name, it.account, it.syncEvents, src.eventCount(it.id), it.color) }
                    // Synced first, in your stated order, then the rest.
                    val ranked = all.filter { it.synced }
                        .sortedWith(
                            compareBy({ AgendaOrder.rank(it.id, priority) }, { it.name.lowercase() })
                        )
                    value = ranked + all.filterNot { it.synced }.sortedBy { it.name.lowercase() }
                }

                fun move(row: CalRow, delta: Int) {
                    val current = calendars.filter { it.synced }.map { it.id }.toMutableList()
                    val from = current.indexOf(row.id)
                    val to = from + delta
                    if (from < 0 || to !in current.indices) return
                    current.add(to, current.removeAt(from))
                    scope.launch {
                        AppGraph.repo.settings.setCalendarPriority(current)
                        calTick++
                    }
                }

                if (calendars.isEmpty()) {
                    Text(
                        "No calendars found. Grant calendar access, or see the Now tab.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                val syncedIds = calendars.filter { it.synced }.map { it.id }
                calendars.forEach { cal ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    if (cal.color == 0) MaterialTheme.colorScheme.outline
                                    else Color(cal.color).copy(alpha = 1f)
                                )
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cal.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text(
                                if (cal.synced) "${cal.events} events in the next fortnight"
                                else "not on this phone",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (cal.synced && syncedIds.size > 1) {
                            val atTop = syncedIds.firstOrNull() == cal.id
                            TextButton(
                                enabled = !atTop,
                                onClick = {
                                    // Straight to the front: nudging one calendar
                                    // up past a dozen others is not an interaction.
                                    scope.launch {
                                        AppGraph.repo.settings.setCalendarPriority(
                                            listOf(cal.id) + syncedIds.filterNot { it == cal.id }
                                        )
                                        calTick++
                                    }
                                }
                            ) { Text(if (atTop) "top" else "to top") }
                            TextButton(
                                enabled = syncedIds.indexOf(cal.id) > 0,
                                onClick = { move(cal, -1) }
                            ) { Text("up") }
                        }
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
                        "beats a longer one it sits inside. Restrict a rule to one " +
                        "calendar so a \"work party\" on your personal calendar cannot " +
                        "switch you into Work.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val ruleCalendars by produceState(initialValue = emptyList<Pair<Long, String>>()) {
                    value = runCatching {
                        AppGraph.scheduler.calendar.calendars()
                            .filter { it.syncEvents }
                            .map { it.id to it.name }
                    }.getOrDefault(emptyList())
                }
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
                    if (ruleCalendars.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = rule.calendarId == null,
                                onClick = {
                                    scope.launch {
                                        AppGraph.repo.ruleDao.upsert(rule.copy(calendarId = null))
                                    }
                                },
                                label = { Text("Any calendar") }
                            )
                            ruleCalendars.forEach { (id, name) ->
                                FilterChip(
                                    selected = rule.calendarId == id,
                                    onClick = {
                                        scope.launch {
                                            AppGraph.repo.ruleDao.upsert(rule.copy(calendarId = id))
                                        }
                                    },
                                    label = { Text(name) }
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            SectionHeader("Sleep and waking")
            SleepSchedule(timeRules = timeRules)

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

            SectionHeader("Where you are")
            PlacesSection(
                places = places,
                modes = modes,
                modeName = { modeName(it) }
            )

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
    val events: Int,
    /** ARGB from Google Calendar, so the dot here matches the dot there. */
    val color: Int
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlacesSection(
    places: List<dev.jaronwilson.modes.core.model.Place>,
    modes: List<dev.jaronwilson.modes.core.model.Mode>,
    modeName: (String) -> String
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var draftName by remember { mutableStateOf("") }
    var draftMode by remember { mutableStateOf(modes.firstOrNull()?.id.orEmpty()) }
    var draftRadius by remember { mutableStateOf(150.0) }
    var draftLat by remember { mutableStateOf<Double?>(null) }
    var draftLng by remember { mutableStateOf<Double?>(null) }

    // Captures a fix once permission is settled, whether it was already granted
    // or just granted through the prompt.
    fun capture() {
        busy = true
        status = "Finding you…"
        scope.launch {
            val fix = LocationGate.oneFix(context)
            busy = false
            if (fix == null) {
                status = "Could not get a location. Try again outside or near a window."
            } else {
                draftLat = fix.latitude
                draftLng = fix.longitude
                status = "Location captured."
            }
        }
    }

    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) capture()
        else status = "Location is off. You can turn it on from the Now tab."
    }

    Panel {
        Text(
            "Save a place and pick the mode it puts you in. When the phone notices " +
                "you are there, it switches, unless a calendar event says otherwise. " +
                "A meeting still wins over a place.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        places.forEach { place ->
            RowItem(
                title = place.name,
                subtitle = "${modeName(place.modeId)} · within ${place.radiusMeters.toInt()} m",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Switch(
                            checked = place.enabled,
                            onCheckedChange = { v ->
                                scope.launch {
                                    AppGraph.repo.placeDao.upsert(place.copy(enabled = v))
                                    LocationGate.refresh(context, AppGraph.repo)
                                    AppGraph.scheduler.reevaluate("place toggled")
                                }
                            }
                        )
                        TextButton(onClick = {
                            scope.launch {
                                AppGraph.repo.placeDao.delete(place)
                                LocationGate.refresh(context, AppGraph.repo)
                                AppGraph.scheduler.reevaluate("place deleted")
                            }
                        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                }
            )
        }

        Spacer(Modifier.height(8.dp))

        if (!adding) {
            TextButton(onClick = {
                adding = true
                status = ""
                draftName = ""
                draftMode = modes.firstOrNull()?.id.orEmpty()
                draftRadius = 150.0
                draftLat = null
                draftLng = null
            }) { Text("Add a place") }
        } else {
            // Capture where you are now. No map picker: a place you set by
            // standing in it is the place you actually mean.
            TextButton(
                enabled = !busy,
                onClick = {
                    if (LocationGate.hasPermission(context)) {
                        capture()
                    } else {
                        askLocation.launch(
                            arrayOf(
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                }
            ) { Text(if (draftLat == null) "Use my location here" else "Update to here") }

            if (status.isNotBlank()) {
                Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }

            OutlinedTextField(
                value = draftName,
                onValueChange = { draftName = it.take(24) },
                label = { Text("Name, e.g. Home or Campus") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Text("Mode", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEach { mode ->
                    FilterChip(
                        selected = draftMode == mode.id,
                        onClick = { draftMode = mode.id },
                        label = { Text(mode.name) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "How close counts: ${draftRadius.toInt()} m",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(100.0, 150.0, 300.0, 500.0).forEach { r ->
                    FilterChip(
                        selected = draftRadius == r,
                        onClick = { draftRadius = r },
                        label = { Text("${r.toInt()} m") }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = draftLat != null && draftName.isNotBlank() && draftMode.isNotBlank(),
                    onClick = {
                        val lat = draftLat; val lng = draftLng
                        if (lat != null && lng != null) {
                            scope.launch {
                                AppGraph.repo.placeDao.upsert(
                                    dev.jaronwilson.modes.core.model.Place(
                                        name = draftName.trim(),
                                        latitude = lat,
                                        longitude = lng,
                                        radiusMeters = draftRadius,
                                        modeId = draftMode
                                    )
                                )
                                LocationGate.refresh(context, AppGraph.repo)
                                AppGraph.scheduler.reevaluate("place added")
                            }
                            adding = false
                        }
                    }
                ) { Text("Save place") }
                TextButton(onClick = { adding = false }) { Text("Cancel") }
            }
        }
    }
}

/**
 * Bedtime and wake time as two clocks, plus a one-tap daily alarm at wake.
 *
 * These edit the single Sleep time rule, so setting bedtime to 20:00 is the
 * same as saying "Sleep runs from 8pm". The wake alarm is handed to the phone's
 * Clock app, which is the thing that actually rings and that you already know
 * how to silence, rather than a half-built alarm of our own.
 */
@Composable
private fun SleepSchedule(timeRules: List<dev.jaronwilson.modes.core.model.TimeRule>) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val sleep = timeRules.firstOrNull { it.modeId == "sleep" }
    // Sensible starting points if the Sleep rule was ever removed.
    val bedtime = sleep?.startMinute ?: (22 * 60 + 30)
    val wake = sleep?.endMinute ?: (7 * 60)

    fun saveSleep(newStart: Int, newEnd: Int) {
        scope.launch {
            val base = sleep ?: dev.jaronwilson.modes.core.model.TimeRule(
                daysMask = 0b1111111,
                startMinute = newStart,
                endMinute = newEnd,
                modeId = "sleep",
                priority = 10,
                note = "Sleep"
            )
            AppGraph.repo.ruleDao.upsert(base.copy(startMinute = newStart, endMinute = newEnd))
        }
    }

    Panel {
        Text(
            "When Sleep runs. Bedtime is when the phone goes quiet; wake is when it " +
                "comes back. Wake before bedtime is fine, it just means the window " +
                "crosses midnight.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TimeOfDayField("Bedtime", bedtime) { m -> saveSleep(m, wake) }
        TimeOfDayField("Wake up", wake) { m -> saveSleep(bedtime, m) }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                // Handed to the Clock app: a real, daily, ringing alarm you can
                // manage there. SKIP_UI creates it without opening the app.
                val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                    .putExtra(android.provider.AlarmClock.EXTRA_HOUR, wake / 60)
                    .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, wake % 60)
                    .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Wake up")
                    .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                    .putIntegerArrayListExtra(
                        android.provider.AlarmClock.EXTRA_DAYS,
                        arrayListOf(
                            java.util.Calendar.MONDAY, java.util.Calendar.TUESDAY,
                            java.util.Calendar.WEDNESDAY, java.util.Calendar.THURSDAY,
                            java.util.Calendar.FRIDAY, java.util.Calendar.SATURDAY,
                            java.util.Calendar.SUNDAY
                        )
                    )
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Set a daily alarm at %02d:%02d".format(wake / 60, wake % 60)) }
        Text(
            "Creates a repeating alarm in your Clock app. Change the wake time and " +
                "tap again to add the new one; delete the old one in Clock.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A clock as an HH:MM field, stored as minutes past midnight. */
@Composable
private fun TimeOfDayField(label: String, minutes: Int, onChange: (Int) -> Unit) {
    var text by remember(minutes) { mutableStateOf("%02d:%02d".format(minutes / 60, minutes % 60)) }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next.filter { it.isDigit() || it == ':' }.take(5)
            val parts = text.split(":")
            if (parts.size == 2) {
                val h = parts[0].toIntOrNull()
                val m = parts[1].toIntOrNull()
                if (h != null && m != null && h in 0..23 && m in 0..59) onChange(h * 60 + m)
            }
        },
        label = { Text("$label (HH:MM)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}
