package dev.otto.phone.state

/** agent/cli/art.py's pure parts: board-line decoration, mode inference, the budget meter, the
 *  per-turn sparkline and the phase colouring. A Python `str` pattern's `\w`, `\b` and `\s` are
 *  Unicode-aware; here they are spelled out as classes (`WORD`, `SPACE`) rather than with `(?U)`,
 *  which desktop Java accepts and Android's ICU regex engine rejects -- it crashed the app on
 *  launch on a Galaxy S23 (2026-09-16) while every JVM test passed. */
object Board {
    val MODE_GLYPHS: Map<String, String> = mapOf("solve" to "◆", "plan" to "☰", "summarize" to "≣", "find" to "⌕")

    const val BULLET_ANSWER = "●"
    const val BULLET_STREAMING = "○"
    const val BULLET_THINKING = "◇"
    const val BULLET_THOUGHT = "◆"
    const val PROMPT_GLYPH = "›"

    const val METER_WIDTH = 12
    const val METER_FULL = "▰"
    const val METER_EMPTY = "▱"
    const val SPARK_GLYPHS = "▁▂▃▄▅▆▇█"

    /** Python's Unicode `\w` and `\s`, written so both JVM and ICU read them the same way. */
    private const val WORD = """[\p{L}\p{N}_]"""
    private const val SPACE = """[\s\p{Z}]"""
    private val MODE_SWITCH = Regex("""^(?:escalated|switched|de-escalated) to ($WORD+) mode""")
    private val MODE_PREFIX = Regex("""^(solve|plan|summarize|find): """)
    private val ARROW = Regex("""$SPACE+->$SPACE+""")
    // `\b(word)\b$`: a word boundary before the word; the one before `$` always holds after a letter.
    private val OUTCOME_OK = Regex("""(?<!$WORD)(ok|approved|passed|done)$""")
    private val OUTCOME_BAD = Regex("""(?<!$WORD)(failed|error|rejected|refused|timed out)$""")

    enum class Outcome { OK, BAD }

    /** A decorated board line: plain text for the UI, plus which word (if any) is the outcome to
     *  colour. `markup()` is the exact string `decorate_board_line` returns. */
    data class Decorated(val text: String, val outcome: Outcome? = null, val outcomeRange: IntRange? = null) {
        fun markup(): String {
            val range = outcomeRange ?: return text
            val tag = if (outcome == Outcome.OK) "green" else "red"
            return text.replaceRange(range, "[$tag]${text.substring(range)}[/]")
        }
    }

    /** `decorate_board_line`: a mode prefix becomes its glyph, `->` an arrow, and a trailing outcome
     *  word is marked. */
    fun decorate(line: String?): Decorated {
        var text = line ?: ""
        MODE_PREFIX.find(text)?.let { m -> text = "${MODE_GLYPHS.getValue(m.groupValues[1])} ${text.substring(m.range.last + 1)}" }
        MODE_SWITCH.find(text)?.let { m -> MODE_GLYPHS[m.groupValues[1]]?.let { glyph -> text = "$glyph $text" } }
        text = ARROW.replace(text, " → ")
        OUTCOME_OK.find(text)?.let { m -> return Decorated(text, Outcome.OK, m.groups[1]!!.range) }
        OUTCOME_BAD.find(text)?.let { m -> return Decorated(text, Outcome.BAD, m.groups[1]!!.range) }
        return Decorated(text)
    }

    /** `mode_from_board_line`: the mode a board line announces, or null. */
    fun modeFrom(line: String?): String? {
        val text = (line ?: "").trim()
        val match = MODE_SWITCH.find(text) ?: MODE_PREFIX.find(text) ?: return null
        return match.groupValues[1].lowercase().takeIf { it in MODE_GLYPHS }
    }

    enum class Level { OK, WARN, BAD }

    data class Meter(val bar: String, val level: Level)

    /** `meter`: `used` of `total` as a bar; null when there is no ceiling. Level follows the
     *  Python's green / yellow / bold red. */
    fun meter(used: Int, total: Int?, width: Int = METER_WIDTH, warnAt: Double = 0.8): Meter? {
        if (total == null || total <= 0) return null
        val fraction = (used.toDouble() / total).coerceIn(0.0, 1.0)
        val filled = Format.pyRound(fraction * width)
        val level = if (fraction < 0.6) Level.OK else if (fraction < warnAt) Level.WARN else Level.BAD
        return Meter(METER_FULL.repeat(filled) + METER_EMPTY.repeat(width - filled), level)
    }

    /** `sparkline`: the last `width` values as bars scaled to the largest. */
    fun sparkline(values: List<Number>, width: Int = 12): String {
        val tail = values.takeLast(width).map { maxOf(0L, it.toDouble().toLong()) }
        if (tail.isEmpty()) return ""
        val peak = tail.max().takeIf { it != 0L } ?: 1L
        val top = SPARK_GLYPHS.length - 1
        return tail.joinToString("") { v -> SPARK_GLYPHS[minOf(top, Format.pyRound(v.toDouble() / peak * top))].toString() }
    }

    /** `phase_style`: which phases mean something. */
    fun phaseLevel(phase: String?): Level? {
        val lowered = (phase ?: "").lowercase()
        return when {
            lowered.startsWith("checking") || lowered.startsWith("judging") -> Level.WARN
            lowered.startsWith("stopping") -> Level.BAD
            else -> null
        }
    }
}
