package dev.otto.phone.protocol

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolModelsTest {
    private fun frame(json: String): JsonObject = Protocol.parse(json)!!
    private inline fun <reified T> ok(json: String): T {
        val reply = Protocol.decode(kotlinx.serialization.serializer<T>(), frame(json))
        assertTrue("expected Ok, got $reply", reply is Reply.Ok)
        return (reply as Reply.Ok).value
    }

    @Test fun helloOkReadsFeaturesAndIgnoresWhatItDoesNotKnow() {
        val hello = ok<Hello>("""{"type":"hello_ok","otto_version":"0.3.0","api_version":1,"protocol_version":2,"min_protocol":1,"features":["sessions","setup.status"],"surprise":{"x":1}}""")
        assertEquals("0.3.0", hello.ottoVersion)
        assertEquals(2, hello.protocolVersion)
        assertEquals(listOf("sessions", "setup.status"), hello.features)
        assertEquals(emptyList<String>(), ok<Hello>("""{"type":"hello_ok","otto_version":"0.1.2","api_version":1,"protocol_version":1,"min_protocol":1}""").features)
    }

    @Test fun sessionsRepliesDecode() {
        val list = ok<SessionList>("""{"type":"sessions_result","op":"list","sessions":[{"id":"0123456789abcdef0123456789abcdef","short_id":"01234567","title":"cart","workspace":null,"turns":3,"created_at":"2026-09-15T10:00:00+00:00","last_active_at":"2026-09-15T10:05:00+00:00","age":"5m ago"}]}""")
        assertEquals("01234567", list.sessions.single().shortId)
        assertNull(list.sessions.single().workspace)
        assertEquals("abc", ok<OpenedSession>("""{"op":"open","session_id":"abc","title":null,"turns":0}""").sessionId)
        val transcript = ok<Transcript>("""{"op":"transcript","id":"abc","title":"t","turns":1,"earlier":"","messages":[{"role":"you","text":"hi"},{"role":"otto","text":"hello"}]}""")
        assertEquals(listOf("you", "otto"), transcript.messages.map { it.role })
        assertTrue(ok<DeletedSession>("""{"op":"delete","session_id":"abc","deleted":true}""").deleted)
        assertEquals("new title", ok<RenamedSession>("""{"op":"rename","session_id":"abc","title":"new title"}""").title)
        assertEquals("otto-abc.json", ok<ExportedSession>("""{"op":"export","filename":"otto-abc.json","data":{"version":1}}""").filename)
        assertEquals(4, ok<ImportedSession>("""{"op":"import","session_id":"def","title":"x","turns":4}""").turns)
    }

    @Test fun usageKeepsUnknownCostsUnknown() {
        val usage = ok<SessionUsage>("""{"op":"usage","usage":{"calls":2,"input_tokens":900,"output_tokens":100,"cached_input_tokens":0,"total_tokens":1000,"cost":null,"fully_priced":false,
            "models":[{"model":"inception:mercury-2","calls":2,"input_tokens":900,"output_tokens":100,"cached_input_tokens":0,"total_tokens":1000,"reported":true,"cost":null}]},
            "turn_tokens":[400,600],"turn":{"tokens":600,"calls":1,"cost":0.0012},"title":"t","turns":2}""")
        assertNull(usage.usage.cost)
        assertFalse(usage.usage.fullyPriced)
        assertNull(usage.usage.models.single().cost)
        assertEquals(listOf(400L, 600L), usage.turnTokens)
        assertEquals(0.0012, usage.turn!!.cost!!, 0.0)
    }

    @Test fun setupStatusReadsEitherKeySpelling() {
        val serve = ok<SetupStatus>("""{"op":"status","ready":true,"key_status":{"INCEPTION_API_KEY":"********1234"},"vendor_rows":[{"name":"inception","label":"Inception (required)","key_var":"INCEPTION_API_KEY","url_var":null,"key_present":true,"url_present":true,"custom":false,"masked_key":"********1234"}],"version":{"otto":"0.3.0","api":1},"setup_write":false}""")
        assertEquals("********1234", serve.maskedKeys["INCEPTION_API_KEY"])
        assertTrue(serve.vendorRows.single().keyPresent)
        val embedded = ok<SetupStatus>("""{"ok":true,"available":true,"ready":false,"keys":{"GEMINI_API_KEY":"not set"},"version":{"otto":"0.1.2","api":1,"python":"3.13.1"},"compat":{"otto":"0.1.2","api":1,"features":["guidance"]}}""")
        assertEquals("not set", embedded.maskedKeys["GEMINI_API_KEY"])
        assertEquals(listOf("guidance"), embedded.compat!!.features)
    }

    @Test fun doctorModelsAndRoutingDecode() {
        val doctor = ok<DoctorReport>("""{"type":"doctor_result","providers":[{"provider":"inception","status":"ok","models":3,"detail":""}],"ready":true,"also_configured":["gemini"]}""")
        assertEquals("ok", doctor.providers.single().status)
        val models = ok<ModelList>("""{"models":[{"spec":"gemini:gemini-3-flash","provider":"gemini","id":"gemini-3-flash","display_name":"Gemini 3 Flash","capabilities":["vision","tools"],"context_window":1000000}]}""")
        assertEquals(listOf("vision", "tools"), models.models.single().capabilities)
        val routes = ok<RoutingList>("""{"op":"list","routes":[{"task":"reason","pin":null,"default":"inception:mercury-2","provider_only":["gemini"],"phone_seat":"gemini:gemini-3-flash"}]}""")
        assertEquals("gemini", routes.routes.single().boundProvider)
        assertNull(ok<RoutingList>("""{"routes":[{"task":"chat_fast","provider_only":null}]}""").routes.single().boundProvider)
        val options = ok<RouteOptions>("""{"op":"options","task":"reason","options":[["","no pin"],["openai:gpt-5-mini","openai:gpt-5-mini"],{"spec":"gemini:x","label":"Gemini X"}]}""")
        assertEquals(listOf(RouteOption("", "no pin"), RouteOption("openai:gpt-5-mini", "openai:gpt-5-mini"), RouteOption("gemini:x", "Gemini X")), options.choices)
    }

    @Test fun lessonsAndNotesDecode() {
        val id = "a".repeat(64)
        assertEquals("tap Add to Cart", ok<LessonList>("""{"op":"list","kind":"phone_lesson","lessons":[{"id":"$id","text":"tap Add to Cart","kind":"phone_lesson"}]}""").lessons.single().text)
        assertEquals(id, ok<LessonDeleted>("""{"op":"delete","kind":"lesson","lesson_id":"$id","deleted":true}""").lessonId)
        assertEquals(3, ok<LessonsCleared>("""{"op":"clear","kind":"lesson","cleared":3}""").cleared)
        assertEquals("in.amazon.mShop.android.shopping", ok<NoteList>("""{"notes":[{"package":"in.amazon.mShop.android.shopping","seeded":true,"learned":2}]}""").notes.single().packageName)
        val note = ok<NoteDetail>("""{"package":"com.x","seeded":"shipped text","learned":[{"id":"$id","note":"learned text"}],"shown":"both"}""")
        assertEquals("learned text", note.learned.single().text)
    }

    @Test fun errorsInEitherSpellingBecomeErrAndUnknownOpsBecomeUnsupported() {
        assertEquals(Reply.Err("busy", "a turn is running"), Protocol.decode(Hello.serializer(), frame("""{"type":"error","code":"busy","message":"a turn is running","id":"r1"}""")))
        assertEquals(Reply.Err("no_session", "gone"), Protocol.decode(Hello.serializer(), frame("""{"ok":false,"error":{"code":"no_session","message":"gone"}}""")))
        assertEquals(Reply.Unsupported, Protocol.decode(Hello.serializer(), frame("""{"type":"error","code":"unknown","message":"unknown message type 'routing'"}""")))
        assertTrue(Protocol.decode(SessionList.serializer(), frame("""{"sessions":"not a list"}""")) is Reply.Err)
    }

    @Test fun replyMapsAndUnwraps() {
        assertEquals(Reply.Ok(2), Reply.Ok(1).map { it + 1 })
        assertEquals(Reply.Unsupported, (Reply.Unsupported as Reply<Int>).map { it + 1 })
        assertNull((Reply.Err("x", "y") as Reply<Int>).valueOrNull())
    }
}
