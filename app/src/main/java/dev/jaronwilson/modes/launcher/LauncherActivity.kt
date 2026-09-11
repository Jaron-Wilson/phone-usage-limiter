package dev.jaronwilson.modes.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.ui.MainActivity
import dev.jaronwilson.modes.ui.theme.ModesTheme
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A home screen with nothing on it.
 *
 * No icons, no grid, no wallpaper widgets, no swipe-up drawer full of colour.
 * Just the handful of apps the current mode says you need, as words. Getting to
 * anything else takes typing its name, which is a small enough friction to stop
 * an idle thumb and a small enough one not to be annoying when you mean it.
 *
 * Setting this as your default launcher is optional. Everything else in the app
 * works without it.
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
        // Pressing home while already home should feel like a reset, not a no-op.
        setIntent(intent)
    }
}

@Composable
private fun Home() {
    val context = LocalContext.current
    var showAll by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val mode by AppGraph.repo.activeMode.collectAsState(initial = null)
    val active by AppGraph.repo.settings.active.collectAsState(
        initial = dev.jaronwilson.modes.core.repo.SettingsStore.ActiveState()
    )
    val heldCount by AppGraph.repo.heldDao.observePendingCount().collectAsState(initial = 0)

    var clock by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = LocalDateTime.now()
            kotlinx.coroutines.delay(10_000)
        }
    }

    BackHandler(enabled = showAll) {
        showAll = false
        query = ""
    }

    val allApps = remember { AppList.all(context) }
    val homeApps = remember(mode?.homeApps) {
        mode?.homeApps.orEmpty()
            .filter { AppList.isInstalled(context, it) }
            .map { AppEntry(it, AppList.label(context, it)) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6000000))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 28.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(48.dp))

            Text(
                clock.format(DateTimeFormatter.ofPattern("H:mm")),
                fontSize = 64.sp,
                color = Color(0xFFE8E4DC),
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 64.sp)
            )
            Text(
                clock.format(DateTimeFormatter.ofPattern("EEEE d MMMM")),
                fontSize = 14.sp,
                color = Color(0xFF6A6A73)
            )

            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${mode?.glyph.orEmpty()} ${mode?.name ?: ""}".trim(),
                    fontSize = 13.sp,
                    letterSpacing = 2.sp,
                    color = Color(0xFFB8A88A),
                    modifier = Modifier.clickable {
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
                if (active.reason.isNotBlank()) {
                    Text(
                        "  ${active.reason}",
                        fontSize = 13.sp,
                        color = Color(0xFF4A4A52),
                        maxLines = 1
                    )
                }
            }

            if (heldCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "$heldCount waiting",
                    fontSize = 13.sp,
                    color = Color(0xFF4A4A52),
                    modifier = Modifier.clickable {
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                .putExtra(
                                    dev.jaronwilson.modes.notify.DigestPublisher.EXTRA_SHOW_DIGEST,
                                    true
                                )
                        )
                    }
                )
            }

            Spacer(Modifier.height(40.dp))

            if (!showAll) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(homeApps, key = { it.packageName }) { app ->
                        AppRow(app.label) { AppList.launch(context, app.packageName) }
                    }
                    item {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "everything else",
                            fontSize = 13.sp,
                            letterSpacing = 1.sp,
                            color = Color(0xFF3A3A44),
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
                    placeholder = { Text("type a name", color = Color(0xFF3A3A44)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                val filtered = remember(query, allApps) {
                    if (query.isBlank()) allApps
                    else allApps.filter { it.label.contains(query, ignoreCase = true) }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        AppRow(app.label) {
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

@Composable
private fun AppRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 22.sp,
        color = Color(0xFFD8D4CC),
        textAlign = TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp)
    )
}
