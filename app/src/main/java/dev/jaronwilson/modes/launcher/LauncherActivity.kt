package dev.jaronwilson.modes.launcher

import android.app.AlarmManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.Settings
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.core.model.EventKind
import dev.jaronwilson.modes.commute.CommuteScheduler
import dev.jaronwilson.modes.core.model.HomeStyle
import dev.jaronwilson.modes.core.model.HomeRow
import dev.jaronwilson.modes.core.repo.Stats
import dev.jaronwilson.modes.core.model.Folder
import dev.jaronwilson.modes.core.model.canNest
import dev.jaronwilson.modes.core.model.resolveHomeRows
import kotlinx.coroutines.launch
import dev.jaronwilson.modes.notify.DigestPublisher
import dev.jaronwilson.modes.schedule.AgendaOrder
import dev.jaronwilson.modes.schedule.CalEvent
import dev.jaronwilson.modes.ui.MainActivity
import dev.jaronwilson.modes.ui.theme.Brand
import dev.jaronwilson.modes.ui.theme.ModesTheme
import dev.jaronwilson.modes.ui.AppPicker
import dev.jaronwilson.modes.ui.PickerPalette
import dev.jaronwilson.modes.ui.AppRowName
import dev.jaronwilson.modes.ui.tileColors
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.JulianFields

