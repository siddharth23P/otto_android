package dev.otto.phone.access

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * What the agent-at-work overlay shows, folded from the agent's events. Pure (the clock is given), so which
 * events move it, and what its card says, are tested on the JVM; [AgentOverlay] only draws it.
 *
 *  - [Working]: a turn is running -- the step it last said, the calls so far, when it started, and
 *    whether it is waiting on a question for the person.
 *  - [Done]: the turn ended -- how, the first line of its answer, and what it cost; offered for
 *    [DONE_SHOWN_MS] with a way back to Otto, then gone.
 *  - [Hidden]: nothing to show.
 */
sealed interface OverlayState {
    object Hidden : OverlayState

    data class Working(val step: String, val calls: Int, val startedAt: Long, val asking: Boolean = false) : OverlayState

    data class Done(val outcome: Outcome, val summary: String, val calls: Int, val elapsedMs: Long, val at: Long) : OverlayState

    enum class Outcome(val label: String) { DONE("DONE"), STOPPED("STOPPED"), FAILED("FAILED") }

    fun after(event: JsonObject, now: Long): OverlayState {
        val working = this as? Working
        return when (event.str("type")) {
            "started" -> Working(step = "thinking", calls = 0, startedAt = now)
            "progress" -> working?.let { w ->
                val calls = maxOf(w.calls, event.int("calls") ?: 0)
                // A partial is the answer being written, not a step: the card keeps saying what was last done.
                val step = if (event.str("kind") == "partial") null else clean(event.str("text"))
                w.copy(step = step ?: w.step, calls = calls, asking = if (step != null) false else w.asking)
            } ?: this
            "board" -> working?.let { w ->
                (event["lines"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.lastOrNull { it.isNotBlank() }?.let(::clean)?.let { w.copy(step = it, asking = false) }
            } ?: this
            "ask" -> working?.copy(asking = true) ?: this
            "final" -> working?.let { w ->
                Done(Outcome.DONE, summary(event.str("text")) ?: "Finished", w.calls, now - w.startedAt, now)
            } ?: Hidden
            "error" -> working?.let { w ->
                val stopped = event.str("code") == "cancelled"
                Done(if (stopped) Outcome.STOPPED else Outcome.FAILED,
                    clean(event.str("message")) ?: if (stopped) "Stopped" else "Something went wrong",
                    w.calls, now - w.startedAt, now)
            } ?: Hidden
            else -> this
        }
    }

    /** The done card has been offered long enough. */
    fun expired(now: Long): Boolean = this is Done && now - at >= DONE_SHOWN_MS

    /** Whether the edge layer -- the sign the agent has the phone -- is up. */
    val agentHasPhone: Boolean get() = this is Working && !asking

    /** The card's mono line: who, how long, how many calls. */
    fun meta(now: Long): String = when (this) {
        Hidden -> ""
        is Working -> listOfNotNull(if (asking) "OTTO · QUESTION" else "OTTO", clock(now - startedAt), callsText(calls)).joinToString(" · ")
        is Done -> listOfNotNull(outcome.label, clock(elapsedMs), callsText(calls)).joinToString(" · ")
    }

    /** The card's words. */
    val words: String get() = when (this) {
        Hidden -> ""
        is Working -> if (asking) "Otto has a question for you -- open Otto to answer" else step.replaceFirstChar { it.uppercase() }
        is Done -> summary
    }

    companion object {
        /** How long the done card is offered before it goes by itself. */
        const val DONE_SHOWN_MS = 15_000L
        /** About two lines of the card. */
        const val MAX_STEP = 100
        /** The done card's one line of answer. */
        const val MAX_SUMMARY = 120
        private val WHITESPACE = Regex("\\s+")
        private val MARKDOWN = Regex("^(#{1,6}\\s+|>\\s*|[-*+]\\s+|\\d+[.)]\\s+)")
        private val EMPHASIS = Regex("\\*\\*|__|`+|~~")

        /** One run of plain text: whitespace collapsed, cut at `max` with an ellipsis. Null when blank. */
        fun clean(raw: String?, max: Int = MAX_STEP): String? {
            val flat = raw?.trim()?.replace(WHITESPACE, " ")?.takeIf { it.isNotEmpty() } ?: return null
            return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
        }

        /** The answer's first line of prose -- outside any fenced code block -- with a heading, quote or list
         *  marker and emphasis taken off. */
        fun summary(answer: String?): String? {
            var fenced = false
            for (raw in answer?.lineSequence() ?: return null) {
                val line = raw.trim()
                if (line.startsWith("```")) { fenced = !fenced; continue }
                if (fenced || line.isEmpty()) continue
                return clean(line.replace(MARKDOWN, "").replace(EMPHASIS, ""), MAX_SUMMARY)
            }
            return null
        }

        /** m:ss, or h:mm:ss past an hour. */
        fun clock(ms: Long): String {
            val s = (ms.coerceAtLeast(0) / 1000)
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }

        private fun callsText(calls: Int): String? = when {
            calls <= 0 -> null
            calls == 1 -> "1 call"
            else -> "$calls calls"
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    }
}
