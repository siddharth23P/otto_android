package dev.otto.phone.protocol

import dev.otto.phone.state.FORBIDDEN_SETUP
import dev.otto.phone.state.writeProblem
import dev.otto.phone.transport.Correlator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The frames otto serve (protocol 2) sends, literally, as the finished server writes them. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServeShapesTest {
    private fun frame(json: String): JsonObject = Protocol.parse(json)!!
    private inline fun <reified T> ok(json: String): T {
        val reply = Protocol.decode(kotlinx.serialization.serializer<T>(), frame(json))
        assertTrue("expected Ok, got $reply", reply is Reply.Ok)
        return (reply as Reply.Ok).value
    }

    private val lessonId = "b".repeat(64)

    @Test fun helloOkNamesEveryFeature() {
        val hello = ok<Hello>("""{"type":"hello_ok","otto_version":"0.3.0","api_version":1,"protocol_version":2,"min_protocol":1,"features":["ids","turn.phone","sessions.close","sessions.rename","sessions.export","sessions.import","sessions.usage","setup","doctor","models","routing","lessons","notes","files"]}""")
        val caps = Capabilities.of(hello)
        assertTrue(caps.echoesIds)
        Op.entries.forEach { assertTrue("${it.key} should be supported", caps.supports(it)) }
    }

    @Test fun startedArrivesOnAnEventFrameCarryingTheTurnsId() {
        val c = Correlator()
        val other = c.begin(Op.TURN, null)
        val mine = c.begin(Op.TURN, null)
        val routed = c.onFrame(frame("""{"type":"event","id":"${mine.id}","session_id":"0123456789abcdef0123456789abcdef","event":{"type":"started","session_id":"0123456789abcdef0123456789abcdef","budget_max":40}}"""))
        assertTrue(routed is Correlator.Routed.Event)
        assertNull("the request id does not leak into the event", (routed as Correlator.Routed.Event).event["id"])
        assertTrue(mine.reply.isCompleted)
        assertEquals("40", ((mine.reply.getCompleted() as Reply.Ok).value["budget_max"] as JsonPrimitive).content)
        assertFalse(other.reply.isCompleted)
    }

    @Test fun phaseFinalAndErrorEvents() {
        val phase = AgentEvent.parse(frame("""{"type":"progress","kind":"phase","text":"answering here","calls":0,"elapsed":0.4,"partial":"","detail":{"phone":false}}""")) as AgentEvent.Progress
        assertEquals(false, phase.phone)
        val final = AgentEvent.parse(frame("""{"type":"final","text":"Done.","usage":{"calls":2},"trace_id":"t1","turn":{"tokens":1200,"calls":2,"cost":null},"title":"Research","turns":1,"phone":null,"document":null}""")) as AgentEvent.Final
        assertNull(final.document); assertNull(final.phone); assertNull(final.turn!!.cost)
        val withDoc = AgentEvent.parse(frame("""{"type":"final","text":"Wrote it.","turn":{"tokens":9,"calls":1,"cost":0.02},"title":"t","turns":2,"phone":false,"document":{"path":"/w/otto_research/x/document.md","format":"md","markdown":"# X","truncated":false,"files":["document.md","document.docx"]}}""")) as AgentEvent.Final
        assertEquals(listOf("document.md", "document.docx"), withDoc.document!!.files)
        val error = AgentEvent.parse(frame("""{"type":"error","code":"provider","message":"401 from inception","turn":{"tokens":0,"calls":1,"cost":null}}""")) as AgentEvent.Error
        assertEquals("provider", error.code); assertEquals(1, error.turn!!.calls)
    }

    @Test fun sessionRepliesWithAnEchoedId() {
        val t = ok<Transcript>("""{"type":"sessions_result","op":"transcript","id":"r7","session_id":"abcd","title":"t","turns":1,"earlier":"","messages":[{"role":"you","text":"hi"}]}""")
        assertEquals("abcd", t.sessionRef)
        assertEquals("abcd", ok<Transcript>("""{"type":"sessions_result","op":"transcript","id":"abcd","title":"t","turns":1,"earlier":"","messages":[]}""").sessionRef)
        assertTrue(ok<ClosedSession>("""{"type":"sessions_result","op":"close","id":"r8","session_id":"abcd","closed":true}""").closed)
        val export = ok<ExportedSession>("""{"type":"sessions_result","op":"export","id":"r9","session_id":"abcd","filename":"otto-session-abcd.json","data":{"version":1,"session":{"id":"abcd","workspace":null}}}""")
        assertEquals("abcd", export.sessionId); assertEquals("otto-session-abcd.json", export.filename)
        assertEquals("efgh", ok<ImportedSession>("""{"type":"sessions_result","op":"import","id":"r10","session_id":"efgh","title":"x","turns":3}""").sessionId)
        val usage = ok<SessionUsage>("""{"type":"sessions_result","op":"usage","id":"r11","session_id":"abcd","usage":{"calls":1,"total_tokens":10,"cost":0.001,"fully_priced":true,"models":[]},"turn_tokens":[10],"turn":{"tokens":10,"calls":1,"cost":0.001},"title":"t","turns":1}""")
        assertEquals("abcd", usage.sessionId); assertEquals(listOf(10L), usage.turnTokens)
    }

    @Test fun setupDoctorAndModels() {
        val status = ok<SetupStatus>("""{"type":"setup_result","op":"status","id":"r1","ready":true,"keys":{"INCEPTION_API_KEY":"sk-…1234"},"vendors":[{"name":"inception","label":"Inception","key_var":"INCEPTION_API_KEY","url_var":null,"key_present":true,"url_present":false,"custom":false,"masked_key":"sk-…1234"}],"version":{"otto":"0.3.0","api":1,"python":"3.13.1"},"setup_write":true}""")
        assertEquals("sk-…1234", status.maskedKeys["INCEPTION_API_KEY"])
        assertEquals("INCEPTION_API_KEY", status.vendorRows.single().keyVar)
        assertTrue(status.setupWrite)
        val set = ok<KeySet>("""{"type":"setup_result","op":"set_key","id":"r2","name":"GEMINI_API_KEY","masked":"AI…9x","ready":true}""")
        assertEquals("AI…9x", set.masked); assertEquals(true, set.ready)
        assertEquals("old", ok<KeySet>("""{"name":"X","shown":"old"}""").masked)
        val probe = ok<ProbeResult>("""{"type":"setup_result","op":"probe","id":"r3","name":"gemini","ok":true,"status":"ok","detail":"","model_count":2,"models":[{"spec":"gemini:a","provider":"gemini","id":"a","display_name":null,"capabilities":[],"context_window":null,"max_output_tokens":8192}]}""")
        assertEquals(2, probe.modelCount); assertEquals(8192, probe.models.single().maxOutputTokens)
        val doctor = ok<DoctorReport>("""{"type":"doctor_result","id":"r4","providers":[{"provider":"inception","status":"ok","models":3,"detail":""}],"ready":true,"required":["inception"],"also_configured":["gemini"]}""")
        assertEquals("inception", doctor.requiredText)
        val models = ok<ModelList>("""{"type":"models_result","id":"r5","models":[{"spec":"inception:mercury-2","provider":"inception","id":"mercury-2","display_name":"Mercury 2","capabilities":["tools"],"context_window":128000,"max_output_tokens":16384}]}""")
        assertEquals("Mercury 2", models.models.single().displayName)
    }

    @Test fun routingShapes() {
        val list = ok<RoutingList>("""{"type":"routing_result","op":"list","id":"r1","routes":[{"task":"evaluate","pin":null,"default":"gemini:gemini-3-flash","provider_only":{"provider":"gemini","reason":"needs screenshots"},"phone_seat":"gemini:gemini-3-flash"},{"task":"chat_fast","pin":"inception:mercury-2","default":"inception:mercury-2","provider_only":null,"phone_seat":null}]}""")
        assertEquals("gemini", list.routes[0].boundProvider); assertEquals("needs screenshots", list.routes[0].boundReason)
        assertNull(list.routes[1].boundProvider)
        val options = ok<RouteOptions>("""{"type":"routing_result","op":"options","id":"r2","task":"reason","pin":null,"options":[{"label":"(no pin — default route)","spec":""},{"label":"Mercury 2","spec":"inception:mercury-2"}]}""")
        assertEquals(RouteOption("", "(no pin — default route)"), options.choices.first())
        val pinned = ok<RouteChange>("""{"type":"routing_result","op":"pin","id":"r3","task":"reason","pin":"inception:mercury-2","problems":["plan has no vision model"]}""")
        assertEquals(listOf("plan has no vision model"), pinned.problems)
        assertNull(ok<RouteChange>("""{"type":"routing_result","op":"clear","id":"r4","task":"reason","pin":null,"problems":[]}""").pin)
    }

    @Test fun lessonsAndNotesShapes() {
        val list = ok<LessonList>("""{"type":"lessons_result","op":"list","id":"r1","kind":"phone_lesson","lessons":[{"lesson_id":"$lessonId","cue":"amazon search results","action":"tap Add to Cart","outcome":"worked","text":"when amazon search results -> tap Add to Cart (worked)"}]}""")
        val row = list.lessons.single()
        assertEquals(lessonId, row.id); assertEquals("tap Add to Cart", row.action); assertEquals("worked", row.outcome)
        val deleted = ok<LessonDeleted>("""{"type":"lessons_result","op":"delete","id":"r2","kind":"lesson","lesson_id":"$lessonId","deleted":true}""")
        assertEquals("the request id is not the lesson id", lessonId, deleted.lessonId)
        assertEquals(4, ok<LessonsCleared>("""{"type":"lessons_result","op":"clear","id":"r3","kind":"lesson","removed":4}""").cleared)
        val notes = ok<NoteList>("""{"type":"notes_result","op":"list","id":"r4","notes":[{"package":"in.amazon.mShop.android.shopping","seeded":true,"learned":2}]}""")
        assertTrue(notes.notes.single().seeded)
        val note = ok<NoteDetail>("""{"type":"notes_result","op":"get","id":"r5","package":"in.amazon.mShop.android.shopping","seeded":"Search results: Add to Cart is on each row.","learned":[{"lesson_id":"$lessonId","cue":null,"action":null,"outcome":null,"text":"the cart badge updates late"}],"shown":["Search results: Add to Cart is on each row.","the cart badge updates late"]}""")
        assertEquals(2, note.shown.size); assertEquals(lessonId, note.learned.single().id); assertNull(note.learned.single().cue)
        val gone = ok<LessonDeleted>("""{"type":"notes_result","op":"delete","id":"r6","package":"in.amazon.mShop.android.shopping","lesson_id":"$lessonId","deleted":true}""")
        assertEquals("in.amazon.mShop.android.shopping", gone.packageName)
    }

    @Test fun filesShape() {
        val blob = ok<FileBlob>("""{"type":"files_result","op":"get","id":"r1","session_id":"abcd","name":"document.docx","path":"/w/document.docx","format":"docx","mime":"application/vnd.openxmlformats-officedocument.wordprocessingml.document","size":3,"data":"AAEC"}""")
        assertEquals("docx", blob.format); assertEquals(3L, blob.size)
    }

    @Test fun errorCodesAndTheForbiddenExplanation() {
        val c = Correlator()
        val pin = c.begin(Op.ROUTING_PIN)
        c.onFrame(frame("""{"type":"error","id":"${pin.id}","code":"forbidden","message":"setup changes are only accepted from loopback"}"""))
        val reply = pin.reply.getCompleted()
        assertEquals(FORBIDDEN_SETUP, reply.writeProblem())
        assertEquals("busy", Reply.Err("busy", "busy").writeProblem())
        assertEquals(Reply.Unsupported, Protocol.decode(Hello.serializer(), frame("""{"type":"error","id":"r2","code":"unknown","message":"unknown op 'x'"}""")))
        assertEquals(Reply.Err("no_phone", "no phone"), Protocol.decode(Hello.serializer(), frame("""{"type":"error","id":"r3","code":"no_phone","message":"no phone"}""")))
    }
}
