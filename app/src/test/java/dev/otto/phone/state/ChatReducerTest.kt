package dev.otto.phone.state

import dev.otto.phone.protocol.AgentEvent
import dev.otto.phone.protocol.Protocol
import dev.otto.phone.protocol.SessionUsage
import dev.otto.phone.protocol.Transcript
import dev.otto.phone.protocol.TranscriptMessage
import dev.otto.phone.protocol.UsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReducerTest {
    private fun ev(json: String) = AgentEvent.parse(Protocol.parse(json)!!)
    private fun ChatState.on(json: String, now: Long, guard: List<String>? = null) = ChatReducer.reduce(this, ChatAction.Event(ev(json), now, guard))
    private val open = ChatReducer.reduce(ChatState(), ChatAction.Opened("s1", "", 0))

    @Test fun aTurnRunsFromSendToFinal() {
        var s = ChatReducer.reduce(open, ChatAction.Sent("add a phone case", 1_000))
        assertTrue(s.running); assertEquals("starting", s.turn!!.phase)
        s = s.on("""{"type":"started","session_id":"s1","budget_max":120}""", 1_100)
        assertEquals("thinking", s.turn!!.phase); assertEquals(120, s.turn!!.budgetMax)
        s = s.on("""{"type":"progress","kind":"phase","text":"working on your phone","calls":1,"detail":{"phone":true}}""", 1_200)
        assertEquals(Where.PHONE, s.turn!!.where); assertEquals("working on your phone", s.turn!!.phase)
        s = s.on("""{"type":"progress","kind":"call_start","text":"gemini:gemini-3-flash","calls":2}""", 1_300)
        assertEquals("gemini:gemini-3-flash", s.turn!!.model); assertEquals(2, s.turn!!.calls)
        s = s.on("""{"type":"progress","kind":"tool","text":"phone_tap","calls":0,"detail":{"target":"Add to Cart"}}""", 1_400)
        assertEquals("phone_tap Add to Cart", s.turn!!.tool); assertEquals(2, s.turn!!.calls)
        s = s.on("""{"type":"board","node":"agent","lines":["solve: phone_tap -> ok",""]}""", 1_500)
        s = s.on("""{"type":"board","node":"evaluate","lines":["escalated to plan mode -- more steps"]}""", 1_600)
        assertEquals(2, s.turn!!.steps); assertEquals("plan", s.turn!!.mode)
        assertEquals(listOf("solve: phone_tap -> ok", "escalated to plan mode -- more steps"), s.turn!!.lines)
        assertEquals(Board.Meter("▱▱▱▱▱▱▱▱▱▱▱▱", Board.Level.OK), s.turn!!.meter)
        s = s.on("""{"type":"final","session_id":"s1","text":"Added.","turn":{"tokens":900,"calls":3,"cost":0.004},"title":"Phone case","turns":1,"usage":{"calls":3}}""", 13_600)
        assertFalse(s.running); assertNull(s.turn)
        val answer = s.blocks.last() as ChatBlock.Otto
        assertEquals("Added.", answer.text)
        assertEquals(0.004, answer.turn!!.cost!!.cost!!, 0.0)
        assertEquals("☰ plan · ◆ thought for 0:12 · 2 steps · 2 model calls", answer.turn!!.thoughtTitle(99_999))
        assertEquals("Phone case", s.title); assertEquals(1, s.turns); assertEquals(3, s.usage!!.calls)
        assertEquals(listOf("add a phone case", "Added."), s.blocks.map { (it as? ChatBlock.User)?.text ?: (it as ChatBlock.Otto).text })
    }

    @Test fun answeringHereCarriesTheDocument() {
        var s = ChatReducer.reduce(open, ChatAction.Sent("write a research note", 0))
        s = s.on("""{"type":"progress","kind":"phase","text":"answering here","calls":1,"detail":{"phone":false}}""", 10)
        assertEquals(Where.HERE, s.turn!!.where)
        s = s.on("""{"type":"final","text":"Wrote a note.","document":{"path":"document.md","markdown":"# Note","files":["document.md"]}}""", 20)
        val turn = (s.blocks.last() as ChatBlock.Otto).turn!!
        assertEquals("# Note", turn.document!!.markdown); assertEquals(Where.HERE, turn.where)
    }

    @Test fun streamingShowsOnlyTheAnswerAndIsThrottled() {
        var s = ChatReducer.reduce(open, ChatAction.Sent("q", 0))
        s = s.on("""{"type":"progress","kind":"partial","partial":"ACTION: execute_bash"}""", 100)
        assertNull(s.turn!!.streaming)
        s = s.on("""{"type":"progress","kind":"partial","partial":"FINAL: The"}""", 200)
        assertEquals("The", s.turn!!.streaming)
        s = s.on("""{"type":"progress","kind":"partial","partial":"FINAL: The answer"}""", 250)
        assertEquals("The", s.turn!!.streaming)
        s = s.on("""{"type":"progress","kind":"partial","partial":"FINAL: The answer is"}""", 280)
        assertEquals("The answer is", s.turn!!.streaming)
        s = s.on("""{"type":"final","text":"The answer is 42."}""", 290)
        assertEquals("The answer is 42.", (s.blocks.last() as ChatBlock.Otto).text)
    }

    @Test fun guardNotesAreWhatTheLogGainedDuringTheTurn() {
        var s = ChatReducer.reduce(open, ChatAction.Sent("buy it", 0, guardLog = listOf("old refusal")))
        s = s.on("""{"type":"progress","kind":"tool","text":"phone_tap"}""", 10, guard = listOf("old refusal", "refused: checkout button"))
        s = s.on("""{"type":"progress","kind":"tool","text":"phone_tap"}""", 20, guard = listOf("old refusal", "refused: checkout button"))
        s = s.on("""{"type":"board","lines":[]}""", 30, guard = listOf("refused: checkout button", "handed over: payment"))
        assertEquals(listOf("refused: checkout button", "handed over: payment"), s.turn!!.guardNotes)
    }

    @Test fun guardDeltaHandlesRotationAndRepeats() {
        assertEquals(listOf("c"), GuardNotes.delta(listOf("a", "b"), listOf("a", "b", "c")))
        assertEquals(listOf("d"), GuardNotes.delta(listOf("a", "b", "c"), listOf("b", "c", "d")))
        assertEquals(listOf("x", "x"), GuardNotes.delta(emptyList(), listOf("x", "x")))
        assertEquals(listOf("x"), GuardNotes.delta(listOf("x", "x"), listOf("x", "x", "x")))
        assertEquals(emptyList<String>(), GuardNotes.delta(listOf("a"), listOf("a")))
        assertEquals(listOf("p", "q"), GuardNotes.delta(listOf("a"), listOf("p", "q")))
    }

    @Test fun stopAndFailureEndTheTurnAsASystemRow() {
        var s = ChatReducer.reduce(open, ChatAction.Sent("q", 0))
        s = s.on("""{"type":"cancel_request"}""", 5)
        assertEquals("stopping", s.turn!!.phase)
        s = s.on("""{"type":"error","code":"cancelled","message":"stopped","turn":{"tokens":10,"calls":1}}""", 10)
        val stopped = s.blocks.last() as ChatBlock.System
        assertEquals("stopped", stopped.text); assertTrue(stopped.turn!!.stopped); assertEquals(1, stopped.turn!!.cost!!.calls)
        s = ChatReducer.reduce(s, ChatAction.Sent("again", 20))
        s = s.on("""{"type":"error","code":"provider","message":"401 from inception"}""", 30)
        val failed = s.blocks.last() as ChatBlock.System
        assertEquals("401 from inception", failed.text); assertTrue(failed.turn!!.failed); assertFalse(s.running)
    }

    @Test fun aRefusedStartIsASystemRowInTheServersWords() {
        var s = ChatReducer.reduce(open, ChatAction.Sent("q", 0))
        s = ChatReducer.reduce(s, ChatAction.StartFailed("busy", "a turn is already running in that session", 1))
        assertNull(s.turn)
        assertEquals(ChatBlock.System("a turn is already running in that session", "busy", atMs = 1), s.blocks.last())
    }

    @Test fun asksAreShownAndAnswered() {
        var s = ChatReducer.reduce(open, ChatAction.Sent("q", 0))
        s = s.on("""{"type":"ask","thread_id":"t1","question":"Which colour?","choices":["black","blue"]}""", 10)
        assertEquals(AskUi("t1", "Which colour?", listOf("black", "blue")), s.ask)
        s = ChatReducer.reduce(s, ChatAction.Answered("blue", 20))
        assertNull(s.ask); assertEquals(ChatBlock.User("blue", 20), s.blocks.last()); assertTrue(s.running)
    }

    @Test fun eventsForAnotherSessionAreIgnoredAndAFreshSessionAdoptsItsId() {
        val s = ChatReducer.reduce(open, ChatAction.Sent("q", 0)).on("""{"type":"final","session_id":"other","text":"x"}""", 10)
        assertTrue(s.running)
        val fresh = ChatReducer.reduce(ChatState(), ChatAction.Sent("q", 0)).on("""{"type":"started","session_id":"s9"}""", 10)
        assertEquals("s9", fresh.sessionId)
    }

    @Test fun openingASessionRestoresItsTranscript() {
        val t = Transcript("s2", "Earlier chat", 2, earlier = "summary of before", messages = listOf(TranscriptMessage("you", "hi"), TranscriptMessage("otto", "hello")))
        val s = ChatReducer.reduce(ChatState(guardSeen = listOf("g")), ChatAction.Opened("s2", "Earlier chat", 2, t))
        assertEquals(listOf(ChatBlock.Earlier("summary of before"), ChatBlock.User("hi"), ChatBlock.Otto("hello")), s.blocks)
        assertEquals(listOf("g"), s.guardSeen)
        assertEquals("New", ChatReducer.reduce(s, ChatAction.Renamed("New")).title)
        val u = ChatReducer.reduce(s, ChatAction.UsageLoaded(SessionUsage(UsageSnapshot(calls = 4), title = "", turns = 3)))
        assertEquals(4, u.usage!!.calls); assertEquals("Earlier chat", u.title); assertEquals(3, u.turns)
    }

    @Test fun anEmptyFinalSaysSo() {
        val s = ChatReducer.reduce(open, ChatAction.Sent("q", 0)).on("""{"type":"final","text":"  "}""", 1)
        assertEquals("(no output)", (s.blocks.last() as ChatBlock.Otto).text)
    }
}
