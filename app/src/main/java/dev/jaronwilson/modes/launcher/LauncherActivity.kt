package dev.jaronwilson.modes.launcher

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.core.model.EventKind
import dev.jaronwilson.modes.core.model.HomeRow
import dev.jaronwilson.modes.core.repo.Stats
import dev.jaronwilson.modes.core.model.resolveHomeRows
import dev.jaronwilson.modes.notify.DigestPublisher
import dev.jaronwilson.modes.schedule.CalEvent
import dev.jaronwilson.modes.ui.MainActivity
import dev.jaronwilson.modes.ui.theme.ModesTheme
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Ink = Color(0xFFD8D4CC)
private val InkBright = Color(0xFFE8E4DC)
private val InkDim = Color(0xFF6A6A73)
private val InkFaint = Color(0xFF3A3A44)
private val Accent = Color(0xFFB8A88A)

/**
 * A home screen with nothing on it but the day.
 *
 * Black. What is happening right now, what is next, then the current mode's
 * apps as words in folders. No icon grid, no wallpaper, no drawer full of
 * colour. Getting to anything else takes typing its name, which is enough
 * friction to stop an idle thumb and not enough to annoy you when you mean it.
 *
 * Setting this as your default launcher is optional, but the folders here are
 * also the mode's allow list when its guard is set to allowlist, so this screen
 * is where you say what a mode is for.
 */
class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppGraph.ensure(applicationContext)
        setContent { ModesTheme(darkTheme = true) { Home() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@Composable
private fun Home() {
    val context = LocalContext.current
    var showAll by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var openFolder by remember { mutableStateOf<Long?>(null) }

    val mode by AppGraph.repo.activeMode.collectAsState(initial = null)
    val active by AppGraph.repo.settings.active.collectAsState(
        initial = dev.jaronwilson.modes.core.repo.SettingsStore.ActiveState()
    )
    val heldCount by AppGraph.repo.heldDao.observePendingCount().collectAsState(initial = 0)
    val entries by AppGraph.repo.homeDao
        .observeForMode(mode?.id.orEmpty())
        .collectAsState(initial = emptyList())
    val folders by AppGraph.repo.folderDao.observeAll().collectAsState(initial = emptyList())

    // Rows switched off for this mode are not drawn at all. Under an allowlist
    // guard they are also not openable, so the screen stays an honest picture
    // of what the mode permits.
    val rows = remember(entries, folders) {
        resolveHomeRows(entries, folders).filter { it.entry.enabled && it.packages.isNotEmpty() }
    }

    var clock by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = LocalDateTime.now()
            delay(10_000)
        }
    }

    // Refreshed on its own clock: the calendar changes far less often than the
    // minute does, and querying the provider is not free.
    val events by produceState(initialValue = emptyList<CalEvent>()) {
        while (true) {
            val now = System.currentTimeMillis()
            val endOfDay = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            value = runCatching {
                AppGraph.scheduler.calendar.events(now - 12 * 60 * 60_000L, endOfDay)
                    .filter { !it.allDay && it.end > now }
                    .sortedBy { it.begin }
                    .take(8)
            }.getOrDefault(emptyList())
            delay(5 * 60_000L)
        }
    }

    BackHandler(enabled = showAll || openFolder != null) {
        showAll = false
        openFolder = null
        query = ""
    }

    val allApps = remember { AppList.all(context) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 28.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(36.dp))

            Text(
                clock.format(DateTimeFormatter.ofPattern("H:mm")),
                fontSize = 60.sp,
                color = InkBright,
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 60.sp)
            )
            Text(
                clock.format(DateTimeFormatter.ofPattern("EEEE d MMMM")),
                fontSize = 14.sp,
                color = InkDim
            )

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${mode?.glyph.orEmpty()} ${mode?.name ?: ""}".trim(),
                    fontSize = 13.sp,
                    letterSpacing = 2.sp,
                    color = Accent,
                    modifier = Modifier.clickable { openApp(context) }
                )
                if (active.reason.isNotBlank()) {
                    Text(
                        "  ${active.reason}",
                        fontSize = 13.sp,
                        color = InkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (heldCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "$heldCount waiting",
                    fontSize = 13.sp,
                    color = InkFaint,
                    modifier = Modifier.clickable { openApp(context, showDigest = true) }
                )
            }

            if (!showAll) {
                Spacer(Modifier.height(24.dp))
                Agenda(events)
            }

            Spacer(Modifier.height(28.dp))

            if (!showAll) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    items(rows, key = { it.entry.id }) { row ->
                        HomeRowView(
                            row = row,
                            expanded = openFolder == row.entry.id,
                            onToggle = {
                                openFolder = if (openFolder == row.entry.id) null else row.entry.id
                            },
                            onLongPress = { openApp(context, editHomeFor = mode?.id) }
                        )
                    }
                    item {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "everything else",
                            fontSize = 13.sp,
                            letterSpacing = 1.sp,
                            color = InkFaint,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAll = true }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("type a name", color = InkFaint) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                val filtered = remember(query, allApps) {
                    if (query.isBlank()) allApps
                    else allApps.filter { it.label.contains(query, ignoreCase = true) }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        AppRow(app.label) {
                            Stats.log(EventKind.APP_OPENED, app.packageName)
                            AppList.launch(context, app.packageName)
                            showAll = false
                            query = ""
                        }
                    }
                }
            }
        }
    }
}

