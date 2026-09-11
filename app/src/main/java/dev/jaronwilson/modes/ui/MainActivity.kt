package dev.jaronwilson.modes.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.notify.DigestPublisher
import dev.jaronwilson.modes.ui.screens.DigestScreen
import dev.jaronwilson.modes.ui.screens.HomeLayoutScreen
import dev.jaronwilson.modes.ui.screens.ModeEditScreen
import dev.jaronwilson.modes.ui.screens.ModesScreen
import dev.jaronwilson.modes.ui.screens.NowScreen
import dev.jaronwilson.modes.ui.screens.RulesScreen
import dev.jaronwilson.modes.ui.theme.ModesTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppGraph.ensure(applicationContext)
        val startOnDigest = intent?.getBooleanExtra(DigestPublisher.EXTRA_SHOW_DIGEST, false) == true

        setContent {
            ModesTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { }
                LaunchedEffect(Unit) {
                    permissionLauncher.launch(Perms.runtimePermissions)
                }
                AppShell(startOnDigest)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppGraph.scope.launch { AppGraph.scheduler.reevaluate("app resumed") }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("now", "Now", Icons.Outlined.Schedule),
    Tab("modes", "Modes", Icons.Outlined.Tune),
    Tab("rules", "Rules", Icons.AutoMirrored.Outlined.Rule),
    Tab("digest", "Waiting", Icons.Outlined.Inbox)
)

@Composable
private fun AppShell(startOnDigest: Boolean) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination
    val heldCount by AppGraph.repo.heldDao.observePendingCount().collectAsState(initial = 0)

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEach { tab ->
                    val selected = current?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = {
                            Text(
                                if (tab.route == "digest" && heldCount > 0) "${tab.label} $heldCount"
                                else tab.label
                            )
                        }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = if (startOnDigest) "digest" else "now",
            modifier = Modifier.padding(padding)
        ) {
            composable("now") { NowScreen(onOpenModes = { nav.navigate("modes") }) }
            composable("modes") {
                ModesScreen(onEdit = { id -> nav.navigate("mode/$id") })
            }
            composable("mode/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                ModeEditScreen(
                    modeId = id,
                    onDone = { nav.popBackStack() },
                    onEditHome = { nav.navigate("home/$id") }
                )
            }
            composable("home/{id}") { entry ->
                HomeLayoutScreen(
                    modeId = entry.arguments?.getString("id").orEmpty(),
                    onDone = { nav.popBackStack() }
                )
            }
            composable("rules") { RulesScreen() }
            composable("digest") { DigestScreen() }
        }
    }
}
