package dev.otto.phone

import dev.otto.phone.access.StatusLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusLineTest {
    private fun event(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun StatusLine.then(vararg events: String) = events.fold(this) { line, e -> line.after(event(e)) }
    private val started = """{"type":"started","session_id":"s1"}"""

    @Test fun nothingIsShownOutsideATurn() {
        assertNull(StatusLine().text)
        assertNull(StatusLine().then("""{"type":"final","text":"done"}""").text)
    }

    @Test fun aStartedTurnShowsThinking() {
        assertEquals("Otto: thinking", StatusLine().then(started).text)
    }

    @Test fun progressShowsItsText() {
        val line = StatusLine().then(started, """{"type":"progress","kind":"tool","text":"Opening Chrome","calls":2}""")
        assertEquals("Otto: Opening Chrome", line.text)
    }

    @Test fun aPartialAnswerOrABlankStepLeavesTheLastStep() {
        val line = StatusLine().then(started, """{"type":"progress","kind":"tool","text":"Searching"}""",
            """{"type":"progress","kind":"partial","text":"","partial":"The weather is"}""",
            """{"type":"progress","kind":"route","text":"   "}""")
        assertEquals("Otto: Searching", line.text)
    }

    @Test fun theBoardShowsItsLastLine() {
        val line = StatusLine().then(started, """{"type":"board","node":"agent","lines":["looked at the screen","tapped Search",""]}""")
        assertEquals("Otto: tapped Search", line.text)
        assertEquals(line, line.then("""{"type":"board","node":"agent","lines":[]}"""))
    }

    @Test fun aFinalAnswerOrAnErrorHidesIt() {
        val working = StatusLine().then(started, """{"type":"progress","kind":"tool","text":"Scrolling"}""")
        assertNull(working.then("""{"type":"final","text":"done"}""").text)
        assertNull(working.then("""{"type":"error","code":"cancelled","message":"stopped"}""").text)
    }

    @Test fun aQuestionSaysSo() {
        val line = StatusLine().then(started, """{"type":"ask","thread_id":"t","question":"Which one?","choices":[]}""")
        assertEquals("Otto: has a question for you", line.text)
        assertNull(StatusLine().then("""{"type":"ask","thread_id":"t","question":"Which one?"}""").text)
    }

    @Test fun eventsItDoesNotKnowChangeNothing() {
        val line = StatusLine().then(started, """{"type":"progress","kind":"tool","text":"Typing"}""")
        assertEquals(line, line.then("""{"type":"usage","calls":3}""", """{"no_type":true}"""))
    }

    @Test fun stepsAreOneLineAndCutToAboutTwoLines() {
        assertEquals("tapped Search in Maps", StatusLine.clean("  tapped\n Search\t in   Maps "))
        assertNull(StatusLine.clean(" \n "))
        val cut = StatusLine.clean("word ".repeat(60))!!
        assertTrue(cut.length <= StatusLine.MAX_STEP)
        assertTrue(cut.endsWith("…"))
        val line = StatusLine().then(started, """{"type":"progress","kind":"tool","text":"${"x".repeat(300)}"}""")
        assertEquals("Otto: " + "x".repeat(StatusLine.MAX_STEP - 1) + "…", line.text)
    }
}
