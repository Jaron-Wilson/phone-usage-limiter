package dev.jaronwilson.modes.guard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.core.model.EventKind
import dev.jaronwilson.modes.core.repo.Stats
import dev.jaronwilson.modes.ui.theme.ModesTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The speed bump. Not a lock: a pause long enough for the reach-for-the-phone
 * reflex to finish and for you to notice you are making a choice.
 */
class InterstitialActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.ensure(applicationContext)

        val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        val modeId = intent.getStringExtra(EXTRA_MODE).orEmpty()

        setContent {
            ModesTheme {
                val label = remember(pkg) { appLabel(pkg) }
                var mode by remember { mutableStateOf<dev.jaronwilson.modes.core.model.Mode?>(null) }
                LaunchedEffect(modeId) { mode = AppGraph.repo.mode(modeId) }

                Speedbump(
                    appLabel = label,
                    modeName = mode?.name ?: "Focus",
                    waitSeconds = mode?.speedbumpSeconds ?: 10,
                    passMinutes = mode?.passMinutes ?: 5,
                    onDismiss = { goHome() },
                    onProceed = {
                        lifecycleScope.launch {
                            AppGuardService.grantPass(pkg, modeId, mode?.passMinutes ?: 5)
                            Stats.log(EventKind.PASS_GRANTED, pkg)
                            openApp(pkg)
                            finish()
                        }
                    }
                )
            }
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
        finish()
    }

    private fun openApp(pkg: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(launch)
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_BLOCKING = "blocking"

        fun launch(context: Context, pkg: String, modeId: String, blocking: Boolean) {
            context.startActivity(
                Intent(context, InterstitialActivity::class.java).apply {
                    putExtra(EXTRA_PKG, pkg)
                    putExtra(EXTRA_MODE, modeId)
                    putExtra(EXTRA_BLOCKING, blocking)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                }
            )
        }
    }
}

@Composable
private fun Speedbump(
    appLabel: String,
    modeName: String,
    waitSeconds: Int,
    passMinutes: Int,
    onDismiss: () -> Unit,
    onProceed: () -> Unit
) {
    var remaining by remember { mutableIntStateOf(waitSeconds) }
    LaunchedEffect(waitSeconds) {
        remaining = waitSeconds
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                modeName,
                color = Color(0xFF8A8A93),
                fontSize = 14.sp,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(24.dp))
            Text(
                appLabel,
                color = Color(0xFFE8E4DC),
                fontSize = 34.sp,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineLarge
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "You set this one aside for now.",
                color = Color(0xFF8A8A93),
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(48.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE8E4DC),
                    contentColor = Color.Black
                )
            ) {
                Text("Put it down")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onProceed,
                enabled = remaining <= 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (remaining > 0) "Open anyway ($remaining)"
                    else "Open anyway for $passMinutes min",
                    color = if (remaining > 0) Color(0xFF4A4A52) else Color(0xFF8A8A93)
                )
            }
        }
    }
}
