package dev.otto.phone.state

import dev.otto.phone.protocol.ModelUsage
import dev.otto.phone.protocol.SessionRow
import dev.otto.phone.protocol.UsageSnapshot
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** The TUI's formatting, ported so the app says numbers the way the terminal does. Each function
 *  names its Python original; the JVM tests hold them to the Python's own test vectors, including
 *  Python's rounding (a float formats from its exact binary value, and `round` is half-to-even). */
object Format {
    /** Python's f"{x:.Nf}": the exact binary value, rounded half-to-even, sign kept on a negative zero. */
    fun fixed(x: Double, digits: Int): String {
        val body = BigDecimal(Math.abs(x)).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()
        val negative = x < 0 || (x == 0.0 && 1.0 / x < 0)
        return if (negative) "-$body" else body
    }

    /** Python's round(x) on a float: half-to-even. */
    fun pyRound(x: Double): Int = Math.rint(x).toInt()

    // -- agent/cli/usage_panel.py ------------------------------------------------------------------

    /** `_thousands`: 1234567 -> "1.23M". */
    fun thousands(n: Long): String = when {
        n >= 1_000_000 -> fixed(n / 1_000_000.0, 2) + "M"
        n >= 1_000 -> fixed(n / 1_000.0, 1) + "k"
        else -> n.toString()
    }

    private val ID_PREFIXES = setOf(
        "us", "eu", "apac", "global",
        "anthropic", "openai", "google", "meta", "mistral", "cohere",
        "amazon", "bedrock", "azure", "inception",
    )

    private fun String.pyIsDigit() = isNotEmpty() && all { it.isDigit() }

    /** `_short_model`: "us.anthropic.claude-sonnet-4-20250514-v1:0" -> "claude-sonnet-4". */
    fun shortModel(name: String?): String {
        var tail = (name ?: "").split("/").last()
        val segments = tail.split(".").toMutableList()
        while (segments.size > 1 && segments[0].lowercase() in ID_PREFIXES) segments.removeAt(0)
        tail = segments.joinToString(".")
        val parts = tail.split("-").toMutableList()
        while (parts.size > 2) {
            val last = parts.last()
            val stamp = (last.pyIsDigit() && last.length >= 6) || (last.startsWith("v") && last.drop(1).pyIsDigit()) || last.endsWith(":0")
            if (!stamp) break
            parts.removeAt(parts.size - 1)
        }
        return parts.joinToString("-").ifEmpty { tail }.ifEmpty { "unknown" }
    }

    /** One model's line under its name: "3 req · 1.2k · $0.004" ("--" tokens when unreported). */
    fun modelUsageLine(row: ModelUsage): String =
        "${row.calls} req · ${if (row.reported) thousands(row.totalTokens) else "--"} · ${formatCost(row.cost)}"

    // -- agent/pipeline/pricing.py -----------------------------------------------------------------

    /** `format_cost`. `unknown` is what None reads as: the TUI's "--"; the app passes "—". */
    fun formatCost(amount: Double?, unknown: String = "--"): String = when {
        amount == null -> unknown
        amount != 0.0 && amount < 0.01 -> "$" + fixed(amount, 4)
        amount < 10 -> "$" + fixed(amount, 3)
        else -> "$" + fixed(amount, 2)
    }

    // -- agent/cli/tui.py --------------------------------------------------------------------------

    /** `_clock`: seconds as m:ss, truncated like int() and floored like // and %. */
    fun clock(seconds: Double): String {
        val s = seconds.toLong()
        return "${Math.floorDiv(s, 60L)}:${Math.floorMod(s, 60L).toString().padStart(2, '0')}"
    }

    /** `_draw_topbar`'s right-hand side: "3 req · 1.2k tok · $0.004" (+ when partly unpriced). */
    fun spendLine(usage: UsageSnapshot, tokens: Long = usage.totalTokens, cost: Double = usage.cost ?: 0.0): String =
        if (usage.calls != 0) "${usage.calls} req · ${thousands(tokens)} tok · ${formatCost(cost)}${if (!usage.fullyPriced) "+" else ""}"
        else "nothing spent yet"

    /** `_session_line`: "0123abcd  title  ·  3 turn(s)  ·  workspace  ·  5m ago ◂ this one". */
    fun sessionLine(row: SessionRow, current: String? = null, now: Instant = Instant.now()): String {
        val where = row.workspace?.takeIf { it.isNotEmpty() }?.substringAfterLast("/") ?: "no workspace"
        val mark = if (row.id == current) " ◂ this one" else ""
        val shortId = row.shortId.ifEmpty { row.id.take(8) }
        val label = row.title.ifEmpty { "(untitled)" }
        val age = row.age.ifEmpty { runCatching { describeAge(row.lastActiveAt, now) }.getOrDefault("") }
        return "$shortId  $label  ·  ${row.turns} turn(s)  ·  $where  ·  $age$mark"
    }

    /** agent/memory/sessions.py `describe_age`: "just now", "5m ago", "3h ago", "2d ago". A timestamp
     *  without an offset is UTC. */
    fun describeAge(iso: String, now: Instant = Instant.now()): String {
        val then = runCatching { OffsetDateTime.parse(iso).toInstant() }
            .getOrElse { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC) }
        val seconds = maxOf(0L, Duration.between(then, now).seconds)
        return when {
            seconds < 60 -> "just now"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86400}d ago"
        }
    }

    /** `_stream_answer`: a streamed reply is a tool call until FINAL: appears; after it, the answer. */
    fun textAfterFinal(partial: String): String? {
        val marker = partial.indexOf("FINAL:")
        if (marker < 0) return null
        return partial.substring(marker + "FINAL:".length).trim()
    }

    /** `_close_thinking`'s title: "◆ plan · ◆ thought for 0:12 · 5 steps · 3 model calls". */
    fun thoughtTitle(mode: String?, steps: Int, calls: Int, elapsed: Double): String {
        val parts = mutableListOf<String>()
        if (steps != 0) parts += "$steps steps"
        if (calls != 0) parts += "$calls model calls"
        if (elapsed != 0.0) parts += clock(elapsed)
        val stamp = clock(elapsed)
        val ordered = (if (elapsed != 0.0) listOf(stamp) else emptyList()) + parts.filter { !it.endsWith(stamp) }
        val prefix = modePrefix(mode)
        return if (ordered.isNotEmpty()) "$prefix${Board.BULLET_THOUGHT} thought for ${ordered.joinToString(" · ")}"
        else "$prefix${Board.BULLET_THOUGHT} thought"
    }

    /** `_mode_prefix`: "☰ plan · " for a known mode, nothing otherwise. */
    fun modePrefix(mode: String?): String = Board.MODE_GLYPHS[mode]?.let { "$it $mode · " } ?: ""
}
