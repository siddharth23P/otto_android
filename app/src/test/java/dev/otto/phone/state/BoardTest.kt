package dev.otto.phone.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Expected values from tests/test_art.py and from agent/cli/art.py run on the same inputs. */
class BoardTest {
    @Test fun boardLinesAreDrawnWithGlyphs() {
        val cases = listOf(
            "solve: execute_bash -> ok" to "◆ execute_bash → [green]ok[/]",
            "plan: read_file src/x.py -> failed" to "☰ read_file src/x.py → [red]failed[/]",
            "escalated to plan mode -- need steps" to "☰ escalated to plan mode -- need steps",
            "compacted 3 older tool result(s)" to "compacted 3 older tool result(s)",
            "find: search ->   timed out" to "⌕ search → [red]timed out[/]",
            "switched to turbo mode" to "switched to turbo mode",
            "looked -> it was done" to "looked → it was [green]done[/]",
            "undone" to "undone",
        )
        for ((line, expected) in cases) assertEquals(line, expected, Board.decorate(line).markup())
    }

    @Test fun decorationSaysWhichWordToColour() {
        val d = Board.decorate("solve: execute_bash -> ok")
        assertEquals("◆ execute_bash → ok", d.text)
        assertEquals(Board.Outcome.OK, d.outcome)
        assertEquals("ok", d.text.substring(d.outcomeRange!!))
        assertNull(Board.decorate("compacted").outcome)
        assertEquals("", Board.decorate(null).text)
    }

    @Test fun modeFromBoardLine() {
        val cases = listOf(
            "escalated to plan mode -- need steps" to "plan",
            "switched to find mode" to "find",
            "de-escalated to summarize mode" to "summarize",
            "solve: execute_bash -> ok" to "solve",
            "compacted 3 older tool result(s)" to null,
            "escalated to turbo mode" to null,
            "" to null,
            "  switched to Find mode  " to "find",
        )
        for ((line, mode) in cases) assertEquals(line, mode, Board.modeFrom(line))
    }

    @Test fun theMeterTurnsRedWhereTheRunIsToldToWrapUp() {
        assertEquals(Board.Level.OK, Board.meter(0, 120)!!.level)
        assertEquals(Board.Level.WARN, Board.meter(80, 120, warnAt = 0.8)!!.level)
        assertEquals(Board.Level.BAD, Board.meter(96, 120, warnAt = 0.8)!!.level)
        assertEquals(Board.METER_FULL.repeat(Board.METER_WIDTH), Board.meter(120, 120)!!.bar)
        assertNull(Board.meter(3, null))
        assertNull(Board.meter(0, 0))
    }

    @Test fun theMeterRoundsHalfToEven() {
        fun bar(filled: Int, width: Int = 12) = "▰".repeat(filled) + "▱".repeat(width - filled)
        val cases = listOf(
            Triple(5, 120, bar(0) to Board.Level.OK), Triple(15, 120, bar(2) to Board.Level.OK), Triple(25, 120, bar(2) to Board.Level.OK),
            Triple(35, 120, bar(4) to Board.Level.OK), Triple(45, 120, bar(4) to Board.Level.OK), Triple(71, 120, bar(7) to Board.Level.OK),
            Triple(72, 120, bar(7) to Board.Level.WARN), Triple(95, 120, bar(10) to Board.Level.WARN), Triple(96, 120, bar(10) to Board.Level.BAD),
            Triple(200, 120, bar(12) to Board.Level.BAD), Triple(-3, 120, bar(0) to Board.Level.OK),
            Triple(1, 8, bar(2) to Board.Level.OK), Triple(3, 8, bar(4) to Board.Level.OK), Triple(5, 8, bar(8) to Board.Level.WARN),
        )
        for ((used, total, expected) in cases) {
            val m = Board.meter(used, total)!!
            assertEquals("$used/$total", expected, m.bar to m.level)
        }
        assertEquals(bar(0, 2), Board.meter(1, 4, width = 2)!!.bar)
        assertEquals(bar(2, 2), Board.meter(3, 4, width = 2)!!.bar)
        assertEquals(bar(0, 4), Board.meter(1, 8, width = 4)!!.bar)
        assertEquals(bar(2, 4), Board.meter(3, 8, width = 4)!!.bar)
    }

    @Test fun theSparklineScalesToThePeakAndKeepsTheTail() {
        assertEquals("▁▅█", Board.sparkline(listOf(0, 50, 100)))
        assertEquals("██", Board.sparkline(listOf(1, 1)))
        assertEquals("", Board.sparkline(emptyList()))
        assertEquals(12, Board.sparkline((0 until 40).toList(), width = 12).length)
        val cases = listOf(
            listOf(1, 14) to "▁█", listOf(5, 14) to "▃█", listOf(3, 14) to "▃█", listOf(9, 14) to "▅█", listOf(0, 0, 0) to "▁▁▁",
            (1..14).toList() to "▃▃▃▄▅▅▅▆▇▇▇█", listOf(2.9, 7.9) to "▃█", listOf(-5, 10) to "▁█",
        )
        for ((values, shown) in cases) assertEquals("$values", shown, Board.sparkline(values))
    }

    @Test fun onlyThePhasesThatMeanSomethingAreColoured() {
        assertNull(Board.phaseLevel("working it out"))
        assertEquals(Board.Level.WARN, Board.phaseLevel("checking the answer"))
        assertEquals(Board.Level.WARN, Board.phaseLevel("Judging"))
        assertEquals(Board.Level.BAD, Board.phaseLevel("stopping after this call"))
        assertNull(Board.phaseLevel(""))
    }
}
