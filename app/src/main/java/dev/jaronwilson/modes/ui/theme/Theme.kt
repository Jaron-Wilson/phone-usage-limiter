package dev.jaronwilson.modes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Brand.Dark.accent,
    onPrimary = Brand.Dark.accentInk,
    secondary = Brand.Dark.muted,
    onSecondary = Brand.Dark.paper,
    background = Brand.Dark.paper,
    onBackground = Brand.Dark.ink,
    surface = Brand.Dark.surface,
    onSurface = Brand.Dark.ink,
    surfaceVariant = Brand.Dark.surface,
    onSurfaceVariant = Brand.Dark.muted,
    outline = Brand.Dark.border,
    outlineVariant = Brand.Dark.border,
    error = Brand.Dark.accent,
    primaryContainer = Brand.Dark.accent.copy(alpha = 0.14f),
    onPrimaryContainer = Brand.Dark.accent,
    // FilterChip draws its selected state from these two. Left unset they fall
    // back to Material purple, which is the one colour the site does not own.
    secondaryContainer = Brand.Dark.accent.copy(alpha = 0.18f),
    onSecondaryContainer = Brand.Dark.ink
)

private val LightColors = lightColorScheme(
    primary = Brand.Light.accent,
    onPrimary = Brand.Light.accentInk,
    secondary = Brand.Light.muted,
    onSecondary = Brand.Light.paper,
    background = Brand.Light.paper,
    onBackground = Brand.Light.ink,
    surface = Brand.Light.surface,
    onSurface = Brand.Light.ink,
    surfaceVariant = Brand.Light.paper,
    onSurfaceVariant = Brand.Light.muted,
    outline = Brand.Light.border,
    outlineVariant = Brand.Light.border,
    error = Brand.Light.accent,
    primaryContainer = Brand.Light.accent.copy(alpha = 0.14f),
    onPrimaryContainer = Brand.Light.accent,
    secondaryContainer = Brand.Light.accent.copy(alpha = 0.16f),
    onSecondaryContainer = Brand.Light.ink
)

/**
 * The site's type scale, translated.
 *
 * Headings in Fraunces at semi-bold with tight tracking, as the site sets h1
 * through h3. Body in Inter with generous leading, matching its 1.65 line
 * height. The eyebrow is the site's small uppercase accent label, which the
 * app uses for every section heading.
 */
private val ModesTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Brand.serif, fontWeight = FontWeight.SemiBold,
        fontSize = 56.sp, lineHeight = 58.sp, letterSpacing = (-0.5).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = Brand.serif, fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.3).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Brand.serif, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontFamily = Brand.serif, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = Brand.sans, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Brand.sans, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 26.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Brand.sans, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 22.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Brand.sans, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp
    ),
    /** The eyebrow. */
    labelSmall = TextStyle(
        fontFamily = Brand.sans, fontWeight = FontWeight.SemiBold,
        fontSize = 11.5.sp, lineHeight = 16.sp, letterSpacing = 1.2.sp
    )
)

@Composable
fun ModesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ModesTypography,
        shapes = Brand.shapes,
        content = content
    )
}
