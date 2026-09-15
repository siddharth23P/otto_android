package dev.otto.phone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEventTest {
    private fun parse(json: String) = AgentEvent.parse(json)!!

    @Test fun startedCarriesTheBudget() {
        assertEquals(AgentEvent.Started("s1", 120), parse("""{"type":"started","session_id":"s1","budget_max":120}"""))
        assertEquals(AgentEvent.Started("s1", null), parse("""{"type":"started","session_id":"s1"}"""))
    }

    @Test fun progressReadsEveryField() {
        val p = parse("""{"type":"progress","kind":"tool","text":"phone_tap","calls":4,"elapsed":3.25,"partial":null,"detail":{"target":"Add to Cart"},"session_id":"s1"}""") as AgentEvent.Progress
        assertEquals("tool", p.kind); assertEquals(4, p.calls); assertEquals(3.25, p.elapsed, 0.0); assertEquals("", p.partial)
        assertEquals("Add to Cart", p.detail!!.str("target"))
        assertNull(p.phone)
    }

    @Test fun thePhoneDecisionComesFromThePhaseEvent() {
        val yes = parse("""{"type":"progress","kind":"phase","text":"working on your phone","calls":1,"detail":{"phone":true}}""") as AgentEvent.Progress
        val no = parse("""{"type":"progress","kind":"phase","text":"answering here","calls":1,"detail":{"phone":false}}""") as AgentEvent.Progress
        val other = parse("""{"type":"progress","kind":"phase","text":"checking the answer","calls":2,"detail":null}""") as AgentEvent.Progress
        assertEquals(true, yes.phone); assertEquals(false, no.phone); assertNull(other.phone)
        assertNull((parse("""{"type":"progress","kind":"tool","text":"x","detail":{"phone":true}}""") as AgentEvent.Progress).phone)
    }

    @Test fun boardAndAsk() {
        val board = parse("""{"type":"board","node":"agent","lines":["solve: execute_bash -> ok",""],"output":null}""") as AgentEvent.Board
        assertEquals(listOf("solve: execute_bash -> ok", ""), board.lines); assertNull(board.output)
        assertEquals(AgentEvent.Ask(null, "t1", "Which size?", listOf("S", "M")), parse("""{"type":"ask","thread_id":"t1","question":"Which size?","choices":["S","M"]}"""))
    }

    @Test fun finalCarriesTurnTitleDocumentAndPhone() {
        val f = parse("""{"type":"final","session_id":"s1","text":"Done.","trace_id":"tr","usage":{"calls":3,"total_tokens":900,"cost":0.004,"fully_priced":true,"models":[]},
            "turn":{"tokens":900,"calls":3,"cost":0.004},"title":"Research note","turns":2,"phone":false,
            "document":{"path":"document.md","format":"md","markdown":"# Note","truncated":false,"files":["document.md","document.docx"]}}""") as AgentEvent.Final
        assertEquals("Done.", f.text); assertEquals(3, f.usage!!.calls); assertEquals(900L, f.turn!!.tokens)
        assertEquals("Research note", f.title); assertEquals(2, f.turns); assertEquals(false, f.phone)
        assertEquals("# Note", f.document!!.markdown); assertEquals(2, f.document!!.files.size)
        val old = parse("""{"type":"final","text":"ok","usage":{"calls":1},"trace_id":null}""") as AgentEvent.Final
        assertNull(old.turn); assertNull(old.document); assertNull(old.phone); assertNull(old.title)
    }

    @Test fun errorsAndCancelRequests() {
        val e = parse("""{"type":"error","code":"cancelled","message":"stopped","turn":{"tokens":10,"calls":1,"cost":null}}""") as AgentEvent.Error
        assertTrue(e.cancelled); assertEquals(1, e.turn!!.calls); assertNull(e.turn!!.cost)
        assertEquals(AgentEvent.Error(null, "failed", ""), parse("""{"type":"error"}"""))
        assertEquals(AgentEvent.CancelRequest(null), parse("""{"type":"cancel_request"}"""))
    }

    @Test fun anythingElseIsUnknownNotDropped() {
        val u = parse("""{"type":"heartbeat","session_id":"s9"}""") as AgentEvent.Unknown
        assertEquals("heartbeat", u.type); assertEquals("s9", u.sessionId)
        assertNull(AgentEvent.parse("not json"))
    }
}
