package dev.otto.phone.state

import dev.otto.phone.protocol.AgentEvent
import dev.otto.phone.protocol.DocumentInfo
import dev.otto.phone.protocol.SessionUsage
import dev.otto.phone.protocol.Transcript
import dev.otto.phone.protocol.TurnTotals
import dev.otto.phone.protocol.UsageSnapshot
import dev.otto.phone.protocol.str

/** Where a turn ran: on the phone, or answered on the computer running otto. Null until decided. */
enum class Where { PHONE, HERE }

/** Everything one turn shows while it runs, and what its folded thinking block says afterwards. */
data class TurnUi(
    val running: Boolean = true,
    val startedAtMs: Long = 0,
    val endedAtMs: Long? = null,
    val phase: String = "",
    val mode: String? = null,
    val tool: String = "",
    val model: String = "",
    val calls: Int = 0,
    val budgetMax: Int? = null,
    /** Graph updates, counted the way the TUI counts "5 steps". */
    val steps: Int = 0,
    /** Board lines as the pipeline wrote them; decorate with Board.decorate when drawing. */
    val lines: List<String> = emptyList(),
    /** What the phone's guard refused or noted during this turn. */
    val guardNotes: List<String> = emptyList(),
    /** The answer so far (text after FINAL:), or null before there is one. */
    val streaming: String? = null,
    val lastPartialAtMs: Long? = null,
    val answer: String? = null,
    val document: DocumentInfo? = null,
    val cost: TurnTotals? = null,
    val where: Where? = null,
    val stopped: Boolean = false,
    val failed: Boolean = false,
) {
    fun elapsedSeconds(nowMs: Long): Double = ((endedAtMs ?: nowMs) - startedAtMs).coerceAtLeast(0) / 1000.0

    /** "◆ thought for 0:12 · 5 steps · 3 model calls" once the turn is over. */
    fun thoughtTitle(nowMs: Long): String = Format.thoughtTitle(mode, steps, calls, elapsedSeconds(nowMs))

    val meter: Board.Meter? get() = Board.meter(calls, budgetMax)

    companion object {
        const val MAX_LINES = 400
    }
}

data class AskUi(val threadId: String, val question: String, val choices: List<String>)

sealed interface ChatBlock {
    /** The compacted part of a resumed session, as text. */
    data class Earlier(val text: String) : ChatBlock
    data class User(val text: String, val atMs: Long? = null) : ChatBlock
    /** Otto's answer (Markdown). `turn` is null for answers restored from a transcript. */
    data class Otto(val text: String, val turn: TurnUi? = null, val atMs: Long? = null) : ChatBlock
    /** An error or a stop, in the server's own words. */
    data class System(val text: String, val code: String, val turn: TurnUi? = null, val atMs: Long? = null) : ChatBlock
}

data class ChatState(
    val sessionId: String = "",
    val title: String = "",
    val turns: Int = 0,
    val blocks: List<ChatBlock> = emptyList(),
    /** The turn running now; it becomes part of a block when it ends. */
    val turn: TurnUi? = null,
    val ask: AskUi? = null,
    val usage: UsageSnapshot? = null,
    /** The guard log as last seen, so only what a turn added becomes its notes. */
    val guardSeen: List<String> = emptyList(),
) {
    val running: Boolean get() = turn?.running == true
}

sealed interface ChatAction {
    data class Opened(val sessionId: String, val title: String, val turns: Int, val transcript: Transcript? = null) : ChatAction
    data class Sent(val text: String, val nowMs: Long, val guardLog: List<String> = emptyList()) : ChatAction
    data class StartFailed(val code: String, val message: String, val nowMs: Long) : ChatAction
    data class Event(val event: AgentEvent, val nowMs: Long, val guardLog: List<String>? = null) : ChatAction
    data class Answered(val text: String, val nowMs: Long) : ChatAction
    data class Renamed(val title: String) : ChatAction
    data class UsageLoaded(val usage: SessionUsage) : ChatAction
}

/** The new entries in a bounded, rotating log: whatever follows the longest overlap between the end
 *  of what was seen and the start of what is there now. */
object GuardNotes {
    fun delta(before: List<String>, after: List<String>): List<String> {
        for (k in minOf(before.size, after.size) downTo 1) {
            if (before.subList(before.size - k, before.size) == after.subList(0, k)) return after.drop(k)
        }
        return after
    }
}

object ChatReducer {
    /** Partial answers closer together than this are not redrawn. */
    const val PARTIAL_EVERY_MS = 80L