// One palette with the rest of the app, and with jaronwilson.org.
private val Ink = Brand.Launcher.ink
private val InkBright = Brand.Launcher.ink
private val InkDim = Brand.Launcher.muted
private val InkFaint = Brand.Launcher.faint
private val Accent = Brand.Launcher.accent

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Home() {
    val context = LocalContext.current
    var showAll by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Which folder is open, as a trail so a nested one can be backed out of a
    // level at a time. Empty means the home screen itself.
    var openFolder by remember { mutableStateOf<Long?>(null) }
    // A trail rather than a single id: opening a folder inside a folder has to
    // be reversible one step at a time.
    var folderTrail by remember { mutableStateOf<List<Long>>(emptyList()) }
    var editing by remember { mutableStateOf(false) }
    var openSite by remember { mutableStateOf<EdgeTarget.Site?>(null) }
    var openSiteEdge by remember { mutableStateOf(Edge.LEFT) }
    var picking by remember { mutableStateOf<Picking?>(null) }

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
    val foldersById = remember(folders) { folders.associateBy { it.id } }
    val rowsAll = remember(entries, folders) { resolveHomeRows(entries, folders) }
    val rows = remember(rowsAll) {
        rowsAll.filter { it.entry.enabled && it.packages.isNotEmpty() }
    }

    // The system's next alarm, the same one the status bar shows. Read on the
    // clock tick rather than watched: it is a cheap call and an alarm you set
    // thirty seconds ago is not urgent to display.
    val alarmManager = remember { context.getSystemService(AlarmManager::class.java) }
    var nextAlarm by remember { mutableStateOf<AlarmManager.AlarmClockInfo?>(null) }

    var clock by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = LocalDateTime.now()
            nextAlarm = runCatching { alarmManager?.nextAlarmClock }.getOrNull()
            delay(10_000)
        }
    }

    // Whether there is any calendar to read at all. "Nothing today" and "no
    // calendar is synced to this phone" need different fixes, so they must not
    // share a message.
    val calendarState by produceState(initialValue = CalendarState.OK) {
        while (true) {
            val src = AppGraph.scheduler.calendar
            value = when {
                !src.hasPermission -> CalendarState.NO_PERMISSION
                runCatching { src.calendars().isEmpty() }.getOrDefault(false) -> CalendarState.NO_CALENDARS
                else -> CalendarState.OK
            }
            delay(5 * 60_000L)
        }
    }

    // Refreshed on its own clock: the calendar changes far less often than the
    // minute does, and querying the provider is not free.
    val places by AppGraph.repo.settings.destinations.collectAsState(initial = emptyList())
    val leftEdge by AppGraph.repo.settings.leftEdge.collectAsState(initial = null)
    val rightEdge by AppGraph.repo.settings.rightEdge.collectAsState(initial = null)
    val calendarPriority by AppGraph.repo.settings.calendarPriority
        .collectAsState(initial = emptyList())
    val highlightPattern by AppGraph.repo.settings.agendaHighlight
        .collectAsState(initial = dev.jaronwilson.modes.core.repo.SettingsStore.DEFAULT_HIGHLIGHT)
    val highlight = remember(highlightPattern) {
        runCatching { Regex(highlightPattern) }.getOrNull()
    }

    val events by produceState(initialValue = emptyList<CalEvent>()) {
        while (true) {
            val now = System.currentTimeMillis()
            val endOfTomorrow = LocalDate.now().plusDays(2).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            value = runCatching {
                AppGraph.scheduler.calendar.events(now - 12 * 60 * 60_000L, endOfTomorrow)
                    .filter { it.end > now }
                    .take(60)
            }.getOrDefault(emptyList())
            delay(5 * 60_000L)
        }
    }

    BackHandler(
        enabled = showAll || openFolder != null || editing || picking != null ||
            folderTrail.isNotEmpty() || openSite != null
    ) {
        when {
            openSite != null -> openSite = null
            picking != null -> picking = null
            editing -> editing = false
            folderTrail.size > 1 -> folderTrail = folderTrail.dropLast(1)
            openFolder != null -> { openFolder = null; folderTrail = emptyList() }
            else -> {
                showAll = false
                openFolder = null
                query = ""
            }
        }
    }

    val modeId = mode?.id
    fun persistOrder(newRows: List<HomeRow>) {
        AppGraph.scope.launch {
            AppGraph.repo.homeDao.upsertAll(
                newRows.mapIndexed { i, r -> r.entry.copy(sortOrder = i) }
            )
        }
    }

    /**
     * Dropping one row onto a folder.
     *
     * An app joins the folder and its own row goes, which is the whole point:
     * the home screen gets shorter. A folder dropped on a folder nests, unless
     * that would make a loop, in which case nothing happens rather than
     * something surprising.
     */
    fun dropInto(dragged: HomeRow, target: HomeRow) {
        AppGraph.scope.launch {
            val byId = AppGraph.repo.folderDao.getAll().associateBy { it.id }
            val folder = target.folder

            if (folder != null) {
                val child = dragged.folder
                if (child != null) {
                    if (!canNest(folder, child, byId)) return@launch
                    if (child.id in folder.subFolders) return@launch
                    AppGraph.repo.folderDao.upsert(
                        folder.copy(subFolders = folder.subFolders + child.id)
                    )
                } else {
                    val pkg = dragged.entry.packageName ?: return@launch
                    if (pkg !in folder.packages) {
                        AppGraph.repo.folderDao.upsert(folder.copy(packages = folder.packages + pkg))
                    }
                }
                AppGraph.repo.homeDao.delete(dragged.entry)
                return@launch
            }

            // Two apps: a new folder holding both, taking the target's place.
            // It arrives called "Folder" and is renamed by tapping its title in
            // the overlay, which is where you are looking the moment it opens.
            val first = target.entry.packageName ?: return@launch
            val second = dragged.entry.packageName ?: return@launch
            if (first == second) return@launch
            val newId = AppGraph.repo.folderDao.upsert(
                Folder(
                    name = "Folder",
                    packages = listOf(first, second),
                    sortOrder = byId.size
                )
            )
            AppGraph.repo.homeDao.upsert(
                target.entry.copy(packageName = null, folderId = newId)
            )
            AppGraph.repo.homeDao.delete(dragged.entry)
            openFolder = newId
            folderTrail = listOf(newId)
        }
    }

    fun removeRow(row: HomeRow) {
        // Takes the row off this mode only. The folder itself, and every other
        // mode using it, are left alone.
        AppGraph.scope.launch { AppGraph.repo.homeDao.delete(row.entry) }
    }

    val iconStyle = mode?.homeStyle == HomeStyle.ICONS
    val pickerApps = remember(iconStyle) { AppList.all(context, withIcons = iconStyle) }
    // Icons are only loaded for the modes that draw them: decoding a hundred
    // launcher icons is not work a Sleep-mode home screen should ever do.
    // The overlay draws icons even for a text mode's folders, so these load
    // either way; AppIcon rasterises each one once and keeps it.
    val icons = remember {
        AppList.all(context, withIcons = true).associate { it.packageName to it.icon }
    }

    val density = LocalDensity.current
    fun fire(target: EdgeTarget?, edge: Edge) {
        when (target) {
            is EdgeTarget.App -> {
                Stats.log(EventKind.APP_OPENED, target.packageName)
                AppList.launch(context, target.packageName)
            }
            is EdgeTarget.Site -> { openSite = target; openSiteEdge = edge }
            null -> Unit
        }
    }

    // Two layers. The inner one carries the insets and the gutter and holds the
    // home screen; the outer one is the whole display, so an overlay can cover
    // it edge to edge rather than being boxed in by the gutter.
    Box(
        Modifier
            .fillMaxSize()
            .background(Brand.Launcher.background)
            .pointerInput(leftEdge, rightEdge, editing) {
                if (editing) return@pointerInput
                // Only a drag that begins within a thumb's width of an edge
                // counts, so the gesture cannot be triggered by scrolling the
                // list in the middle of the screen.
                val edgeZone = with(density) { 32.dp.toPx() }
                val travel = with(density) { 72.dp.toPx() }
                var startX = 0f
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset -> startX = offset.x; total = 0f },
                    onHorizontalDrag = { change, delta ->
                        total += delta
                        change.consume()
                    },
                    onDragEnd = {
                        val width = size.width.toFloat()
                        when {
                            startX <= edgeZone && total > travel -> fire(leftEdge, Edge.LEFT)
                            startX >= width - edgeZone && total < -travel -> fire(rightEdge, Edge.RIGHT)
                        }
                    }
                )
            }
    ) {
      Box(
          Modifier
              .fillMaxSize()
              .windowInsetsPadding(WindowInsets.safeDrawing)
              .padding(horizontal = 28.dp)
      ) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(36.dp))

            Text(
                clock.format(DateTimeFormatter.ofPattern("H:mm")),
                color = InkBright,
                style = MaterialTheme.typography.displayLarge
            )
            Text(
                clock.format(DateTimeFormatter.ofPattern("EEEE d MMMM")),
                color = InkDim,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${mode?.glyph.orEmpty()} ${mode?.name ?: ""}".trim(),
                    fontSize = 13.sp,
                    letterSpacing = 2.sp,
                    color = Accent,
                    modifier = Modifier.combinedClickable(
                        onClick = { openApp(context) },
                        onLongClick = { editing = true }
                    )
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
                Agenda(events, calendarState, highlight, calendarPriority)
            }

            Spacer(Modifier.height(28.dp))

            if (editing) {
                Spacer(Modifier.height(18.dp))
                EditBar(
                    modeName = mode?.name.orEmpty(),
                    onAddApp = { picking = Picking.App },
                    onAddFolder = { picking = Picking.Folder },
                    onDone = { editing = false }
                )
                Spacer(Modifier.height(14.dp))
            }

            if (editing && picking == null) {
                val ordered = remember(rowsAll) { rowsAll }
                if (iconStyle) {
                    EditableIconGrid(
                        rows = ordered,
                        columns = 4,
                        iconFor = { pkg -> icons[pkg] },
                        label = { row ->
                            if (row.isFolder) row.name
                            else AppList.label(context, row.entry.packageName.orEmpty())
                        },
                        onMove = { from, to -> persistOrder(ordered.moved(from, to)) },
                        onRemove = { removeRow(it) },
                        onOpen = { row -> if (row.isFolder) picking = Picking.InFolder(row.folder!!.id) },
                        onDropInto = { dragged, target -> dropInto(dragged, target) }
                    )
                } else {
                    EditableRowList(
                        rows = ordered,
                        label = { row ->
                            if (row.isFolder) row.name
                            else AppList.label(context, row.entry.packageName.orEmpty())
                        },
                        onMove = { from, to -> persistOrder(ordered.moved(from, to)) },
                        onRemove = { removeRow(it) },
                        onOpen = { row -> if (row.isFolder) picking = Picking.InFolder(row.folder!!.id) },
                        onDropInto = { dragged, target -> dropInto(dragged, target) }
                    )
                }
                Text(
                    "Hold to drag. Rest on a folder to drop it in. Tap a folder to see inside.",
                    fontSize = 12.sp,
                    color = InkFaint,
                    modifier = Modifier.padding(top = 18.dp)
                )
            } else if (editing) {
                PickerPanel(
                    picking = picking!!,
                    modeId = modeId.orEmpty(),
                    existingRows = rowsAll,
                    apps = pickerApps,
                    style = mode?.homeStyle ?: HomeStyle.TEXT,
                    onClose = { picking = null }
                )
            } else if (!showAll && iconStyle) {
                Column(Modifier.weight(1f)) {
                    IconHome(
                        rows = rows,
                        icons = icons,
                        openFolder = openFolder,
                        onToggleFolder = { rowId ->
                            val f = rows.firstOrNull { it.entry.id == rowId }?.folder
                            openFolder = f?.id
                            folderTrail = listOfNotNull(f?.id)
                        },
                        onEditHome = { editing = true },
                        onLaunch = { pkg ->
                            Stats.log(EventKind.APP_OPENED, pkg)
                            AppList.launch(context, pkg)
                        },
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        "everything else",
                        fontSize = 13.sp,
                        letterSpacing = 1.sp,
                        color = InkFaint,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAll = true }
                            .padding(vertical = 14.dp)
                    )
                }
            } else if (!showAll) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    items(rows, key = { it.entry.id }) { row ->
                        HomeRowView(
                            row = row,
                            onOpen = {
                                if (row.isFolder) {
                                    openFolder = row.folder?.id
                                    folderTrail = listOfNotNull(row.folder?.id)
                                } else {
                                    row.entry.packageName?.let { pkg ->
                                        Stats.log(EventKind.APP_OPENED, pkg)
                                        AppList.launch(context, pkg)
                                    }
                                }
                            },
                            onLongPress = { editing = true }
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
                AppPicker(
                    apps = pickerApps,
                    style = mode?.homeStyle ?: HomeStyle.TEXT,
                    query = query,
                    onQueryChange = { query = it },
                    palette = PickerPalette.LAUNCHER,
                    limit = 60,
                    emptyText = "Nothing by that name",
                    onPick = { app ->
                        Stats.log(EventKind.APP_OPENED, app.packageName)
                        AppList.launch(context, app.packageName)
                        showAll = false
                        query = ""
                    }
                )
            }

      }

        // Opened folders float over the home screen rather than unfolding in
        // it, so nothing below shifts to make room and closing puts you back
        // exactly where you were.
        val openTrail = remember(folderTrail, foldersById) {
            folderTrail.mapNotNull { foldersById[it]?.name }
        }
        EdgeWebPanel(
            site = openSite,
            edge = openSiteEdge,
            onDismiss = { openSite = null },
            onOpenInBrowser = { url ->
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                openSite = null
            }
        )

        FolderOverlay(
            visible = openFolder != null && !editing,
            folder = folderTrail.lastOrNull()?.let { foldersById[it] },
            trail = openTrail,
            style = mode?.homeStyle ?: HomeStyle.TEXT,
            foldersById = foldersById,
            iconFor = { pkg -> icons[pkg] },
            onOpenSub = { id -> folderTrail = folderTrail + id },
            onLaunch = { pkg ->
                Stats.log(EventKind.APP_OPENED, pkg)
                AppList.launch(context, pkg)
                openFolder = null
                folderTrail = emptyList()
            },
            onRename = { folder, newName ->
                AppGraph.scope.launch {
                    AppGraph.repo.folderDao.upsert(folder.copy(name = newName))
                }
            },
            onDismiss = {
                if (folderTrail.size > 1) folderTrail = folderTrail.dropLast(1)
                else { openFolder = null; folderTrail = emptyList() }
            }
        )
        }
    }
}

