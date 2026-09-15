package dev.otto.phone.ui.chat

import dev.otto.phone.state.ChatBlock
import dev.otto.phone.state.Format
import dev.otto.phone.state.TurnUi
import dev.otto.phone.state.Where
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the chat says, as pure functions: labels, meta rows, day separators. No Compose, so the JVM
 *  tests hold the words to the web app's. */
object ChatText {
    const val PLACEHOLDER = "Message otto…"
    const val WORKING = "working…"
    const val UNKNOWN_COST = "—"
    const val EMPTY_TITLE = "Nothing here yet."
    const val EMPTY_BODY = "Ask for something on your phone, or for something written. Try one of these:"
    val EMPTY_SUGGESTIONS = listOf(
        "Write a short research note on how Otto decides to use the phone",
        "Turn on dark mode",
        "Explain what you can do on this phone",
    )

    private val DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    private val SPEC_PREFIX = Regex("^[a-z0-9_]+:(?=.)")

    /** A model as the app names it: otto's "provider:id" spec loses its provider, then the TUI's
     *  `_short_model` drops vendor prefixes and date stamps. "inception:mercury-2" -> "mercury-2". */
    fun model(spec: String): String = Format.shortModel(spec.replaceFirst(SPEC_PREFIX, ""))

    /** `fmtDay`: "today", "yesterday", or "Sep 15, 2026". */
    fun dayLabel(atMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return when (day) {
            today -> "today"
            today.minusDays(1) -> "yesterday"
            else -> DAY.format(day)
        }
    }

    fun day(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()

    fun clock(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = CLOCK.format(Instant.ofEpochMilli(atMs).atZone(zone))

    /** The mono meta row above a message: author, HH:MM, short model, cost in dollars ("—" unpriced).
     *  Restored messages have no time, model or cost, and say only who spoke. */
    fun meta(block: ChatBlock, zone: ZoneId = ZoneId.systemDefault()): List<String> = when (block) {
        is ChatBlock.User -> listOfNotNull("you", block.atMs?.let { clock(it, zone) })
        is ChatBlock.Otto -> listOfNotNull("otto", block.atMs?.let { clock(it, zone) }) + turnMeta(block.turn)
        is ChatBlock.System -> listOfNotNull("otto", block.atMs?.let { clock(it, zone) }) + turnMeta(block.turn)
        is ChatBlock.Earlier -> listOf("earlier")
    }

    private fun turnMeta(turn: TurnUi?): List<String> {
        if (turn == null) return emptyList()
        return listOfNotNull(turn.model.ifBlank { null }?.let(ChatText::model), Format.formatCost(turn.cost?.cost, UNKNOWN_COST))
    }

    /** The trace fold's label: "working…" while it runs, then who answered, or what went wrong. */
    fun traceLabel(turn: TurnUi, systemText: String? = null): String = when {
        turn.running -> WORKING
        turn.stopped -> "stopped"
        turn.failed -> systemText?.ifBlank { null } ?: "failed"
        turn.model.isNotBlank() -> "answered by ${model(turn.model)}"
        else -> "answered"
    }

    /** The composer chip for the turn's phone decision, or null before otto decided. */
    fun whereChip(where: Where?): String? = when (where) {
        Where.PHONE -> "on your phone"
        Where.HERE -> "answering here"
        null -> null
    }

    fun modelChip(turn: TurnUi?): String = turn?.model?.ifBlank { null }?.let(::model) ?: "auto"

    /** A message quoted into the composer, Markdown-style, ready for the reply under it. */
    fun quote(text: String): String = text.trim().lines().joinToString("\n") { "> $it" } + "\n\n"

    /** A tool row in the trace: "phone_tap · running · Add to Cart". */
    fun toolLine(tool: String, running: Boolean): String {
        val name = tool.substringBefore(' ')
        val detail = tool.substringAfter(' ', "").ifBlank { null }
        return listOfNotNull(name, if (running) "running" else "done", detail).joinToString(" · ")
    }

    /** The transcript as rows: a day pill before the first message of each day that has a time. */
    sealed interface Row {
        data class Day(val label: String) : Row
        data class Message(val index: Int, val block: ChatBlock) : Row
    }

    fun rows(blocks: List<ChatBlock>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Row> {
        val out = ArrayList<Row>(blocks.size + 2)
        var lastDay: LocalDate? = null
        blocks.forEachIndexed { i, b ->
            val at = when (b) {
                is ChatBlock.User -> b.atMs
                is ChatBlock.Otto -> b.atMs
                is ChatBlock.System -> b.atMs
                is ChatBlock.Earlier -> null
            }
            if (at != null) {
                val d = day(at, zone)
                if (d != lastDay) { out += Row.Day(dayLabel(at, nowMs, zone)); lastDay = d }
            }
            out += Row.Message(i, b)
        }
        return out
    }

    /** The phase line under the top bar while a turn runs: "thinking · 3 calls · 0:12". */
    fun statusLine(turn: TurnUi, nowMs: Long): String {
        val parts = mutableListOf(turn.phase.ifBlank { "thinking" })
        if (turn.calls > 0) parts += "${turn.calls} call${if (turn.calls == 1) "" else "s"}"
        parts += Format.clock(turn.elapsedSeconds(nowMs))
        return parts.joinToString(" · ")
    }
}
