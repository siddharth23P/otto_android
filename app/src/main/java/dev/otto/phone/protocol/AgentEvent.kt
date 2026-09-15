package dev.otto.phone.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** One agent/embed.py event, typed. `sessionId` is what the transport attached (serve puts it on
 *  the frame, embedded on the event); null when neither did. */
sealed interface AgentEvent {
    val sessionId: String?

    data class Started(override val sessionId: String?, val budgetMax: Int? = null) : AgentEvent

    data class Progress(
        override val sessionId: String?,
        val kind: String,
        val text: String,
        val calls: Int = 0,
        val elapsed: Double = 0.0,
        val partial: String = "",
        val detail: JsonObject? = null,
    ) : AgentEvent {
        /** The turn's phone decision, from `progress{kind:"phase", detail:{phone}}`; null on any other event. */
        val phone: Boolean? get() = if (kind == "phase") detail?.bool("phone") else null
    }

    data class Board(
        override val sessionId: String?,
        val node: String,
        val lines: List<String>,
        val output: JsonElement? = null,
    ) : AgentEvent

    data class Ask(
        override val sessionId: String?,
        val threadId: String,
        val question: String,
        val choices: List<String>,
    ) : AgentEvent

    data class Final(
        override val sessionId: String?,
        val text: String,
        val usage: UsageSnapshot? = null,
        val traceId: String? = null,
        val turn: TurnTotals? = null,
        val title: String? = null,
        val turns: Int? = null,
        val phone: Boolean? = null,
        val document: DocumentInfo? = null,
    ) : AgentEvent

    data class Error(
        override val sessionId: String?,
        val code: String,
        val message: String,
        val turn: TurnTotals? = null,
    ) : AgentEvent {
        val cancelled: Boolean get() = code == "cancelled"
    }

    /** Stop pressed in the notification: the UI sends the same cancel as its own Stop button. */
    data class CancelRequest(override val sessionId: String?) : AgentEvent

    data class Unknown(override val sessionId: String?, val type: String, val raw: JsonObject) : AgentEvent

    companion object {
        fun parse(text: String): AgentEvent? = Protocol.parse(text)?.let(::parse)

        fun parse(event: JsonObject): AgentEvent {
            val sid = event.str("session_id")?.ifBlank { null }
            return when (val type = event.str("type") ?: "") {
                "started" -> Started(sid, event.int("budget_max"))
                "progress" -> Progress(
                    sid, event.str("kind") ?: "", event.str("text") ?: "", event.int("calls") ?: 0,
                    event.double("elapsed") ?: 0.0, event.str("partial") ?: "", event.obj("detail"),
                )
                "board" -> Board(sid, event.str("node") ?: "", event.strings("lines"), event["output"].nullIfJsonNull())
                "ask" -> Ask(sid, event.str("thread_id") ?: "", event.str("question") ?: "", event.strings("choices"))
                "final" -> Final(
                    sid, event.str("text") ?: "",
                    usage = event.obj("usage")?.let { decodeOrNull<UsageSnapshot>(it) },
                    traceId = event.str("trace_id"),
                    turn = event.obj("turn")?.let { decodeOrNull<TurnTotals>(it) },
                    title = event.str("title"),
                    turns = event.int("turns"),
                    phone = event.bool("phone"),
                    document = event.obj("document")?.let { decodeOrNull<DocumentInfo>(it) },
                )
                "error" -> Error(sid, event.str("code") ?: "failed", event.str("message") ?: "",
                    event.obj("turn")?.let { decodeOrNull<TurnTotals>(it) })
                "cancel_request" -> CancelRequest(sid)
                else -> Unknown(sid, type, event)
            }
        }

        private inline fun <reified T> decodeOrNull(obj: JsonObject): T? =
            runCatching { Protocol.json.decodeFromJsonElement<T>(obj) }.getOrNull()
    }
}
