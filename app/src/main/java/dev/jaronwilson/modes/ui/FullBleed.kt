package dev.jaronwilson.modes.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Makes the dialog this is called inside cover the entire display.
 *
 * `decorFitsSystemWindows = false` is supposed to be enough and is not: the
 * dialog's own window is still positioned below the status bar, so everything
 * inside it starts a status bar's height down, however many times the padding
 * inside is removed. The strip that would not go away was this.
 *
 * FLAG_LAYOUT_NO_LIMITS is what actually lets a window extend past the bars.
 */
@Composable
fun FullBleedDialogWindow() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Pinned to the very top left, and allowed into the camera cutout,
        // which is the last sixty pixels a window is otherwise kept out of.
        window.attributes = window.attributes.apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = 0
            y = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }
}
