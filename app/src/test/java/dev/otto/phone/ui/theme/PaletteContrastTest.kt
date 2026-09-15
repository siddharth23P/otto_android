package dev.otto.phone.ui.theme

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The web's tokens.spec.js over the app's palette: every text colour clears WCAG AA against every
 *  surface it can sit on, in both themes. The expected ratios come from the WCAG formula, not from
 *  the palette, so this can disagree with it. */
class PaletteContrastTest {
    private val text = listOf("ink", "dim", "faint", "acc", "ok", "warn", "bad")
    private val surfaces = listOf("bg", "bg2", "card", "card2", "acc-soft")
    private val themes = listOf(Palettes.Studio, Palettes.Paper)

    private fun failures(p: Palette, fgs: List<String>, bgs: List<String>): List<String> =
        fgs.flatMap { fg -> bgs.mapNotNull { bg ->
            val ratio = Contrast.ratio(p.byName.getValue(fg), p.byName.getValue(bg))
            if (ratio < Contrast.AA) "${p.name} $fg on $bg = ${"%.2f".format(ratio)}" else null
        } }

    @Test fun theFormulaIsTheSpecs() {
        assertEquals(21.0, Contrast.ratio(0xFFFFFFFF, 0xFF000000), 1e-9)
        assertEquals(1.0, Contrast.ratio(0xFF777777, 0xFF777777), 1e-9)
        assertEquals(4.48, Contrast.ratio(0xFF777777, 0xFFFFFFFF), 0.005)
        assertEquals(Contrast.ratio(0xFF14110E, 0xFFF2EBDF), Contrast.ratio(0xFFF2EBDF, 0xFF14110E), 1e-12)
    }

    @Test fun everyTextColourClearsAaOnEverySurface() {
        for (p in themes) assertEquals(emptyList<String>(), failures(p, text, surfaces))
    }

    @Test fun theClayAccentClearsAaWhereItIsText() {
        for (p in themes) assertEquals(emptyList<String>(), failures(p, listOf("accent"), listOf("bg", "bg2", "card", "card2")))
    }

    /** Clay on acc-soft does not clear AA in either theme (Studio 4.39, Paper 4.19), so accent text
     *  must not sit on an acc-soft fill. Pinned here so a token change that alters it is noticed. */
    @Test fun theClayAccentOnAccSoftIsBelowAa() {
        assertEquals(listOf("studio accent on acc-soft = 4.39", "paper accent on acc-soft = 4.19"),
            themes.flatMap { failures(it, listOf("accent"), surfaces) })
    }

    @Test fun theLabelOnAClayFillClearsAa() {
        for (p in themes) assertTrue("${p.name}: ${Contrast.ratio(p.onAccent, p.accent)}", Contrast.ratio(p.onAccent, p.accent) >= Contrast.AA)
    }

    @Test fun thePaletteIsTheWebsTokens() {
        val studio = mapOf("bg" to 0xFF14110E, "bg2" to 0xFF1B1713, "card" to 0xFF221D18, "card2" to 0xFF191510, "line" to 0xFF312A22,
            "line-soft" to 0xFF251F19, "ink" to 0xFFF2EBDF, "dim" to 0xFFA79E90, "faint" to 0xFF94897C, "acc" to 0xFFF2EBDF,
            "acc3" to 0xFF9BB8B2, "acc-soft" to 0xFF2A2219, "ok" to 0xFF8FD093, "warn" to 0xFFE2AA63, "bad" to 0xFFEE8477, "accent" to 0xFFC97044)
        val paper = mapOf("bg" to 0xFFEAE3D4, "bg2" to 0xFFF3EEE3, "card" to 0xFFF9F5EC, "card2" to 0xFFEFE9DC, "line" to 0xFFD2C8B4,
            "line-soft" to 0xFFE0D8C6, "ink" to 0xFF1D1813, "dim" to 0xFF5E564A, "faint" to 0xFF645D52, "acc" to 0xFF1D1813,
            "acc3" to 0xFF2C5F58, "acc-soft" to 0xFFE3D9C6, "ok" to 0xFF2F6B37, "warn" to 0xFF7E5310, "bad" to 0xFFA33528, "accent" to 0xFFA14C28)
        assertEquals(studio, Palettes.Studio.byName)
        assertEquals(paper, Palettes.Paper.byName)
        assertTrue(Palettes.Studio.dark && !Palettes.Paper.dark)
    }

    @Test fun noTextIsSmallerThanElevenSp() {
        for ((name, size) in TypeScale.sizes) assertTrue("$name is $size sp", size >= TypeScale.MIN)
    }

    @Test fun motionHasTwoDurationsEasedOut() {
        assertEquals(130, MotionTokens.FAST_MS)
        assertEquals(220, MotionTokens.MID_MS)
        assertArrayEquals(floatArrayOf(0.22f, 0.72f, 0.24f, 1f), MotionTokens.EASE, 0f)
        assertEquals(0.975f, MotionTokens.PRESS_SCALE, 0f)
    }

    @Test fun theThemeChoiceReadsItsPreference() {
        assertEquals(ThemeChoice.PAPER, ThemeChoice.fromPref("paper"))
        assertEquals(ThemeChoice.STUDIO, ThemeChoice.fromPref("studio"))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.fromPref(null))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.fromPref("neon"))
    }
}
