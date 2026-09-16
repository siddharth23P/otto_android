package dev.otto.phone

import dev.otto.phone.access.SecureProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #18: a protected window is known from the first read of its page, not only from a look. */
class SecureProbeTest {
    private var now = 10_000L
    private val probe = SecureProbe({ now })

    @Test fun aVerdictHoldsForItsPageUntilTheAppOpensAnother() {
        assertNull(probe.known("com.bank", 3))
        probe.record("com.bank", 3, isSecure = true)
        assertEquals(true, probe.known("com.bank", 3))
        // The same app's next window, and another app, are probed afresh.
        assertNull(probe.known("com.bank", 4))
        assertNull(probe.known("com.shop", 3))
        probe.record("com.shop", 1, isSecure = false)
        assertEquals(false, probe.known("com.shop", 1))
        assertNull(probe.known("com.bank", 3))
    }

    @Test fun capturesAreSpacedByTheSystemsMinimum() {
        assertEquals(0L, probe.waitMs())
        probe.captured()
        assertEquals(SecureProbe.MIN_GAP_MS, probe.waitMs())
        now += 200
        assertEquals(SecureProbe.MIN_GAP_MS - 200, probe.waitMs())
        now += 1_000
        assertEquals(0L, probe.waitMs())
    }

    @Test fun ottosOwnWindowAndAnEmptyReadAreNotProbed() {
        assertTrue(probe.skip("dev.otto.phone", "dev.otto.phone"))
        assertTrue(probe.skip("", "dev.otto.phone"))
        assertFalse(probe.skip("com.bank", "dev.otto.phone"))
    }
}
