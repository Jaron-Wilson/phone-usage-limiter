package dev.jaronwilson.modes.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.notify.DigestPublisher
import dev.jaronwilson.modes.ui.Panel
import dev.jaronwilson.modes.ui.RowItem
import dev.jaronwilson.modes.ui.ScreenScaffold
import dev.jaronwilson.modes.ui.SectionHeader
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DigestScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending by AppGraph.repo.heldDao.observePending().collectAsState(initial = emptyList())
    val history by AppGraph.repo.heldDao
        .observeHistory(System.currentTimeMillis() - 24 * 60 * 60 * 1000L)
        .collectAsState(initial = emptyList())

    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    fun time(ms: Long) = fmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ScreenScaffold(
            title = if (pending.isEmpty()) "Nothing waiting" else "${pending.size} waiting",
            subtitle = "Held back by the current mode. Nothing here is lost: it arrives " +
                "in a batch at the next delivery, or now if you say so."
        ) {
            if (pending.isNotEmpty()) {
                Button(
                    onClick = {
                        scope.launch {
                            DigestPublisher(context, AppGraph.repo)
                                .releaseAll("Delivered on request")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Deliver everything now") }

                SectionHeader("Being held")
                Panel {
                    pending.forEach { item ->
                        RowItem(
                            title = item.title ?: item.appLabel,
                            subtitle = "${item.appLabel} · ${item.notifClass.label} · " +
                                time(item.postedAt) +
                                (item.text?.let { "\n$it" } ?: ""),
                            trailing = {
                                TextButton(onClick = {
                                    scope.launch {
                                        DigestPublisher(context, AppGraph.repo).release(item)
                                    }
                                }) { Text("Let through") }
                            }
                        )
                    }
                }
            }

            SectionHeader("Last 24 hours")
            Panel {
                if (history.isEmpty()) {
                    Text(
                        "Nothing has been held yet. If that stays true, your modes are " +
                            "letting everything through.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val byApp = history.groupingBy { it.appLabel }.eachCount()
                        .entries.sortedByDescending { it.value }
                    byApp.forEach { (app, count) ->
                        RowItem(title = app, subtitle = "$count held")
                    }
                }
            }
        }
    }
}
