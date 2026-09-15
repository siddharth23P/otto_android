package dev.otto.phone

import dev.otto.phone.access.Snapshot
import dev.otto.phone.access.UiNode
import dev.otto.phone.bridge.DeviceException
import dev.otto.phone.bridge.DeviceOps
import dev.otto.phone.bridge.PyBridge
import dev.otto.phone.transport.ServeProtocol
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    @Test fun aDeviceResultFromTheEnvelopeObjectIsTheOneTheStringPathMade() {
        val screen = Snapshot("s3", "com.whatsapp", "WhatsApp", 1080, 2400, true, false, listOf(
            UiNode(1, "Type a message", "", "edit-field", 0, 2200, 900, 2300, true, true, false, false, true, null, viewId = "entry"),
            UiNode(2, "", "Send", "button", 900, 2200, 1080, 2300, true, false, false, false, false, null),
        ))
        val was = PyBridge.ops
        PyBridge.ops = object : DeviceOps by DeviceOps.Unavailable {
            override fun tree() = screen.toJson()
            override fun tap(x: Int, y: Int) = buildJsonObject { put("done", "tapped $x,$y"); put("after", screen.toJson()) }
            override fun press(key: String) = throw DeviceException("this screen is a checkout", "guard", handover = true)
        }
        try {
            val calls = listOf("tree" to emptyList(), "tap" to listOf(JsonPrimitive(540), JsonPrimitive(2250)),
                "press" to listOf(JsonPrimitive("enter")), "scroll" to listOf(JsonPrimitive("down"), JsonPrimitive(-1)), "nope" to emptyList())
            for ((method, args) in calls) {
                val old = ServeProtocol.deviceResult(9, ServeProtocol.parse(PyBridge.call(method, args))!!)
                assertEquals(method, old, ServeProtocol.deviceResult(9, PyBridge.callJson(method, args)))
            }
        } finally {
            PyBridge.ops = was
        }
    }
}
