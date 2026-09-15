package dev.otto.phone.ui.chat

import dev.otto.phone.protocol.ModelUsage
import dev.otto.phone.protocol.SessionRow
import dev.otto.phone.protocol.UsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class DrawerTextTest {
    @Test fun sessionRowsAndTheCurrentLine() {
        val row = SessionRow(id = "0123456789abcdef", shortId = "01234567", title = "cart", turns = 3, age = "5m ago")
        assertEquals("01234567 · 3 turns · 5m ago", DrawerText.sessionMeta(row, current = false))
        assertEquals("01234567 · 1 turn · 5m ago · this one", DrawerText.sessionMeta(row.copy(turns = 1), current = true))
        val aged = SessionRow(id = "abcdef0123", turns = 0, lastActiveAt = "2026-09-15T10:00:00+00:00")
        assertEquals("abcdef01 · 0 turns · 2h ago", DrawerText.sessionMeta(aged, false, Instant.parse("2026-09-15T12:30:00Z")))
        assertEquals("2 turns · ☰ plan · mercury-2", DrawerText.currentMeta(2, "plan", "inception:mercury-2"))
        assertEquals("0 turns", DrawerText.currentMeta(0, null, ""))
    }

    @Test fun usageSaysDashWhenItDoesNotKnow() {
        assertEquals("3 req · 1.2k tok · $0.0040", DrawerText.usageLine(ModelUsage("m", calls = 3, totalTokens = 1_234, cost = 0.004)))
        assertEquals("1 req · — · —", DrawerText.usageLine(ModelUsage("m", calls = 1, reported = false, cost = null)))
        assertEquals("4 req · 2.0k tok · $0.012+", DrawerText.totalsLine(UsageSnapshot(calls = 4, totalTokens = 2_000, cost = 0.012, fullyPriced = false)))
        assertEquals("4 req · 2.0k tok · —", DrawerText.totalsLine(UsageSnapshot(calls = 4, totalTokens = 2_000, cost = null, fullyPriced = false)))
        assertEquals("tokens per turn: 400, 1.5k", DrawerText.sparkDescription(listOf(400, 1_500)))
    }
}
