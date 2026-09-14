package dev.otto.phone

import dev.otto.phone.transport.ServeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServeProtocolTest {
    @Test fun helloCarriesTheVersionTokenAndPhoneCapability() {
        val frame = ServeProtocol.parse(ServeProtocol.hello("t0k", "Pixel"))!!
        assertEquals("hello", ServeProtocol.type(frame))
        assertEquals("1", frame["protocol_version"].toString())
        assertEquals("\"t0k\"", frame["token"].toString())
        assertEquals("[\"phone\"]", frame["capabilities"].toString())
    }

    @Test fun pairingStringsParse() {
        assertEquals("ws://192.168.1.5:8765/" to "abc", ServeProtocol.parsePairing("ws://192.168.1.5:8765/#abc"))
        assertNull(ServeProtocol.parsePairing("http://x/#abc"))
        assertNull(ServeProtocol.parsePairing("ws://x/"))
    }

    @Test fun deviceResultsCarryTheEnvelopeFields() {
        val envelope = ServeProtocol.parse("""{"ok":false,"error":{"code":"guard","message":"no","handover":true}}""")!!
        val frame = ServeProtocol.parse(ServeProtocol.deviceResult(7, envelope))!!
        assertEquals("device_result", ServeProtocol.type(frame))
        assertEquals("7", frame["id"].toString())
        assertEquals("false", frame["ok"].toString())
    }
}
