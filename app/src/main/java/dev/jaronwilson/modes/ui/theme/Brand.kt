package dev.jaronwilson.modes.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jaronwilson.modes.R

/**
 * The same paper, ink and burnt orange as jaronwilson.org, so the app reads as
 * the same person's work when it sits on that site as a project.
 *
 * Every value here has a counterpart in that site's style.css and should move
 * with it. Nothing else in the app names a colour directly.
 */
@OptIn(ExperimentalTextApi::class)
object Brand {

    /** Light: the site by day. */
    object Light {
        val paper = Color(0xFFFAF8F4)
        val surface = Color(0xFFFFFFFF)
        val ink = Color(0xFF1A1A17)
        val muted = Color(0xFF6B6862)
        val border = Color(0xFFE8E4DC)
        val accent = Color(0xFFB3542B)
        val accentInk = Color(0xFFFFFFFF)
    }

    /** Dark: the site at night, and the app's default. */
    object Dark {
        val paper = Color(0xFF17150F)
        val surface = Color(0xFF201D16)
        val ink = Color(0xFFEDE9E0)
        val muted = Color(0xFFA39D8F)
        val border = Color(0xFF35322A)
        val accent = Color(0xFFD97A4A)
        val accentInk = Color(0xFF1A1109)
    }

    /**
     * The home screen is darker than the site's dark mode on purpose: it is
     * black so there is nothing on it to look at but the day. Type and accent
     * come from the dark palette so the two still belong together.
     */
    object Launcher {
        val background = Color.Black
        val ink = Dark.ink
        val muted = Dark.muted
        val faint = Color(0xFF4A473F)
        val accent = Dark.accent
        val surface = Color(0x14FFFFFF)
    }

    /** Fraunces carries every heading, as it does on the site. Optical size at 72. */
    val serif: FontFamily = FontFamily(
        Font(
            R.font.fraunces,
            weight = FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(
                FontVariation.weight(600),
                FontVariation.Setting("opsz", 72f)
            )
        ),
        Font(
            R.font.fraunces,
            weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(
                FontVariation.weight(400),
                FontVariation.Setting("opsz", 72f)
            )
        )
    )

    /** Inter for everything that is read rather than looked at. */
    val sans: FontFamily = FontFamily(
        Font(R.font.inter, weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.inter, weight = FontWeight.Medium,
            variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.inter, weight = FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(600)))
    )

    /** 10px cards, 8px buttons, pill chips: the site's three radii. */
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(10.dp),
        large = RoundedCornerShape(14.dp),
        extraLarge = RoundedCornerShape(999.dp)
    )

    /** The site's rhythm, in dp: gutter, section, card padding. */
    object Space {
        val gutter = 20.dp
        val section = 40.dp
        val card = 20.dp
        val row = 12.dp
    }
}
