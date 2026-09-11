package dev.jaronwilson.modes.ui.screens

import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.core.model.EventKind
import dev.jaronwilson.modes.core.model.NotifClass
import dev.jaronwilson.modes.core.model.UsageEvent
import dev.jaronwilson.modes.core.repo.SettingsStore
import dev.jaronwilson.modes.launcher.AppList
import dev.jaronwilson.modes.ui.Panel
import dev.jaronwilson.modes.ui.Perms
import dev.jaronwilson.modes.ui.RowItem
import dev.jaronwilson.modes.ui.ScreenScaffold
import dev.jaronwilson.modes.ui.SectionHeader
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the phone did today, in numbers you can read in ten seconds.
 *
 * Deliberately plain: counts and durations, not charts. The question this
 * screen answers is "is it working", and a number answers that.
 */
@Composable
fun StatsScreen() {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val startOfToday = remember { LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli() }
    val weekAgo = remember { startOfToday - 6 * DAY }

    val events by AppGraph.repo.eventDao.observeSince(weekAgo).collectAsState(initial = emptyList())
    val modes by AppGraph.repo.modes.collectAsState(initial = emptyList())
    val active by AppGraph.repo.settings.active.collectAsState(initial = SettingsStore.ActiveState())

    val today = remember(events) { events.filter { it.at >= startOfToday } }
    fun modeName(id: String) = modes.firstOrNull { it.id == id }?.name ?: id.ifBlank { "unknown" }
    fun label(pkg: String?) = pkg?.let { AppList.label(context, it) } ?: "?"

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenScaffold(
            title = "Stats",
            subtitle = "Today, and the week behind it. Kept for a month, then forgotten."
        ) {
            // ---- notifications ----
            val allowed = today.count { it.kind == EventKind.NOTIF_ALLOWED }
            val held = today.count { it.kind == EventKind.NOTIF_HELD }
            SectionHeader("Notifications today")
            Panel {
                Big("$held", "held back")
                Big("$allowed", "let through")
                if (held + allowed > 0) {
                    val pct = held * 100 / (held + allowed)
                    Text(
                        "$pct% of what arrived waited for a better moment.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                val byClass = today.filter { it.kind == EventKind.NOTIF_HELD }
                    .groupingBy { it.detail ?: "OTHER" }.eachCount()
                    .entries.sortedByDescending { it.value }.take(5)
                byClass.forEach { (cls, n) ->
                    val pretty = runCatching { NotifClass.valueOf(cls).label }.getOrDefault(cls)
                    RowItem(title = pretty, trailing = { Text("$n") })
                }
                val byApp = today.filter { it.kind == EventKind.NOTIF_HELD }
                    .groupingBy { it.packageName ?: "?" }.eachCount()
                    .entries.sortedByDescending { it.value }.take(5)
                if (byApp.isNotEmpty()) {
                    Text(
                        "Noisiest apps",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    byApp.forEach { (pkg, n) -> RowItem(title = label(pkg), trailing = { Text("$n") }) }
                }
            }

            // ---- time in each mode ----
            SectionHeader("Where the day went")
            Panel {
                val durations = remember(today, active) {
                    modeDurations(today, active.modeId, startOfToday, System.currentTimeMillis())
                }
                if (durations.isEmpty()) {
                    Text("No mode changes recorded yet today.", style = MaterialTheme.typography.bodyMedium)
                }
                durations.entries.sortedByDescending { it.value }.forEach { (mode, ms) ->
                    RowItem(title = modeName(mode), trailing = { Text(hm(ms)) })
                }
            }

            // ---- guard ----
            val stops = today.count { it.kind == EventKind.GUARD_STOPPED }
            val passes = today.count { it.kind == EventKind.PASS_GRANTED }
            SectionHeader("Reaching for things")
            Panel {
                Big("$stops", "times a mode stopped you")
                Big("$passes", "times you went in anyway")
                if (stops > 0) {
                    val stayedOut = stops - passes
                    Text(
                        if (stayedOut > 0) "$stayedOut times you put it down. That is the number."
                        else "Every pause got walked through today. Worth a longer pause?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                val stoppedApps = today.filter { it.kind == EventKind.GUARD_STOPPED }
                    .groupingBy { it.packageName ?: "?" }.eachCount()
                    .entries.sortedByDescending { it.value }.take(5)
                stoppedApps.forEach { (pkg, n) -> RowItem(title = label(pkg), trailing = { Text("$n") }) }
            }

            // ---- opened from home ----
            val opened = today.filter { it.kind == EventKind.APP_OPENED }
                .groupingBy { it.packageName ?: "?" }.eachCount()
                .entries.sortedByDescending { it.value }.take(6)
            if (opened.isNotEmpty()) {
                SectionHeader("Opened from the home screen")
                Panel {
                    opened.forEach { (pkg, n) -> RowItem(title = label(pkg), trailing = { Text("$n") }) }
                }
            }

            // ---- screen time, from the system ----
            SectionHeader("Screen time today")
            Panel {
                if (!Perms.hasUsageAccess(context)) {
                    Text(
                        "Android keeps per-app screen time itself. Grant usage access " +
                            "and it shows up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Grant usage access") }
                } else {
                    val screen by produceState(initialValue = emptyList<Pair<String, Long>>()) {
                        value = screenTimeToday(context, startOfToday)
                    }
                    if (screen.isEmpty()) {
                        Text("Nothing recorded yet.", style = MaterialTheme.typography.bodyMedium)
                    }
                    val total = screen.sumOf { it.second }
                    if (total > 0) Big(hm(total), "total, across apps")
                    screen.take(8).forEach { (pkg, ms) ->
                        RowItem(title = label(pkg), trailing = { Text(hm(ms)) })
                    }
                }
            }

            // ---- the week ----
            SectionHeader("Last seven days")
            Panel {
                (0..6).map { d -> startOfToday - d * DAY }.forEach { dayStart ->
                    val dayEvents = events.filter { it.at >= dayStart && it.at < dayStart + DAY }
                    val h = dayEvents.count { it.kind == EventKind.NOTIF_HELD }
                    val s = dayEvents.count { it.kind == EventKind.GUARD_STOPPED }
                    val date = java.time.Instant.ofEpochMilli(dayStart).atZone(zone).toLocalDate()
                    RowItem(
                        title = if (dayStart == startOfToday) "Today" else date.dayOfWeek.name.lowercase()
                            .replaceFirstChar { it.uppercase() },
                        subtitle = "$h held, $s stops",
                    )
                }
            }
        }
    }
}

@Composable
private fun Big(value: String, caption: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(value, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(end = 12.dp))
        Text(
            caption,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

private const val DAY = 24 * 60 * 60 * 1000L

private fun hm(ms: Long): String {
    val m = ms / 60_000
    return if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"
}

/**
 * Time spent in each mode today, reconstructed from the change log. The mode
 * before the first change is whatever that change says it replaced; the last
 * segment runs to now.
 */
internal fun modeDurations(
    todayEvents: List<UsageEvent>,
    activeModeId: String,
    startOfDay: Long,
    now: Long
): Map<String, Long> {
    val changes = todayEvents.filter { it.kind == EventKind.MODE_CHANGED }.sortedBy { it.at }
    val out = mutableMapOf<String, Long>()
    var cursor = startOfDay
    var mode = changes.firstOrNull()?.detail?.ifBlank { null } ?: activeModeId
    for (c in changes) {
        out[mode] = (out[mode] ?: 0L) + (c.at - cursor).coerceAtLeast(0)
        cursor = c.at
        mode = c.modeId
    }
    out[mode] = (out[mode] ?: 0L) + (now - cursor).coerceAtLeast(0)
    return out.filterValues { it > 0 }
}

private fun screenTimeToday(context: Context, startOfDay: Long): List<Pair<String, Long>> {
    val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
    val stats = runCatching {
        usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, System.currentTimeMillis())
    }.getOrNull() ?: return emptyList()
    return stats
        .filter { it.totalTimeInForeground > 60_000 && it.packageName != context.packageName }
        .groupBy { it.packageName }
        .map { (pkg, list) -> pkg to list.sumOf { it.totalTimeInForeground } }
        .sortedByDescending { it.second }
}