/**
 * The next alarm, phrased the way you would say it out loud.
 *
 * Taken from the system rather than kept here, so it is whatever the Clock app
 * has set, including a nap timer someone else's app created. Tapping opens
 * whichever app owns it.
 */
@Composable
private fun AlarmLine(alarm: AlarmManager.AlarmClockInfo) {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(alarm.triggerTime).atZone(zone)
    val today = LocalDate.now(zone)
    val whenText = when (at.toLocalDate()) {
        today -> DateTimeFormatter.ofPattern("H:mm").format(at)
        today.plusDays(1) -> DateTimeFormatter.ofPattern("H:mm").format(at) + " tomorrow"
        else -> DateTimeFormatter.ofPattern("H:mm EEE").format(at)
    }
    val hoursAway = (alarm.triggerTime - System.currentTimeMillis()) / 3_600_000

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable {
            runCatching { alarm.showIntent?.send() }
        }
    ) {
        Text("alarm", fontSize = 11.sp, letterSpacing = 2.sp, color = InkFaint)
        Spacer(Modifier.width(10.dp))
        Text(
            whenText,
            fontSize = 14.sp,
            // Something going off within the hour is worth noticing.
            color = if (hoursAway < 1) Accent else InkDim
        )
    }
}

/** Why the agenda is empty, so the screen can say something useful. */
private enum class CalendarState { OK, NO_PERMISSION, NO_CALENDARS }

