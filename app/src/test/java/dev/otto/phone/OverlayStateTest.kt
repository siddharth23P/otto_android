package dev.otto.phone

import dev.otto.phone.access.OverlayState
import dev.otto.phone.access.OverlayState.Done
import dev.otto.phone.access.OverlayState.Hidden
import dev.otto.phone.access.OverlayState.Outcome
import dev.otto.phone.access.OverlayState.Working
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayStateTest {
    private fun event(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    /** Events at the given times (ms). */
    private fun OverlayState.then(vararg timed: Pair<Long, String>) = timed.fold(this) { state, (at, e) -> state.after(event(e), at) }

    private val started = 1_000L to """{"type":"started","session_id":"s1"}"""

    @Test fun nothingIsShownOutsideATurn() {
        assertEquals(Hidden, Hidden.then(5_000L to """{"type":"final","text":"done"}"""))
        assertEquals(Hidden, Hidden.then(5_000L to """{"type":"progress","kind":"tool","text":"Opening Chrome"}"""))
        assertEquals("", Hidden.meta(0))
    }

    @Test fun aTurnShowsItsStepHowLongAndHowManyCalls() {
        val w = Hidden.then(started, 4_000L to """{"type":"progress","kind":"tool","text":"opening Amazon","calls":3}""")
        assertEquals(Working("opening Amazon", 3, 1_000L), w)
        assertEquals("OTTO · 0:12 · 3 calls", w.meta(13_000L))
        assertEquals("Opening Amazon", w.words)
        assertTrue(w.agentHasPhone)
        assertEquals("OTTO · 0:00", Hidden.then(started).meta(1_500L))
        assertEquals("Thinking", Hidden.then(started).words)
    }

    @Test fun aPartialAnswerOrABlankStepLeavesTheLastStepAndCallsNeverGoBack() {
        val w = Hidden.then(started, 2_000L to """{"type":"progress","kind":"tool","text":"Searching","calls":4}""",
            3_000L to """{"type":"progress","kind":"partial","text":"","partial":"The cheapest is","calls":2}""",
            3_500L to """{"type":"progress","kind":"route","text":"   "}""")
        assertEquals(Working("Searching", 4, 1_000L), w)
    }

    @Test fun theBoardShowsItsLastLine() {
        val w = Hidden.then(started, 2_000L to """{"type":"board","node":"agent","lines":["looked at the screen","tapped Search",""]}""")
        assertEquals("tapped Search", (w as Working).step)
        assertEquals(w, w.then(3_000L to """{"type":"board","node":"agent","lines":[]}"""))
    }

    @Test fun aQuestionIsCarriedSoTheCardCanAnswerIt() {
        val asking = Hidden.then(started, 2_000L to """{"type":"ask","session_id":"s1","thread_id":"t",
            "question":"Which pen pack?","choices":["Pilot, ₹150","Uniball, ₹240","", "Cello, ₹99", "Reynolds"]}""")
        val ask = (asking as Working).ask!!
        assertEquals(OverlayState.Question("s1", "t", "Which pen pack?", listOf("Pilot, ₹150", "Uniball, ₹240", "Cello, ₹99")), ask)
        assertTrue(asking.asking)
        assertFalse(asking.agentHasPhone)
        assertEquals("OTTO · QUESTION · 0:01", asking.meta(2_000L))
        assertEquals("Which pen pack?", asking.words)
        // Answered from the card, or by the turn moving on by itself.
        val answered = asking.answered() as Working
        assertNull(answered.ask)
        assertTrue(answered.agentHasPhone)
        assertEquals("Answered", answered.words)
        assertTrue(asking.then(9_000L to """{"type":"progress","kind":"tool","text":"Adding the red one"}""").agentHasPhone)
        assertEquals(Hidden, Hidden.answered())
    }

    @Test fun theEndOfATurnIsOfferedWithItsFirstLineAndThenExpires() {
        val working = Hidden.then(started, 2_000L to """{"type":"progress","kind":"tool","text":"Scrolling","calls":9}""")
        val done = working.then(49_000L to """{"type":"final","text":"## Added to your cart\n\nThe **Pilot** pens, ₹300."}""")
        assertEquals(Done(Outcome.DONE, "Added to your cart", 9, 48_000L, 49_000L), done)
        assertEquals("DONE · 0:48 · 9 calls", done.meta(50_000L))
        assertFalse(done.agentHasPhone)
        assertFalse(done.expired(49_000L + OverlayState.DONE_SHOWN_MS - 1))
        assertTrue(done.expired(49_000L + OverlayState.DONE_SHOWN_MS))
        val stopped = working.then(5_000L to """{"type":"error","code":"cancelled","message":"stopped by the person"}""")
        assertEquals(Outcome.STOPPED, (stopped as Done).outcome)
        assertEquals("stopped by the person", stopped.words)
        val failed = working.then(5_000L to """{"type":"error","code":"failed","message":""}""")
        assertEquals(Done(Outcome.FAILED, "Something went wrong", 9, 4_000L, 5_000L), failed)
        assertEquals(Working("thinking", 0, 60_000L), done.then(60_000L to started.second))
    }

    /** A tap on the question card opened Otto and cleared the overlay, so nothing came back when the
     *  agent went on working in Amazon (2026-09-16). */
    @Test fun openingOttoToAnswerAQuestionDoesNotEndTheTurn() {
        val asking = Hidden.then(started, 2_000L to """{"type":"ask","thread_id":"t","question":"Which one?"}""")
        assertEquals(asking, asking.opened())
        assertEquals("Otto has a question", ((Hidden.then(started, 2_000L to """{"type":"ask","thread_id":"t"}""")) as Working).ask!!.text)
        val working = Hidden.then(started, 2_000L to """{"type":"progress","kind":"tool","text":"Scrolling"}""")
        assertEquals(working, working.opened())
        val done = working.then(9_000L to """{"type":"final","text":"added"}""")
        assertEquals(Hidden, done.opened())
        assertEquals(Hidden, Hidden.opened())
    }

    @Test fun eventsItDoesNotKnowChangeNothing() {
        val w = Hidden.then(started, 2_000L to """{"type":"progress","kind":"tool","text":"Typing"}""")
        assertEquals(w, w.then(3_000L to """{"type":"cancel_request"}""", 3_000L to """{"type":"usage","calls":3}""", 3_000L to """{"no_type":true}"""))
    }

    @Test fun wordsAreOneLineCutShortAndTimesReadAsAClock() {
        assertEquals("tapped Search in Maps", OverlayState.clean("  tapped\n Search\t in   Maps "))
        assertNull(OverlayState.clean(" \n "))
        val cut = OverlayState.clean("word ".repeat(60))!!
        assertTrue(cut.length <= OverlayState.MAX_STEP && cut.endsWith("…"))
        assertEquals("The cheapest is the Pilot pen", OverlayState.summary("\n```\ncode\n```\n- The cheapest is the `Pilot` pen\nmore"))
        assertNull(OverlayState.summary("  \n "))
        assertEquals("0:05", OverlayState.clock(5_400))
        assertEquals("12:03", OverlayState.clock(723_000))
        assertEquals("1:00:01", OverlayState.clock(3_601_000))
        assertEquals("0:00", OverlayState.clock(-50))
    }
}
