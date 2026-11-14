package de.hoefer.nookbot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Farben des NookBot-Designs (seit 0.23 ruhig und warm, angelehnt an Claude Duet): Off-White statt Schwarz,
 * Bot-Blasen grau, eigene Nachrichten lachsfarben, Hauptknöpfe schwarz (dunkel: hell). Akzent nur noch sparsam.
 */
data class Pal(
    val dark: Boolean,
    val bg: Color, val card: Color, val field: Color, val line: Color, val chip: Color,
    val fg: Color, val fg2: Color, val mut: Color,
    /** Blase des Bots */ val bubble: Color,
    /** eigene Nachricht und Text darauf */ val mine: Color, val onMine: Color,
    /** Hauptknopf (schwarz bzw. im Dunklen hell) und Inhalt darauf */ val ink: Color, val onInk: Color,
)

val DarkPal = Pal(true, Color(0xFF171614), Color(0xFF22201D), Color(0xFF2A2825), Color(0xFF33302C), Color(0xFF2A2825),
    Color(0xFFEEEAE3), Color(0xFFCFCAC1), Color(0xFFA39E95),
    bubble = Color(0xFF2A2825), mine = Color(0xFFE5876D), onMine = Color(0xFF1F120D), ink = Color(0xFFEEEAE3), onInk = Color(0xFF171614))
val LightPal = Pal(false, Color(0xFFF3F1EC), Color(0xFFFFFFFF), Color(0xFFEDEBE6), Color(0xFFE6E2DA), Color(0xFFEDEBE6),
    Color(0xFF1F1E1C), Color(0xFF4A4741), Color(0xFF77736C),
    bubble = Color(0xFFE9E7E2), mine = Color(0xFFF6B9A5), onMine = Color(0xFF2A1A14), ink = Color(0xFF151413), onInk = Color(0xFFFFFFFF))

/** Überschriften in Serifenschrift (wie Duet), Text bleibt schlicht. */
val Serif = androidx.compose.ui.text.font.FontFamily.Serif

object A {
    val accent = Color(0xFFE06A4E)
    val amber = Color(0xFFF5A524)
    val green = Color(0xFF2FBF71)
    val blue = Color(0xFF3B8CFF)
    val violet = Color(0xFF9B6BFF)
    val grey = Color(0xFF8F8E95)
    /** Farbauswahl für Bots */
    val bots = listOf(0xFFFF3B4E, 0xFF3B8CFF, 0xFF2FBF71, 0xFFF5A524, 0xFF9B6BFF, 0xFF8F8E95, 0xFF14B8A6, 0xFFEC4899)
}

val LocalPal = staticCompositionLocalOf { DarkPal }

/** Kurzer Zugriff in Composables: P.bg, P.fg … */
val P: Pal @Composable get() = LocalPal.current

@Composable
fun NookTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "hell" -> false
        "dunkel" -> true
        else -> isSystemInDarkTheme()
    }
    val pal = if (dark) DarkPal else LightPal
    val scheme = if (dark) darkColorScheme(primary = A.accent, background = pal.bg, surface = pal.bg, onSurface = pal.fg, surfaceVariant = pal.field)
    else lightColorScheme(primary = A.accent, background = pal.bg, surface = pal.bg, onSurface = pal.fg, surfaceVariant = pal.field)
    CompositionLocalProvider(LocalPal provides pal) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
