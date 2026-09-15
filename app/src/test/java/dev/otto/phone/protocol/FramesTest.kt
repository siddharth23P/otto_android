package dev.otto.phone.protocol

import dev.otto.phone.transport.ServeProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FramesTest {
    private fun obj(frame: String): JsonObject = Protocol.parse(frame)!!

    @Test fun helloStillSaysProtocolOne() {
        val hello = obj(Frames.hello("tok", "Pixel"))
        assertEquals("1", hello["protocol_version"].toString())
        assertEquals("[\"phone\"]", hello["capabilities"].toString())
    }

    @Test fun aRequestCarriesItsIdOnlyWhenGiven() {
        assertEquals("\"r7\"", obj(Frames.doctor("r7"))["id"].toString())
        assertFalse(obj(Frames.doctor()).containsKey("id"))
        assertEquals("""{"type":"sessions","op":"list"}""", Frames.sessions("list"))
    }

    @Test fun groupsAndOpsGoWhereTheServerReadsThem() {
        val rename = obj(Frames.sessions("rename", sessionId = "abc", title = "Cart", id = "r1"))
        assertEquals("sessions", rename.str("type")); assertEquals("rename", rename.str("op")); assertEquals("Cart", rename.str("title"))
        val key = obj(Frames.setKey("GEMINI_API_KEY", "secret", "r2"))
        assertEquals("setup", key.str("type")); assertEquals("set_key", key.str("op")); assertEquals("GEMINI_API_KEY", key.str("name"))
        assertNull(obj(Frames.models()).str("op"))
        val pin = obj(Frames.pinRoute("reason", "openai:gpt-5-mini"))
        assertEquals("routing", pin.str("type")); assertEquals("pin", pin.str("op")); assertEquals("openai:gpt-5-mini", pin.str("spec"))
    }

    @Test fun lessonDeletesNameTheRowApartFromTheRequestId() {
        val frame = obj(Frames.deleteLesson("app_note:com.x", "f".repeat(64), "r3"))
        assertEquals("r3", frame.str("id"))
        assertEquals("f".repeat(64), frame.str("lesson_id"))
        assertEquals("app_note:com.x", frame.str("kind"))
        assertEquals("com.x", obj(Frames.deleteNote("com.x", "e".repeat(64))).str("package"))
    }

    @Test fun aTurnSaysWhereToRunOnlyWhenAsked() {
        val plain = obj(Frames.turn("abc", "hi"))
        assertFalse(plain.containsKey("phone")); assertFalse(plain.containsKey("op"))
        assertEquals("off", obj(Frames.turn("abc", "write a note", PhoneMode.OFF, "r4")).str("phone"))
        assertFalse(obj(Frames.turn("", "new session")).containsKey("session_id"))
    }

    @Test fun theFacadeMakesTheSameFrames() {
        assertEquals(Frames.turn("s", "t"), ServeProtocol.turn("s", "t"))
        assertEquals(Frames.cancel("s"), ServeProtocol.cancel("s"))
        assertEquals(Frames.sessions("delete", sessionId = "s"), ServeProtocol.sessions("delete", sessionId = "s"))
        val envelope = buildJsonObject { put("ok", true) }
        assertEquals("""{"type":"device_result","id":3,"ok":true}""", ServeProtocol.deviceResult(3, envelope))
    }
}
