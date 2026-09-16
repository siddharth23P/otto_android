package dev.otto.phone.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumptionTest {
    @Test fun aNotedTurnReopensItsSessionOnlyAfterTheProcessDied() {
        assertEquals("abc", Resumption.interrupted("abc", null))
        assertEquals("abc", Resumption.interrupted("abc", ""))
        assertEquals("", Resumption.interrupted(Resumption.NEW_SESSION, null))
        // The chat already shows a session: the process lived, the turn ended with its own event.
        assertNull(Resumption.interrupted("abc", "abc"))
        assertNull(Resumption.interrupted(null, null))
        assertNull(Resumption.interrupted("", null))
    }

    @Test fun theRestrictedSettingsHintNeedsATryAndNoStore() {
        assertTrue(RestrictedSettings.likely(34, null, triedSettings = true, serviceOn = false))
        assertTrue(RestrictedSettings.likely(33, "com.android.chrome", triedSettings = true, serviceOn = false))
        assertFalse(RestrictedSettings.likely(34, "com.android.vending", triedSettings = true, serviceOn = false))
        assertFalse(RestrictedSettings.likely(34, "dev.imranr.obtainium", triedSettings = true, serviceOn = false))
        assertFalse(RestrictedSettings.likely(32, null, triedSettings = true, serviceOn = false))
        assertFalse(RestrictedSettings.likely(34, null, triedSettings = false, serviceOn = false))
        assertFalse(RestrictedSettings.likely(34, null, triedSettings = true, serviceOn = true))
    }
}
