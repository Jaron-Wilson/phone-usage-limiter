package dev.jaronwilson.modes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Paper and ink. Deliberately flat and low-contrast: a settings screen that is
// pleasant to look at is a settings screen you will linger on.
private val Ink = Color(0xFFE8E4DC)
private val InkDim = Color(0xFF8A8A93)
private val Surface0 = Color(0xFF0C0C0F)
private val Surface1 = Color(0xFF17171C)
private val Accent = Color(0xFFB8A88A)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF17171C),
    secondary = InkDim,
    background = Surface0,
    onBackground = Ink,
    surface = Surface1,
    onSurface = Ink,
    surfaceVariant = Color(0xFF23232A),
    onSurfaceVariant = InkDim,
    outline = Color(0xFF3A3A44),
    error = Color(0xFFD98C7A)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6B5B3E),
    background = Color(0xFFF6F3EC),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B1F)
)

private val ModesTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Light),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Normal),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
)

@Composable
fun ModesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ModesTypography,
        content = content
    )
}
