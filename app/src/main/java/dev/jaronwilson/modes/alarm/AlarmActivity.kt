package dev.jaronwilson.modes.alarm

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jaronwilson.modes.ui.theme.Brand
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The full-screen face of the wake alarm, shown over the lock screen when the
 * system allows it. The sound lives in [AlarmService]; this only shows the time
 * and the two buttons, and tells the service what you chose. So even if this
 * never appears, the alarm still rings and can be dismissed from its
 * notification.
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        setContent {
            val time = remember { LocalTime.now().format(DateTimeFormatter.ofPattern("H:mm")) }
            Box(
                Modifier.fillMaxSize().background(Brand.Launcher.background),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(32.dp)
                ) {
                    Text(
                        "WAKE UP",
                        color = Brand.Launcher.accent,
                        fontSize = 14.sp,
                        letterSpacing = 3.sp,
                        fontFamily = Brand.sans
                    )
                    Text(
                        time,
                        color = Brand.Launcher.ink,
                        fontSize = 76.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = Brand.serif
                    )
                    Button(
                        onClick = {
                            AlarmService.dismiss(applicationContext)
                            finish()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Brand.Launcher.accent),
                        shape = RoundedCornerShape(28.dp),
                        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp)
                    ) { Text("Dismiss", fontSize = 18.sp, color = Brand.Launcher.background) }

                    TextButton(onClick = {
                        applicationContext.startService(
                            android.content.Intent(applicationContext, AlarmService::class.java)
                                .setAction(AlarmService.ACTION_SNOOZE)
                        )
                        finish()
                    }) {
                        Text("Snooze 9 min", color = Brand.Launcher.muted, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}
