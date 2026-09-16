package dev.otto.phone.state

import dev.otto.phone.protocol.ModelUsage
import dev.otto.phone.protocol.SessionRow
import dev.otto.phone.protocol.UsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Expected values are Python's own output for the same inputs (tests/test_usage.py,
 *  tests/test_tui_progress.py, and the rounding edges run through the original functions). */
class FormatTest {
    @Test fun thousandsMatchesUsagePanel() {
        val cases = listOf(
            0L to "0", 999L to "999", 1000L to "1.0k", 55_700L to "55.7k", 999_999L to "1000.0k",
            1_000_000L to "1.00M", 2_500_000L to "2.50M",
            // rounding edges, half-to-even on the exact binary value
            1049L to "1.0k", 1050L to "1.1k", 1150L to "1.1k", 1250L to "1.2k", 999_949L to "999.9k", 999_950L to "1000.0k",
            1_005_000L to "1.00M", 1_015_000L to "1.01M", 12_345_678L to "12.35M",
        )
        for ((n, shown) in cases) assertEquals("$n", shown, Format.thousands(n))
    }

    @Test fun shortModelDropsRegionVendorAndDateStamp() {
        val cases = listOf(
            "us.anthropic.claude-sonnet-4-20250514-v1:0" to "claude-sonnet-4",
            "openai/gpt-4o-mini" to "gpt-4o-mini",
            "mercury-coder-small" to "mercury-coder-small",
            "gemini-2.5-flash" to "gemini-2.5-flash",
            "" to "unknown",
            "us.anthropic.claude-3-5-haiku-20241022-v1:0" to "claude-3-5-haiku",
        )
        for ((full, short) in cases) assertEquals(full, short, Format.shortModel(full))
        assertEquals("unknown", Format.shortModel(null))
    }

    @Test fun costIsShownAtAPrecisionThatSaysSomething() {
        val cases = listOf(
            null to "--", 0.0 to "$0.000", 0.0004 to "$0.0004", 0.0912 to "$0.091", 1.5 to "$1.500", 42.128 to "$42.13",
            -0.0 to "$-0.000", 0.00005 to "$0.0001", 0.00015 to "$0.0001", 0.0099999 to "$0.0100", 0.01 to "$0.010",
            0.0015 to "$0.0015", 0.0025 to "$0.0025", 9.9995 to "$9.999", 9.99951 to "$10.000", 10.0 to "$10.00",
            42.125 to "$42.12", 0.125 to "$0.125", -0.5 to "$-0.5000", -0.00001 to "$-0.0000",
        )
        for ((amount, shown) in cases) assertEquals("$amount", shown, Format.formatCost(amount))
        assertEquals("—", Format.formatCost(null, unknown = "—"))
    }

    @Test fun theClockReadsAsMinutesAndSeconds() {
        val cases = listOf(0.0 to "0:00", 9.9 to "0:09", 59.99 to "0:59", 60.0 to "1:00", 131.0 to "2:11",
            3599.0 to "59:59", 3600.0 to "60:00", -1.0 to "-1:59", -61.5 to "-2:59")
        for ((s, shown) in cases) assertEquals("$s", shown, Format.clock(s))
    }

    @Test fun spendLineIsTheTopBarsRightHandSide() {
        assertEquals("nothing spent yet", Format.spendLine(UsageSnapshot()))
        assertEquals("3 req · 1.2k tok · $0.0040", Format.spendLine(UsageSnapshot(calls = 3, totalTokens = 1234, cost = 0.004)))
        assertEquals("3 req · 1.2k tok · $0.0040+", Format.spendLine(UsageSnapshot(calls = 3, totalTokens = 1234, cost = 0.004, fullyPriced = false)))
        // the TUI draws a missing total as 0.0, not as a dash
        assertEquals("1 req · 10 tok · $0.000+", Format.spendLine(UsageSnapshot(calls = 1, totalTokens = 10, cost = null, fullyPriced = false)))
    }

    @Test fun modelUsageLineShowsDashesForWhatIsNotKnown() {
        assertEquals("2 req · 1.5k · $0.0030", Format.modelUsageLine(ModelUsage(model = "m", calls = 2, totalTokens = 1500, cost = 0.003)))
        assertEquals("1 req · -- · --", Format.modelUsageLine(ModelUsage(model = "m", calls = 1, totalTokens = 0, reported = false, cost = null)))
    }

    @Test fun sessionLineMatchesThePicker() {
        val row = SessionRow(id = "0123456789abcdef0123456789abcdef", shortId = "01234567", title = "cart", workspace = "/home/me/otto/workspaces/0123",
            turns = 3, lastActiveAt = "2026-09-15T10:00:00+00:00", age = "5m ago")
        assertEquals("01234567  cart  ·  3 turn(s)  ·  0123  ·  5m ago", Format.sessionLine(row))
        assertEquals("01234567  cart  ·  3 turn(s)  ·  0123  ·  5m ago ◂ this one", Format.sessionLine(row, current = row.id))
        val bare = row.copy(title = "", workspace = null, shortId = "", age = "")
        assertEquals("01234567  (untitled)  ·  3 turn(s)  ·  no workspace  ·  2h ago",
            Format.sessionLine(bare, now = Instant.parse("2026-09-15T12:30:00Z")))
        assertEquals("no workspace", Format.sessionLine(row.copy(workspace = "")).split("  ·  ")[2])
    }

    @Test fun describeAgeIsCoarseOnPurpose() {
        val now = Instant.parse("2026-09-15T12:00:00Z")
        assertEquals("just now", Format.describeAge("2026-09-15T11:59:30+00:00", now))
        assertEquals("just now", Format.describeAge("2026-09-15T13:00:00+00:00", now))
        assertEquals("5m ago", Format.describeAge("2026-09-15T11:55:00", now))
        assertEquals("3h ago", Format.describeAge("2026-09-15T09:00:00+00:00", now))
        assertEquals("3h ago", Format.describeAge("2026-09-15T14:00:00+05:00", now))
        assertEquals("2d ago", Format.describeAge("2026-09-13T11:00:00+00:00", now))
    }

    @Test fun onlyTheTextAfterFinalIsTheAnswer() {
        assertNull(Format.textAfterFinal("ACTION: execute_bash\nCODE:\npytest -q"))
        assertEquals("forty two", Format.textAfterFinal("thinking about it\nFINAL:\nforty two"))
        assertEquals("", Format.textAfterFinal("FINAL:   "))
    }

    @Test fun theThoughtTitlePutsTheClockFirst() {
        assertEquals("◆ thought for 0:12 · 5 steps · 3 model calls", Format.thoughtTitle(null, 5, 3, 12.4))
        assertEquals("☰ plan · ◆ thought for 5 steps", Format.thoughtTitle("plan", 5, 0, 0.0))
        assertEquals("◆ thought", Format.thoughtTitle("turbo", 0, 0, 0.0))
        assertEquals("◆ thought for 1:05", Format.thoughtTitle(null, 0, 0, 65.0))
    }
}
