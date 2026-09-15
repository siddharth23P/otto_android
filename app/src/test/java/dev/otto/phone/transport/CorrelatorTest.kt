package dev.otto.phone.transport

import dev.otto.phone.protocol.Op
import dev.otto.phone.protocol.Protocol
import dev.otto.phone.protocol.Reply
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CorrelatorTest {
    private fun frame(json: String): JsonObject = Protocol.parse(json)!!
    private fun Correlator.Pending.done(): Reply<JsonObject> {
        assertTrue("${op.key} ${id} should be complete", reply.isCompleted)
        return reply.getCompleted()
    }
    private fun Reply<JsonObject>.field(key: String) = ((this as Reply.Ok).value[key] as kotlinx.serialization.json.JsonPrimitive).content

    @Test fun repliesWithIdsCompleteThatRequestWhateverTheOrder() {
        val c = Correlator()
        val first = c.begin(Op.SESSIONS_LIST)
        val second = c.begin(Op.SESSIONS_LIST)
        assertEquals(Correlator.Routed.Resolved(second.id, Op.SESSIONS_LIST),
            c.onFrame(frame("""{"type":"sessions_result","op":"list","id":"${second.id}","sessions":[{"id":"b"}]}""")))
        assertFalse(first.reply.isCompleted)
        c.onFrame(frame("""{"type":"sessions_result","op":"list","id":"${first.id}","sessions":[]}"""))
        assertTrue(first.done() is Reply.Ok)
        assertEquals(0, c.pendingCount)
    }

    @Test fun idLessResultsAreFirstInFirstOutPerOp() {
        val c = Correlator()
        val list1 = c.begin(Op.SESSIONS_LIST)
        val open = c.begin(Op.SESSIONS_OPEN)
        val list2 = c.begin(Op.SESSIONS_LIST)
        c.onFrame(frame("""{"type":"sessions_result","op":"open","session_id":"s1"}"""))
        assertEquals("s1", open.done().field("session_id"))
        c.onFrame(frame("""{"type":"sessions_result","op":"list","n":"1"}"""))
        c.onFrame(frame("""{"type":"sessions_result","op":"list","n":"2"}"""))
        assertEquals("1", list1.done().field("n"))
        assertEquals("2", list2.done().field("n"))
    }

    @Test fun anOpLessResultMatchesItsGroup() {
        val c = Correlator()
        val doctor = c.begin(Op.DOCTOR)
        c.onFrame(frame("""{"type":"doctor_result","providers":[]}"""))
        assertTrue(doctor.done() is Reply.Ok)
    }

    @Test fun aResultNobodyWaitsForIsDropped() {
        val c = Correlator()
        assertTrue(c.onFrame(frame("""{"type":"sessions_result","op":"list","id":"r99"}""")) is Correlator.Routed.Dropped)
        val list = c.begin(Op.SESSIONS_LIST)
        assertTrue(c.onFrame(frame("""{"type":"sessions_result","op":"list","id":"late"}""")) is Correlator.Routed.Dropped)
        assertFalse(list.reply.isCompleted)
    }

    @Test fun idLessBusyAndNoSessionFailThePendingTurn() {
        val c = Correlator()
        val list = c.begin(Op.SESSIONS_LIST)
        val turn = c.begin(Op.TURN, "s1")
        assertTrue(c.onFrame(frame("""{"type":"error","code":"busy","message":"a turn is already running in that session"}""")) is Correlator.Routed.Resolved)
        assertEquals(Reply.Err("busy", "a turn is already running in that session"), turn.done())
        assertFalse(list.reply.isCompleted)
        val turn2 = c.begin(Op.TURN, null)
        c.onFrame(frame("""{"type":"error","code":"no_session","message":"this connection already holds 8 sessions"}"""))
        assertEquals("no_session", (turn2.done() as Reply.Err).code)
        assertFalse(list.reply.isCompleted)
    }

    @Test fun anIdLessNoSessionWithNoTurnPendingFailsTheOldestRequest() {
        val c = Correlator()
        val open = c.begin(Op.SESSIONS_OPEN)
        c.onFrame(frame("""{"type":"error","code":"no_session","message":"no session matches zz"}"""))
        assertEquals(Reply.Err("no_session", "no session matches zz"), open.done())
    }

    @Test fun otherIdLessErrorsFailTheOldestRequestAndAreNeverEvents() {
        val c = Correlator()
        val oldest = c.begin(Op.SESSIONS_TRANSCRIPT)
        val newer = c.begin(Op.SESSIONS_LIST)
        val routed = c.onFrame(frame("""{"type":"error","code":"failed","message":"OSError: disk"}"""))
        assertEquals(Correlator.Routed.Resolved(oldest.id, Op.SESSIONS_TRANSCRIPT), routed)
        assertEquals(Reply.Err("failed", "OSError: disk"), oldest.done())
        assertFalse(newer.reply.isCompleted)
        assertTrue(c.onFrame(frame("""{"type":"error","code":"failed","message":"again"}""")) is Correlator.Routed.Resolved)
        assertTrue(c.onFrame(frame("""{"type":"error","code":"failed","message":"nobody"}""")) is Correlator.Routed.Dropped)
    }

    @Test fun anErrorWithAnIdFailsOnlyThatRequest() {
        val c = Correlator()
        val a = c.begin(Op.ROUTING_PIN)
        val b = c.begin(Op.ROUTING_CLEAR)
        c.onFrame(frame("""{"type":"error","code":"invalid_pin","message":"no such model","id":"${b.id}"}"""))
        assertEquals(Reply.Err("invalid_pin", "no such model"), b.done())
        assertFalse(a.reply.isCompleted)
    }

    @Test fun anUnknownOpBecomesUnsupported() {
        val c = Correlator()
        val setup = c.begin(Op.SETUP_STATUS)
        c.onFrame(frame("""{"type":"error","code":"unknown","message":"unknown message type 'setup'"}"""))
        assertEquals(Reply.Unsupported, setup.done())
    }

    @Test fun aNoQuestionErrorBelongsToAnAnswerAndFailsNothing() {
        val c = Correlator()
        val list = c.begin(Op.SESSIONS_LIST)
        assertTrue(c.onFrame(frame("""{"type":"error","code":"no_question","message":"no question is waiting for that answer"}""")) is Correlator.Routed.Dropped)
        assertFalse(list.reply.isCompleted)
    }

    @Test fun aTurnIsAcceptedByItsStartedEvent() {
        val c = Correlator()
        val forS2 = c.begin(Op.TURN, "s2")
        val fresh = c.begin(Op.TURN, null)
        val routed = c.onFrame(frame("""{"type":"event","session_id":"s1","event":{"type":"started","session_id":"s1","budget_max":120}}"""))
        assertTrue(routed is Correlator.Routed.Event)
        assertEquals("120", fresh.done().field("budget_max"))
        assertFalse(forS2.reply.isCompleted)
        c.onFrame(frame("""{"type":"event","session_id":"s2","event":{"type":"started","session_id":"s2"}}"""))
        assertEquals("s2", forS2.done().field("session_id"))
        assertEquals(setOf("s1", "s2"), c.runningTurns)
    }

    @Test fun eventsCarryTheFramesSessionAndTurnErrorsStayEvents() {
        val c = Correlator()
        val routed = c.onFrame(frame("""{"type":"event","session_id":"s1","event":{"type":"error","code":"provider","message":"401"}}""")) as Correlator.Routed.Event
        assertEquals("s1", (routed.event["session_id"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("provider", (routed.event["code"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test fun finalAndErrorEndARunningTurn() {
        val c = Correlator()
        c.onFrame(frame("""{"type":"event","session_id":"s1","event":{"type":"started"}}"""))
        c.onFrame(frame("""{"type":"event","session_id":"s2","event":{"type":"started"}}"""))
        c.onFrame(frame("""{"type":"event","session_id":"s1","event":{"type":"final","text":"ok"}}"""))
        c.onFrame(frame("""{"type":"event","session_id":"s2","event":{"type":"error","code":"cancelled"}}"""))
        assertEquals(emptySet<String>(), c.runningTurns)
    }

    @Test fun deviceCallsGoToTheDeviceNotToARequest() {
        val c = Correlator()
        val list = c.begin(Op.SESSIONS_LIST)
        assertTrue(c.onFrame(frame("""{"type":"device_call","id":1,"method":"tree","args":[]}""")) is Correlator.Routed.DeviceCall)
        assertFalse(list.reply.isCompleted)
    }

    @Test fun helloCompletesOnHelloOkOrOnAnErrorBeforeIt() {
        val c = Correlator()
        val ok = c.beginHello()
        c.onFrame(frame("""{"type":"hello_ok","otto_version":"0.3.0","protocol_version":2}"""))
        assertTrue(ok.getCompleted() is Reply.Ok)
        val refused = c.beginHello()
        val list = c.begin(Op.SESSIONS_LIST)
        c.onFrame(frame("""{"type":"error","code":"hello","message":"bad token"}"""))
        assertEquals(Reply.Err("hello", "bad token"), refused.getCompleted())
        assertFalse(list.reply.isCompleted)
    }

    @Test fun disconnectFailsEveryWaiterAndEndsEveryRunningTurn() {
        val c = Correlator()
        val hello = c.beginHello()
        val list = c.begin(Op.SESSIONS_LIST)
        val turn = c.begin(Op.TURN, "s9")
        c.onFrame(frame("""{"type":"event","session_id":"s1","event":{"type":"started"}}"""))
        val events = c.failAll("disconnected", "connection lost")
        assertEquals(Reply.Err("disconnected", "connection lost"), hello.getCompleted())
        assertEquals(Reply.Err("disconnected", "connection lost"), list.done())
        assertEquals(Reply.Err("disconnected", "connection lost"), turn.done())
        assertEquals(1, events.size)
        assertEquals("""{"type":"error","code":"disconnected","message":"connection lost","session_id":"s1"}""", events.single().toString())
        assertEquals(0, c.pendingCount)
    }

    @Test fun expiryFailsWithTimeoutAndALateReplyIsDropped() {
        val c = Correlator()
        val models = c.begin(Op.MODELS)
        val timedOut = c.expire(models.id)
        assertEquals("timeout", (timedOut as Reply.Err).code)
        assertEquals(timedOut, models.done())
        assertTrue(c.onFrame(frame("""{"type":"models_result","id":"${models.id}","models":[]}""")) is Correlator.Routed.Dropped)
    }

    @Test fun aSendThatFailedFailsItsRequest() {
        val c = Correlator()
        val list = c.begin(Op.SESSIONS_LIST)
        c.fail(list.id, "disconnected", "not connected")
        assertEquals(Reply.Err("disconnected", "not connected"), list.done())
    }

    @Test fun timeoutsFollowWhatTheOpWaitsOn() {
        assertEquals(15_000L, Correlator.timeoutMillis(Op.SESSIONS_LIST))
        assertEquals(15_000L, Correlator.timeoutMillis(Op.TURN))
        assertEquals(45_000L, Correlator.timeoutMillis(Op.DOCTOR))
        assertEquals(45_000L, Correlator.timeoutMillis(Op.SETUP_PROBE))
        assertEquals(45_000L, Correlator.timeoutMillis(Op.MODELS))
        assertEquals(60_000L, Correlator.timeoutMillis(Op.SESSIONS_EXPORT))
        assertEquals(60_000L, Correlator.timeoutMillis(Op.SESSIONS_IMPORT))
    }

    @Test fun idsAreUnique() {
        val c = Correlator()
        assertEquals(50, (1..50).map { c.begin(Op.SESSIONS_LIST).id }.toSet().size)
    }
}
