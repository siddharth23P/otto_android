package dev.otto.phone.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The typeface says who is speaking: serif is Otto, mono is you and every number, model and id,
 *  sans is the UI. Every family is named here once; nothing else spells one. */
object OttoFonts {
    val mono: FontFamily = FontFamily.Monospace
    val serif: FontFamily = FontFamily.Serif
    val sans: FontFamily = FontFamily.Default
}

/** Text styles with their token colour baked in. Eyebrows and buttons are uppercase: pass the text
 *  through `caps`, since a TextStyle cannot transform case. */
@Immutable
data class OttoType(
    /** Otto's answer: serif 17 / 1.72, ink. */
    val answer: TextStyle,
    /** What you typed: mono 14 / 1.7, dim, drawn with a 1 dp start rule and 14 dp padding. */
    val user: TextStyle,
    /** Author, time, model, cost: mono 11.5, faint. */
    val meta: TextStyle,
    val composer: TextStyle,
    val chip: TextStyle,
    /** Section labels: mono 11.5, 0.13em, faint, followed by a hairline. */
    val eyebrow: TextStyle,
    /** Mono 12.5, 0.12em, as the label on an ink fill. */
    val button: TextStyle,
    /** Big numbers: mono 29, tabular figures. */
    val stat: TextStyle,
    /** Thinking/trace lines: mono 12, faint. */
    val trace: TextStyle,
    val ui: TextStyle,
    val wordmark: TextStyle,
) {
    companion object {
        fun caps(text: String): String = text.uppercase(java.util.Locale.ROOT)
    }
}

fun ottoType(c: OttoColors): OttoType {
    val mono = TextStyle(fontFamily = OttoFonts.mono)
    return OttoType(
        answer = TextStyle(fontFamily = OttoFonts.serif, fontSize = TypeScale.ANSWER.sp, lineHeight = (TypeScale.ANSWER * TypeScale.ANSWER_LINE).sp, color = c.ink),
        user = mono.copy(fontSize = TypeScale.USER.sp, lineHeight = (TypeScale.USER * TypeScale.USER_LINE).sp, color = c.dim),
        meta = mono.copy(fontSize = TypeScale.META.sp, color = c.faint),
        composer = mono.copy(fontSize = TypeScale.COMPOSER.sp, color = c.ink),
        chip = mono.copy(fontSize = TypeScale.CHIP.sp, color = c.dim),
        eyebrow = mono.copy(fontSize = TypeScale.EYEBROW.sp, letterSpacing = TypeScale.EYEBROW_TRACKING.em, color = c.faint),
        button = mono.copy(fontSize = TypeScale.BUTTON.sp, letterSpacing = TypeScale.BUTTON_TRACKING.em, color = c.bg),
        stat = mono.copy(fontSize = TypeScale.STAT.sp, fontFeatureSettings = "tnum", color = c.ink),
        trace = mono.copy(fontSize = TypeScale.TRACE.sp, color = c.faint),
        ui = TextStyle(fontFamily = OttoFonts.sans, fontSize = TypeScale.UI.sp, color = c.ink),
        wordmark = mono.copy(fontSize = TypeScale.WORDMARK.sp, letterSpacing = TypeScale.WORDMARK_TRACKING.em, fontWeight = FontWeight.SemiBold, color = c.ink),
    )
}

val LocalOttoType = staticCompositionLocalOf { ottoType(OttoColors.from(Palettes.Studio)) }

/** Material's scale in the same families, for components that read MaterialTheme.typography:
 *  headlines in the serif, labels in the mono, body in the system sans. */
fun materialTypography(): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = OttoFonts.serif),
        displayMedium = base.displayMedium.copy(fontFamily = OttoFonts.serif),
        displaySmall = base.displaySmall.copy(fontFamily = OttoFonts.serif),
        headlineLarge = base.headlineLarge.copy(fontFamily = OttoFonts.serif),
        headlineMedium = base.headlineMedium.copy(fontFamily = OttoFonts.serif),
        headlineSmall = base.headlineSmall.copy(fontFamily = OttoFonts.serif),
        titleLarge = base.titleLarge.copy(fontFamily = OttoFonts.sans),
        titleMedium = base.titleMedium.copy(fontFamily = OttoFonts.sans),
        titleSmall = base.titleSmall.copy(fontFamily = OttoFonts.sans),
        bodyLarge = base.bodyLarge.copy(fontFamily = OttoFonts.sans),
        bodyMedium = base.bodyMedium.copy(fontFamily = OttoFonts.sans),
        bodySmall = base.bodySmall.copy(fontFamily = OttoFonts.mono, fontSize = TypeScale.TRACE.sp),
        labelLarge = base.labelLarge.copy(fontFamily = OttoFonts.mono, fontSize = TypeScale.BUTTON.sp, letterSpacing = TypeScale.BUTTON_TRACKING.em),
        labelMedium = base.labelMedium.copy(fontFamily = OttoFonts.mono, fontSize = TypeScale.TRACE.sp),
        labelSmall = base.labelSmall.copy(fontFamily = OttoFonts.mono, fontSize = TypeScale.META.sp),
    )
}
