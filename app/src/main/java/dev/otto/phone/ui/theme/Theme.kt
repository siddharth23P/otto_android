package dev.otto.phone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember

/** Studio when the system is dark, Paper when it is light, unless Settings chose one. */
@Composable
fun OttoTheme(choice: ThemeChoice = ThemeChoice.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (choice) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.STUDIO -> true
        ThemeChoice.PAPER -> false
    }
    val colors = remember(dark) { OttoColors.from(Palettes.of(dark)) }
    val type = remember(colors) { ottoType(colors) }
    val scheme = remember(colors) { colorSchemeOf(colors) }
    val typography = remember { materialTypography() }
    val reduced = rememberReducedMotion()
    CompositionLocalProvider(
        LocalOttoColors provides colors,
        LocalOttoType provides type,
        LocalReducedMotion provides reduced,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = OttoShapes.material, content = content)
    }
}

/** Reads the theme from inside it: `OttoTheme.colors.faint`, `OttoTheme.type.meta`. */
object OttoTheme {
    val colors: OttoColors
        @Composable @ReadOnlyComposable get() = LocalOttoColors.current
    val type: OttoType
        @Composable @ReadOnlyComposable get() = LocalOttoType.current
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}
