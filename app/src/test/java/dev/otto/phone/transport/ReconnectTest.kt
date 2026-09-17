package dev.otto.phone.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectTest {
    @Test fun attemptsBackOffToHalfAMinute() {
        val backoff = Backoff()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), List(7) { backoff.next() })
        val many = Backoff()
        repeat(100) { many.next() }
        assertEquals(30_000L, many.next())
    }

    @Test fun anOlderServeIsToldToUpgrade() {
        assertTrue(ServeVersion.behind("0.1.1"))
        assertTrue(ServeVersion.behind("0.0.9"))
        assertFalse(ServeVersion.behind("0.1.2"))
        assertFalse(ServeVersion.behind("0.1.10"))
        assertFalse(ServeVersion.behind("0.2.0"))
        assertFalse(ServeVersion.behind("1.0"))
        assertTrue(ServeVersion.behind("0.1"))
        assertFalse(ServeVersion.behind("0.1.2.dev3"))
        assertFalse(ServeVersion.behind(""))
        assertFalse(ServeVersion.behind("unknown"))
        assertEquals(true, ServeVersion.hint("0.1.1")?.contains("pipx upgrade otto-cli-agent"))
        assertNull(ServeVersion.hint("0.1.2"))
    }
}
