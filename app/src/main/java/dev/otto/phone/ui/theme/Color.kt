package dev.otto.phone.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Every token as a Compose colour. Material's slots carry the common ones; the rest (dim, faint,
 *  ok, warn, bad, clay accent, acc3, line-soft, card2) are only here. */
@Immutable
data class OttoColors(
    val bg: Color, val bg2: Color, val card: Color, val card2: Color,
    val line: Color, val lineSoft: Color,
    val ink: Color, val dim: Color, val faint: Color,
    val acc: Color, val acc3: Color, val accSoft: Color,
    val ok: Color, val warn: Color, val bad: Color,
    val accent: Color, val onAccent: Color,
    val scrim: Color,
    val dark: Boolean,
) {
    companion object {
        fun from(p: Palette) = OttoColors(
            bg = Color(p.bg), bg2 = Color(p.bg2), card = Color(p.card), card2 = Color(p.card2),
            line = Color(p.line), lineSoft = Color(p.lineSoft),
            ink = Color(p.ink), dim = Color(p.dim), faint = Color(p.faint),
            acc = Color(p.acc), acc3 = Color(p.acc3), accSoft = Color(p.accSoft),
            ok = Color(p.ok), warn = Color(p.warn), bad = Color(p.bad),
            accent = Color(p.accent), onAccent = Color(p.onAccent),
            scrim = Color(p.scrim),
            dark = p.dark,
        )
    }
}

val LocalOttoColors = staticCompositionLocalOf { OttoColors.from(Palettes.Studio) }

/** The E1 slot map. No tints: tonal elevation is off everywhere, so depth is a tone step and a
 *  1 dp hairline, never a lighter overlay. */
fun colorSchemeOf(c: OttoColors): ColorScheme {
    val build = if (c.dark) ::darkScheme else ::lightScheme
    return build(c)
}

private fun darkScheme(c: OttoColors) = darkColorScheme(
    primary = c.acc, onPrimary = c.bg, primaryContainer = c.accSoft, onPrimaryContainer = c.ink, inversePrimary = c.bg,
    secondary = c.acc3, onSecondary = c.bg, secondaryContainer = c.accSoft, onSecondaryContainer = c.ink,
    tertiary = c.accent, onTertiary = c.onAccent, tertiaryContainer = c.accSoft, onTertiaryContainer = c.ink,
    background = c.bg, onBackground = c.ink,
    surface = c.bg2, onSurface = c.ink, surfaceVariant = c.card, onSurfaceVariant = c.dim,
    surfaceTint = Color.Transparent, inverseSurface = c.ink, inverseOnSurface = c.bg,
    error = c.bad, onError = c.bg, errorContainer = c.accSoft, onErrorContainer = c.bad,
    outline = c.line, outlineVariant = c.lineSoft, scrim = c.scrim,
    surfaceBright = c.card, surfaceDim = c.bg,
    surfaceContainerLowest = c.card2, surfaceContainerLow = c.bg2, surfaceContainer = c.card,
    surfaceContainerHigh = c.card, surfaceContainerHighest = c.card,
)

private fun lightScheme(c: OttoColors) = lightColorScheme(
    primary = c.acc, onPrimary = c.bg, primaryContainer = c.accSoft, onPrimaryContainer = c.ink, inversePrimary = c.bg,
    secondary = c.acc3, onSecondary = c.bg, secondaryContainer = c.accSoft, onSecondaryContainer = c.ink,
    tertiary = c.accent, onTertiary = c.onAccent, tertiaryContainer = c.accSoft, onTertiaryContainer = c.ink,
    background = c.bg, onBackground = c.ink,
    surface = c.bg2, onSurface = c.ink, surfaceVariant = c.card, onSurfaceVariant = c.dim,
    surfaceTint = Color.Transparent, inverseSurface = c.ink, inverseOnSurface = c.bg,
    error = c.bad, onError = c.bg, errorContainer = c.accSoft, onErrorContainer = c.bad,
    outline = c.line, outlineVariant = c.lineSoft, scrim = c.scrim,
    surfaceBright = c.card, surfaceDim = c.bg,
    surfaceContainerLowest = c.card2, surfaceContainerLow = c.bg2, surfaceContainer = c.card,
    surfaceContainerHigh = c.card, surfaceContainerHighest = c.card,
)
