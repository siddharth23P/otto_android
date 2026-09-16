package dev.otto.phone.ui.chat

import dev.otto.phone.protocol.TurnTotals
import dev.otto.phone.state.ChatBlock
import dev.otto.phone.state.TurnUi
import dev.otto.phone.state.Where
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ChatTextTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()
    private val now = at(2026, 9, 15, 18, 30)

    @Test fun daysReadLikeTheWeb() {
        assertEquals("today", ChatText.dayLabel(at(2026, 9, 15, 0, 1), now, zone))
        assertEquals("yesterday", ChatText.dayLabel(at(2026, 9, 14, 23, 59), now, zone))
        assertEquals("Sep 1, 2026", ChatText.dayLabel(at(2026, 9, 1, 9, 0), now, zone))
    }

    @Test fun aDayPillStartsEachDayThatHasTimes() {
        val blocks = listOf(
            ChatBlock.Earlier("before"), ChatBlock.Otto("restored"),
            ChatBlock.User("a", at(2026, 9, 14, 22, 0)), ChatBlock.Otto("b", null, at(2026, 9, 14, 22, 1)),
            ChatBlock.User("c", at(2026, 9, 15, 9, 0)),
        )
        val rows = ChatText.rows(blocks, now, zone)
        assertEquals(
            listOf("m0", "m1", "day:yesterday", "m2", "m3", "day:today", "m4"),
            rows.map { if (it is ChatText.Row.Day) "day:${it.label}" else "m${(it as ChatText.Row.Message).index}" },
        )
    }

    @Test fun metaSaysWhoWhenWhichModelAndWhatItCost() {
        val turn = TurnUi(running = false, model = "us.anthropic.claude-sonnet-4-20250514-v1:0", cost = TurnTotals(900, 3, 0.004))
        assertEquals(listOf("otto", "18:30", "claude-sonnet-4", "$0.0040"), ChatText.meta(ChatBlock.Otto("hi", turn, now), zone))
        assertEquals(listOf("otto", "18:30", "gemini-3-flash", "—"), ChatText.meta(ChatBlock.Otto("hi", TurnUi(running = false, model = "gemini:gemini-3-flash"), now), zone))
        assertEquals(listOf("you", "18:30"), ChatText.meta(ChatBlock.User("hi", now), zone))
        assertEquals(listOf("otto"), ChatText.meta(ChatBlock.Otto("restored"), zone))
    }

    @Test fun theTraceLabelFollowsTheTurn() {
        assertEquals("working…", ChatText.traceLabel(TurnUi()))
        assertEquals("answered by mercury-2", ChatText.traceLabel(TurnUi(running = false, model = "inception:mercury-2")))
        assertEquals("answered", ChatText.traceLabel(TurnUi(running = false)))
        assertEquals("stopped", ChatText.traceLabel(TurnUi(running = false, stopped = true)))
        assertEquals("401 from inception", ChatText.traceLabel(TurnUi(running = false, failed = true), "401 from inception"))
        assertEquals("failed", ChatText.traceLabel(TurnUi(running = false, failed = true), ""))
    }

    @Test fun talkBackHearsWordsNotGlyphs() {
        assertEquals("answered by mercury-2, plan, thought for 0:12, 5 steps", ChatText.spoken("answered by mercury-2 · ☰ plan · ◆ thought for 0:12 · 5 steps"))
        assertEquals("phone_tap to ok", ChatText.spoken(dev.otto.phone.state.Board.decorate("solve: phone_tap -> ok").text))
    }

    @Test fun chipsQuotesAndToolRows() {
        assertEquals("on your phone", ChatText.whereChip(Where.PHONE))
        assertEquals("answering here", ChatText.whereChip(Where.HERE))
        assertNull(ChatText.whereChip(null))
        assertEquals("auto", ChatText.modelChip(null))
        assertEquals("mercury-2", ChatText.modelChip(TurnUi(model = "inception:mercury-2")))
        assertEquals("> one\n> two\n\n", ChatText.quote(" one\ntwo "))
        assertEquals("phone_tap · running · Add to Cart", ChatText.toolLine("phone_tap Add to Cart", true))
        assertEquals("read_screen · done", ChatText.toolLine("read_screen", false))
        assertEquals("thinking · 2 calls · 0:12", ChatText.statusLine(TurnUi(startedAtMs = 0, calls = 2), 12_400))
        assertEquals("answering here · 1 call · 0:01", ChatText.statusLine(TurnUi(startedAtMs = 0, calls = 1, phase = "answering here"), 1_000))
    }
}
