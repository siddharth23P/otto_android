package dev.otto.phone.ui.theme

import kotlin.math.pow

// The design tokens as plain numbers, with no Compose in this file, so a JVM test can hold them to
// the DataNodes web app's rules (frontend/src/theme.css, motion.css, tests/unit/tokens.spec.js).

/** Studio (dark) or Paper (light), or whatever the system says. Stored in Prefs as `pref`. */
enum class ThemeChoice(val pref: String) {
    SYSTEM("system"), STUDIO("studio"), PAPER("paper");

    companion object {
        fun fromPref(value: String?): ThemeChoice = entries.firstOrNull { it.pref == value } ?: SYSTEM
    }
}

/** theme.css v4, warm monochrome. `acc` is ink, not a colour; `accent` (clay) is the one real
 *  accent and is rationed to state marks. Every value is ARGB. */
data class Palette(
    val name: String,
    val bg: Long, val bg2: Long, val card: Long, val card2: Long,
    val line: Long, val lineSoft: Long,
    val ink: Long, val dim: Long, val faint: Long,
    val acc: Long, val acc3: Long, val accSoft: Long,
    val ok: Long, val warn: Long, val bad: Long,
    val accent: Long,
    /** A label on a clay fill (the Send button): bg clears AA on clay in both themes, ink does not. */
    val onAccent: Long,
    val scrim: Long,
) {
    val dark: Boolean get() = name == "studio"

    /** Tokens by their web names, for checks that iterate. */
    val byName: Map<String, Long>
        get() = mapOf(
            "bg" to bg, "bg2" to bg2, "card" to card, "card2" to card2, "line" to line, "line-soft" to lineSoft,
            "ink" to ink, "dim" to dim, "faint" to faint, "acc" to acc, "acc3" to acc3, "acc-soft" to accSoft,
            "ok" to ok, "warn" to warn, "bad" to bad, "accent" to accent,
        )
}

object Palettes {
    val Studio = Palette(
        name = "studio",
        bg = 0xFF14110E, bg2 = 0xFF1B1713, card = 0xFF221D18, card2 = 0xFF191510,
        line = 0xFF312A22, lineSoft = 0xFF251F19,
        ink = 0xFFF2EBDF, dim = 0xFFA79E90, faint = 0xFF94897C,
        acc = 0xFFF2EBDF, acc3 = 0xFF9BB8B2, accSoft = 0xFF2A2219,
        ok = 0xFF8FD093, warn = 0xFFE2AA63, bad = 0xFFEE8477,
        accent = 0xFFC97044,
        onAccent = 0xFF14110E,
        scrim = 0x9E060504, // rgba(6,5,4,0.62)
    )

    val Paper = Palette(
        name = "paper",
        bg = 0xFFEAE3D4, bg2 = 0xFFF3EEE3, card = 0xFFF9F5EC, card2 = 0xFFEFE9DC,
        line = 0xFFD2C8B4, lineSoft = 0xFFE0D8C6,
        ink = 0xFF1D1813, dim = 0xFF5E564A, faint = 0xFF645D52,
        acc = 0xFF1D1813, acc3 = 0xFF2C5F58, accSoft = 0xFFE3D9C6,
        ok = 0xFF2F6B37, warn = 0xFF7E5310, bad = 0xFFA33528,
        accent = 0xFFA14C28,
        onAccent = 0xFFEAE3D4,
        scrim = 0x611D1813, // rgba(29,24,19,0.38), still dark on paper
    )

    fun of(dark: Boolean): Palette = if (dark) Studio else Paper
}

/** WCAG 2.1 relative luminance and contrast, from the spec (not from the palette). */
object Contrast {
    const val AA = 4.5

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    fun ratio(a: Long, b: Long): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}

/** Type sizes in sp and line heights as multiples, from the web's chat and settings styles. */
object TypeScale {
    const val MIN = 11f

    const val ANSWER = 17f
    const val ANSWER_LINE = 1.72f
    const val USER = 14f
    const val USER_LINE = 1.7f
    const val META = 11.5f
    const val COMPOSER = 14f
    const val CHIP = 11.5f
    const val EYEBROW = 11.5f
    const val EYEBROW_TRACKING = 0.13f
    const val BUTTON = 12.5f
    const val BUTTON_TRACKING = 0.12f
    const val STAT = 29f
    const val TRACE = 12f
    const val UI = 15f
    const val WORDMARK = 13f
    const val WORDMARK_TRACKING = 0.26f

    val sizes: Map<String, Float> = mapOf(
        "answer" to ANSWER, "user" to USER, "meta" to META, "composer" to COMPOSER, "chip" to CHIP,
        "eyebrow" to EYEBROW, "button" to BUTTON, "stat" to STAT, "trace" to TRACE, "ui" to UI, "wordmark" to WORDMARK,
    )
}

/** Two durations, both eased out, never bouncy (theme.css `--fast`, `--mid`, `--ease`). */
object MotionTokens {
    const val FAST_MS = 130
    const val MID_MS = 220
    /** cubic-bezier(.22,.72,.24,1) */
    val EASE = floatArrayOf(0.22f, 0.72f, 0.24f, 1f)
    const val ENTER_OFFSET_DP = 22
    const val STAGGER_MS = 90
    const val PRESS_SCALE = 0.975f
    const val PRESS_MS = 80
    /** bg2 <-> card2, one full cycle. A pulse, not a shimmer. */
    const val SKELETON_MS = 1400
    /** Half a cycle of the tool-running dot. */
    const val PULSE_MS = 700
    const val TYPING_STAGGER_MS = 160
}
