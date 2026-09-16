package dev.otto.phone.ui.memory

import dev.otto.phone.protocol.LessonRow
import dev.otto.phone.protocol.NoteSummary
import dev.otto.phone.state.Board
import dev.otto.phone.state.LessonTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LessonsTextTest {
    @Test fun lessonRowsReadCueActionOutcome() {
        val row = LessonRow(id = "a".repeat(64), cue = "amazon search results", action = "tap Add to Cart", outcome = "worked", text = "raw")
        assertEquals("amazon search results", LessonsText.cue(row))
        assertEquals("→ tap Add to Cart", LessonsText.action(row))
        assertEquals("amazon search results, then tap Add to Cart, worked", LessonsText.describe(row))
        val raw = LessonRow(text = "when stuck, go back")
        assertEquals("when stuck, go back", LessonsText.cue(raw))
        assertNull(LessonsText.action(raw))
        assertEquals(Board.Level.OK, LessonsText.outcomeLevel("worked"))
        assertEquals(Board.Level.BAD, LessonsText.outcomeLevel("didn't work: the button moved"))
        assertNull(LessonsText.outcomeLevel("partly"))
        assertNull(LessonsText.outcomeLevel(null))
    }

    @Test fun tabsNotesAndClear() {
        assertEquals(listOf("lessons", "phone lessons", "app notes"), LessonTab.entries.map(LessonsText::tab))
        assertEquals("shipped note · 2 learned", LessonsText.noteMeta(NoteSummary("in.amazon", seeded = true, learned = 2)))
        assertEquals("1 learned", LessonsText.noteMeta(NoteSummary("x", seeded = false, learned = 1)))
        assertEquals("empty", LessonsText.noteMeta(NoteSummary("x")))
        assertEquals("clear all (3)", LessonsText.clearLabel(3, false))
        assertEquals("sure? clear 3", LessonsText.clearLabel(3, true))
    }
}