/**
 * The day, with what matters most first: the thing happening now, large, then
 * the rest of today, then a quieter look at tomorrow. Reading this should
 * answer "what am I meant to be doing, and what is coming" without opening
 * anything.
 *
 * All-day entries are kept but never promoted to the headline: a deadline that
 * spans the whole day is worth seeing and is not what you are doing right now.
 */
@Composable
private fun Agenda(
    events: List<CalEvent>,
    state: CalendarState,
    highlight: Regex?,
    priority: List<Long>
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val fmt = DateTimeFormatter.ofPattern("H:mm")
    fun t(ms: Long) = fmt.format(Instant.ofEpochMilli(ms).atZone(zone))

    // Bucket by the provider's own day numbers. Doing it by timestamp puts
    // every all-day event on the wrong side of midnight west of Greenwich.
    val todayJulian = remember(events) {
        LocalDate.now().getLong(JulianFields.JULIAN_DAY).toInt()
    }
    val today = AgendaOrder.sort(
        events.filter { it.occursOn(todayJulian) }, priority, highlight
    )
    val tomorrow = AgendaOrder.sort(
        events.filter { it.occursOn(todayJulian + 1) && !it.occursOn(todayJulian) },
        priority, highlight
    )

    val current = today.firstOrNull { !it.allDay && it.begin <= now && it.end > now }
    val upcomingToday = today.filter { it.begin > now || (it.allDay && it.end > now) }
        .filter { it != current }

    Column {
        when {
            current != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EventDot(current.color, 6.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("NOW", fontSize = 11.sp, letterSpacing = 2.sp, color = Accent)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    current.title,
                    color = InkBright,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = if (highlight?.containsMatchIn(current.title) == true) {
                        FontWeight.Bold
                    } else FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { openEvent(context, current) }
                )
                Text("until ${t(current.end)}", fontSize = 14.sp, color = InkDim)
            }
            upcomingToday.any { !it.allDay } -> {
                val next = upcomingToday.first { !it.allDay }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EventDot(next.color, 6.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("NEXT", fontSize = 11.sp, letterSpacing = 2.sp, color = InkDim)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    next.title,
                    color = InkBright,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = if (highlight?.containsMatchIn(next.title) == true) {
                        FontWeight.Bold
                    } else FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { openEvent(context, next) }
                )
                Text("at ${t(next.begin)}", fontSize = 14.sp, color = InkDim)
            }
            state == CalendarState.NO_PERMISSION -> {
                Text(
                    "Calendar access is off. Tap to fix.",
                    fontSize = 15.sp, color = InkDim,
                    modifier = Modifier.clickable { openAppInfo(context) }
                )
            }
            state == CalendarState.NO_CALENDARS -> {
                Text(
                    "No calendar is synced to this phone. Tap to check account sync.",
                    fontSize = 15.sp, color = InkDim,
                    modifier = Modifier.clickable { openSyncSettings(context) }
                )
            }
            tomorrow.isNotEmpty() -> {
                Text("Nothing left today", fontSize = 15.sp, color = InkFaint)
            }
            else -> {
                Text("Nothing on the calendar", fontSize = 15.sp, color = InkFaint)
            }
        }

        val restOfToday = upcomingToday.filterNot { it == current }
            .let { list ->
                // The headline already showed the first timed one.
                val headline = list.firstOrNull { !it.allDay }
                if (current == null && headline != null) list.filter { it != headline } else list
            }

        if (restOfToday.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            EventList(restOfToday.take(4), highlight) { openEvent(context, it) }
        }

        if (tomorrow.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("TOMORROW", fontSize = 11.sp, letterSpacing = 2.sp, color = InkFaint)
            Spacer(Modifier.height(6.dp))
            EventList(tomorrow.take(4), highlight) { openEvent(context, it) }
            if (tomorrow.size > 4) {
                Text(
                    "and ${tomorrow.size - 4} more",
                    fontSize = 13.sp,
                    color = InkFaint,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun EventList(
    events: List<CalEvent>,
    highlight: Regex?,
    onClick: (CalEvent) -> Unit
) {
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("H:mm")
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        events.forEach { event ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick(event) }
            ) {
                val marked = highlight?.containsMatchIn(event.title) == true
                EventDot(event.color, if (marked) 6.dp else 5.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (event.allDay) "all day"
                    else fmt.format(Instant.ofEpochMilli(event.begin).atZone(zone)),
                    fontSize = 14.sp,
                    color = if (marked) Ink else InkDim,
                    fontWeight = if (marked) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.width(56.dp)
                )
                Text(
                    event.title,
                    fontSize = 14.sp,
                    color = if (marked) InkBright else InkDim,
                    fontWeight = if (marked) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun openAppInfo(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun openSyncSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_SYNC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * One row of the text home screen.
 *
 * Flat on purpose: a folder no longer unfolds in place, pushing everything
 * below it down the screen. It opens over the top instead, so closing puts you
 * back exactly where you were.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeRowView(
    row: HomeRow,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current
    val colors = tileColors(PickerPalette.LAUNCHER)

    if (!row.isFolder) {
        val pkg = row.entry.packageName ?: return
        if (!AppList.isInstalled(context, pkg)) return
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                .padding(vertical = 11.dp)
        ) {
            Text(
                AppList.label(context, pkg),
                fontSize = 21.sp,
                fontFamily = Brand.sans,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }

    val contents = remember(row.packages) { row.packages.filter { AppList.isInstalled(context, it) } }
    val subCount = row.folder?.subFolders?.size ?: 0
    if (contents.isEmpty() && subCount == 0) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(vertical = 11.dp)
    ) {
        Text(
            row.name,
            fontSize = 21.sp,
            fontFamily = Brand.sans,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(10.dp))
        Text("${contents.size + subCount}", fontSize = 12.sp, color = colors.faint)
    }
}

/**
 * The calendar's own colour, as Google Calendar draws it. Falls back to the
 * muted grey rather than drawing black on black.
 */
@Composable
private fun EventDot(argb: Int, size: androidx.compose.ui.unit.Dp) {
    val colour = remember(argb) { if (argb == 0) InkFaint else Color(argb).copy(alpha = 1f) }
    Box(Modifier.size(size).clip(CircleShape).background(colour))
}

@Composable
private fun AppRow(label: String, onClick: () -> Unit) {
    AppRowName(label = label, colors = tileColors(PickerPalette.LAUNCHER), onClick = onClick)
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
