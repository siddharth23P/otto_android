package dev.otto.phone.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regex features desktop Java accepts and Android's ICU engine rejects at runtime. A JVM test
 *  cannot compile them the way the phone does, so the sources are checked for them instead:
 *  `(?U)` in Board.kt passed every JVM test and crashed the app on launch (2026-09-16). */
class AndroidRegexTest {
    private val forbidden = listOf("(?U", "UNICODE_CHARACTER_CLASS")

    @Test fun noSourceUsesARegexFlagAndroidCannotCompile() {
        val offenders = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> file.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) null
                else forbidden.firstOrNull { it in code }?.let { "${file.path}:${i + 1}: $it" }
            } }.toList()
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun boardStillReadsUnicodeWordsAndSpacesTheWayPythonDoes() {
        assertEquals("plan", Board.modeFrom("switched to plan mode -- the task has parts"))
        assertEquals(Board.Outcome.OK, Board.decorate("solve: phone_act tap\u00A0->\u00A0done").outcome)
        assertEquals(null, Board.decorate("solve: read the notebook").outcome)
        assertEquals(Board.Outcome.BAD, Board.decorate("évaluation refused").outcome)
        assertEquals(null, Board.decorate("éfailed").outcome)
    }
}
