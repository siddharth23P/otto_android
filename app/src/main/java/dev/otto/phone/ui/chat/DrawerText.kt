package dev.otto.phone.ui.chat

import dev.otto.phone.protocol.ModelUsage
import dev.otto.phone.protocol.SessionRow
import dev.otto.phone.protocol.UsageSnapshot
import dev.otto.phone.state.Board
import dev.otto.phone.state.Format
import java.time.Instant

/** The sessions drawer's words: rows, the current session's line, usage per model and in total.
 *  Unknown costs and unreported tokens read "—", never zero. */
object DrawerText {
    const val DASH = "—"
    const val NO_USAGE = "No usage yet"
    const val NO_SESSIONS = "No sessions yet — the first message starts one."
    const val ESTIMATE = "est. — costs are estimated from each provider's published prices."

    private fun turns(n: Int) = "$n turn${if (n == 1) "" else "s"}"

    /** Under a session's title: "01234567 · 3 turns · 5m ago", and "this one" for the open session. */
    fun sessionMeta(row: SessionRow, current: Boolean, now: Instant = Instant.now()): String {
        val shortId = row.shortId.ifEmpty { row.id.take(8) }
        val age = row.age.ifEmpty { runCatching { Format.describeAge(row.lastActiveAt, now) }.getOrDefault("") }
        return listOfNotNull(shortId.ifBlank { null }, turns(row.turns), age.ifBlank { null }, if (current) "this one" else null).joinToString(" · ")
    }

    /** The open session's line: "3 turns · ☰ plan · mercury-2". */
    fun currentMeta(turnCount: Int, mode: String?, model: String?): String =
        listOfNotNull(turns(turnCount), Board.MODE_GLYPHS[mode]?.let { "$it $mode" }, model?.ifBlank { null }?.let(ChatText::model)).joinToString(" · ")

    /** One model's share: "3 req · 1.2k tok · $0.004". */
    fun usageLine(row: ModelUsage): String =
        "${row.calls} req · ${if (row.reported) Format.thousands(row.totalTokens) + " tok" else DASH} · ${Format.formatCost(row.cost, DASH)}"

    /** The session's total, with "+" when some of it could not be priced. */
    fun totalsLine(usage: UsageSnapshot): String {
        val plus = if (!usage.fullyPriced && usage.cost != null) "+" else ""
        return "${usage.calls} req · ${Format.thousands(usage.totalTokens)} tok · ${Format.formatCost(usage.cost, DASH)}$plus"
    }

    /** What TalkBack says for the sparkline: the numbers, not the bars. */
    fun sparkDescription(values: List<Long>): String =
        "tokens per turn: " + values.takeLast(12).joinToString(", ") { Format.thousands(it) }
}