    fun reduce(state: ChatState, action: ChatAction): ChatState = when (action) {
        is ChatAction.Opened -> ChatState(
            sessionId = action.sessionId, title = action.title, turns = action.turns,
            blocks = action.transcript?.let(::blocksOf) ?: emptyList(), guardSeen = state.guardSeen,
        )
        is ChatAction.Sent -> state.copy(
            blocks = state.blocks + ChatBlock.User(action.text, action.nowMs),
            turn = TurnUi(startedAtMs = action.nowMs, phase = "starting"),
            ask = null,
            guardSeen = action.guardLog,
        )
        is ChatAction.StartFailed -> state.copy(
            blocks = state.blocks + ChatBlock.System(action.message, action.code, atMs = action.nowMs),
            turn = null,
        )
        is ChatAction.Answered -> state.copy(blocks = state.blocks + ChatBlock.User(action.text, action.nowMs), ask = null)
        is ChatAction.Renamed -> state.copy(title = action.title)
        is ChatAction.UsageLoaded -> state.copy(usage = action.usage.usage, title = action.usage.title.ifEmpty { state.title },
            turns = if (action.usage.turns != 0) action.usage.turns else state.turns)
        is ChatAction.Event -> event(state, action)
    }

    private fun blocksOf(t: Transcript): List<ChatBlock> =
        listOfNotNull(t.earlier.takeIf { it.isNotBlank() }?.let(ChatBlock::Earlier)) +
            t.messages.map { if (it.role == "you") ChatBlock.User(it.text) else ChatBlock.Otto(it.text) }

    private fun event(state: ChatState, action: ChatAction.Event): ChatState {
        val e = action.event
        if (state.sessionId.isNotEmpty() && e.sessionId != null && e.sessionId != state.sessionId) return state
        val now = action.nowMs
        var s = if (state.sessionId.isEmpty() && e.sessionId != null) state.copy(sessionId = e.sessionId!!) else state
        if (action.guardLog != null) {
            val fresh = GuardNotes.delta(s.guardSeen, action.guardLog)
            s = s.copy(guardSeen = action.guardLog, turn = s.turn?.let { t -> if (fresh.isEmpty()) t else t.copy(guardNotes = t.guardNotes + fresh) })
        }
        val turn = s.turn ?: TurnUi(startedAtMs = now)
        return when (e) {
            is AgentEvent.Started -> s.copy(turn = turn.copy(running = true, budgetMax = e.budgetMax ?: turn.budgetMax,
                phase = if (turn.phase.isEmpty() || turn.phase == "starting") "thinking" else turn.phase))
            is AgentEvent.Progress -> s.copy(turn = progress(turn, e, now))
            is AgentEvent.Board -> s.copy(turn = turn.copy(
                steps = turn.steps + 1,
                lines = (turn.lines + e.lines.filter { it.isNotBlank() }).takeLast(TurnUi.MAX_LINES),
                mode = e.lines.mapNotNull(Board::modeFrom).lastOrNull() ?: turn.mode,
            ))
            is AgentEvent.Ask -> s.copy(turn = turn, ask = AskUi(e.threadId, e.question, e.choices))
            is AgentEvent.Final -> {
                val done = turn.copy(running = false, endedAtMs = now, streaming = null, answer = e.text, document = e.document,
                    cost = e.turn ?: turn.cost, where = e.phone?.let(::whereOf) ?: turn.where)
                s.copy(
                    blocks = s.blocks + ChatBlock.Otto(e.text.ifBlank { "(no output)" }, done, now),
                    turn = null, ask = null,
                    title = e.title ?: s.title, turns = e.turns ?: s.turns, usage = e.usage ?: s.usage,
                )
            }
            is AgentEvent.Error -> {
                val done = turn.copy(running = false, endedAtMs = now, streaming = null, stopped = e.cancelled, failed = !e.cancelled,
                    cost = e.turn ?: turn.cost)
                s.copy(
                    blocks = s.blocks + ChatBlock.System(if (e.cancelled) "stopped" else e.message.ifBlank { "failed" }, e.code, done, now),
                    turn = null, ask = null,
                )
            }
            is AgentEvent.CancelRequest -> if (s.turn == null) s else s.copy(turn = turn.copy(phase = "stopping"))
            is AgentEvent.Unknown -> s
        }
    }

    private fun whereOf(phone: Boolean) = if (phone) Where.PHONE else Where.HERE

    private fun progress(turn: TurnUi, e: AgentEvent.Progress, now: Long): TurnUi {
        val t = turn.copy(calls = if (e.calls != 0) e.calls else turn.calls)
        return when (e.kind) {
            "call_start" -> t.copy(model = e.text, tool = "", phase = t.phase.ifEmpty { "thinking" })
            "phase" -> t.copy(phase = e.text, tool = "", where = e.phone?.let(::whereOf) ?: t.where)
            "tool" -> t.copy(tool = "${e.text} ${e.detail?.str("target") ?: ""}".trim())
            "partial" -> {
                val text = Format.textAfterFinal(e.partial) ?: return t
                val last = t.lastPartialAtMs
                if (last != null && now - last < PARTIAL_EVERY_MS) t else t.copy(streaming = text, lastPartialAtMs = now)
            }
            else -> t
        }
    }
}