/**
 * The day, with what matters most first: the thing happening now, large, then
 * the rest of today. Reading this should answer "what am I meant to be doing"
 * without opening anything.
 */
@Composable
private fun Agenda(events: List<CalEvent>) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val fmt = DateTimeFormatter.ofPattern("H:mm")
    fun t(ms: Long) = fmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    val current = events.firstOrNull { it.begin <= now && it.end > now }
    val upcoming = events.filter { it.begin > now }

    Column {
        when {
            current != null -> {
                Text("NOW", fontSize = 11.sp, letterSpacing = 2.sp, color = Accent)
                Spacer(Modifier.height(4.dp))
                Text(
                    current.title,
                    fontSize = 26.sp,
                    color = InkBright,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { openEvent(context, current) }
                )
                Text("until ${t(current.end)}", fontSize = 14.sp, color = InkDim)
            }
            upcoming.isNotEmpty() -> {
                val next = upcoming.first()
                Text("NEXT", fontSize = 11.sp, letterSpacing = 2.sp, color = InkDim)
                Spacer(Modifier.height(4.dp))
                Text(
                    next.title,
                    fontSize = 26.sp,
                    color = InkBright,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { openEvent(context, next) }
                )
                Text("at ${t(next.begin)}", fontSize = 14.sp, color = InkDim)
            }
            else -> {
                Text("Nothing on the calendar", fontSize = 15.sp, color = InkFaint)
            }
        }

        val rest = if (current != null) upcoming else upcoming.drop(1)
        if (rest.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                rest.take(5).forEach { event ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openEvent(context, event) }
                    ) {
                        Box(Modifier.size(4.dp).clip(CircleShape).background(InkFaint))
                        Spacer(Modifier.width(10.dp))
                        Text(t(event.begin), fontSize = 14.sp, color = InkDim, modifier = Modifier.width(48.dp))
                        Text(
                            event.title, fontSize = 14.sp, color = InkDim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeRowView(
    row: HomeRow,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current

    if (!row.isFolder) {
        val pkg = row.entry.packageName ?: return
        if (!AppList.isInstalled(context, pkg)) return
        AppRow(AppList.label(context, pkg)) {
            Stats.log(EventKind.APP_OPENED, pkg)
            AppList.launch(context, pkg)
        }
        return
    }

    val contents = remember(row.packages) {
        row.packages.filter { AppList.isInstalled(context, it) }
    }
    if (contents.isEmpty()) return

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
                .padding(vertical = 11.dp)
        ) {
            Text(
                row.name,
                fontSize = 22.sp,
                color = if (expanded) InkBright else Ink
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (expanded) "−" else "${contents.size}",
                fontSize = 13.sp,
                color = InkFaint
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 18.dp, bottom = 8.dp)) {
                contents.forEach { pkg ->
                    Text(
                        AppList.label(context, pkg),
                        fontSize = 19.sp,
                        color = InkDim,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                Stats.log(EventKind.APP_OPENED, pkg)
                                AppList.launch(context, pkg)
                            }
                            .padding(vertical = 9.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 22.sp,
        color = Ink,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp)
    )
}

private fun openApp(
    context: Context,
    showDigest: Boolean = false,
    editHomeFor: String? = null
) {
    context.startActivity(
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(DigestPublisher.EXTRA_SHOW_DIGEST, showDigest)
            .putExtra(MainActivity.EXTRA_EDIT_HOME_FOR, editHomeFor)
    )
}

private fun openEvent(context: Context, event: CalEvent) {
    val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId)
    val intent = Intent(Intent.ACTION_VIEW)
        .setData(uri)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
