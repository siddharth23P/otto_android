package dev.otto.phone.transport

import dev.otto.phone.protocol.Op
import dev.otto.phone.protocol.Protocol
import dev.otto.phone.protocol.Reply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Which request a frame from `otto serve` answers. Pure: no socket, no clock, no Android.
 *
 *  - A reply with an `id` (protocol 2) completes the request with that id, whatever its op.
 *  - A `<group>_result` without one (protocol 1) completes the oldest request of that op.
 *  - An id-less `busy` or `no_session` fails the oldest pending turn (the only request that sends
 *    them on protocol 1), or the oldest request when no turn is pending.
 *  - An id-less `no_question` belongs to an answer, which waits for nothing, so it is dropped.
 *  - Any other id-less `error` fails the oldest pending request.
 *  - No `error` frame is ever an event: only a turn's own `event{type:"error"}` reaches the event
 *    stream, so a failed list no longer clears the status overlay.
 *  - A turn is accepted by its `started` event: the oldest pending turn for that session, or the
 *    oldest one that named no session.
 *  - `failAll` (the socket went away) fails every pending request and hands back one error event
 *    per turn that had started and not finished, so nothing waits forever. */
class Correlator {
    class Pending internal constructor(
        val id: String,
        val op: Op,
        val sessionId: String?,
        val reply: CompletableDeferred<Reply<JsonObject>>,
    ) {
        val timeoutMillis: Long get() = timeoutMillis(op)
    }

    sealed interface Routed {
        data class Hello(val reply: Reply<JsonObject>) : Routed
        /** An agent event with the frame's session_id on it, for EventBus. */
        data class Event(val event: JsonObject) : Routed
        data class DeviceCall(val frame: JsonObject) : Routed
        data class Resolved(val id: String, val op: Op) : Routed
        data class Dropped(val why: String) : Routed
    }

    private val lock = Any()
    private var counter = 0
    private val pending = ArrayList<Pending>()
    private var hello: CompletableDeferred<Reply<JsonObject>>? = null
    private val running = LinkedHashSet<String>()

    val pendingCount: Int get() = synchronized(lock) { pending.size }
    val runningTurns: Set<String> get() = synchronized(lock) { running.toSet() }

    fun beginHello(): CompletableDeferred<Reply<JsonObject>> = synchronized(lock) {
        CompletableDeferred<Reply<JsonObject>>().also { hello = it }
    }

    fun begin(op: Op, sessionId: String? = null): Pending = synchronized(lock) {
        counter += 1
        Pending("r$counter", op, sessionId?.ifBlank { null }, CompletableDeferred()).also { pending.add(it) }
    }

    fun onFrame(frame: JsonObject): Routed {
        val type = frame.string("type") ?: return Routed.Dropped("no type")
        return when {
            type == "hello_ok" -> completeHello(Reply.Ok(frame))
            type == "device_call" -> Routed.DeviceCall(frame)
            type == "event" -> event(frame)
            type == "error" -> error(frame)
            type.endsWith("_result") -> result(type.removeSuffix("_result"), frame)
            else -> Routed.Dropped(type)
        }
    }

    /** The request ran out of time: it fails with `timeout` unless its reply got there first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun expire(id: String): Reply<JsonObject> {
        val p = synchronized(lock) { pending.firstOrNull { it.id == id }?.also { pending.remove(it) } }
        if (p != null) {
            val timedOut = Reply.Err("timeout", "otto did not answer ${p.op.key} within ${p.timeoutMillis / 1000} s")
            p.reply.complete(timedOut)
            return timedOut
        }
        return Reply.Err("timeout", "no request $id is waiting")
    }

    /** The frame could not be sent. */
    fun fail(id: String, code: String, message: String) {
        synchronized(lock) { pending.firstOrNull { it.id == id }?.also { pending.remove(it) } }?.reply?.complete(Reply.Err(code, message))
    }

    fun failAll(code: String, message: String): List<JsonObject> {
        val (waiting, turns, greeting) = synchronized(lock) {
            val w = pending.toList(); pending.clear()
            val t = running.toList(); running.clear()
            val h = hello; hello = null
            Triple(w, t, h)
        }
        greeting?.complete(Reply.Err(code, message))
        waiting.forEach { it.reply.complete(Reply.Err(code, message)) }
        return turns.map { sid ->
            buildJsonObject { put("type", "error"); put("code", code); put("message", message); put("session_id", sid) }
        }
    }

    private fun completeHello(reply: Reply<JsonObject>): Routed {
        val waiter = synchronized(lock) { hello.also { hello = null } } ?: return Routed.Dropped("hello nobody waited for")
        waiter.complete(reply)
        return Routed.Hello(reply)
    }

    private fun event(frame: JsonObject): Routed {
        val inner = frame["event"] as? JsonObject ?: return Routed.Dropped("event without an event")
        val merged = buildJsonObject { inner.forEach { (k, v) -> put(k, v) }; frame["session_id"]?.let { put("session_id", it) } }
        val sid = merged.string("session_id")?.ifBlank { null }
        var accepted: Pending? = null
        synchronized(lock) {
            when (merged.string("type")) {
                "started" -> {
                    if (sid != null) running.add(sid)
                    val turns = pending.filter { it.op == Op.TURN }
                    accepted = turns.firstOrNull { it.sessionId != null && it.sessionId == sid } ?: turns.firstOrNull { it.sessionId == null }
                    accepted?.let { pending.remove(it) }
                }
                "final", "error" -> if (sid != null) running.remove(sid)
            }
        }
        accepted?.reply?.complete(Reply.Ok(merged))
        return Routed.Event(merged)
    }

    private fun error(frame: JsonObject): Routed {
        val failure = Protocol.errorOf(frame) ?: Reply.Err("failed", "")
        if (synchronized(lock) { hello != null }) return completeHello(failure)
        val id = frame.string("id")
        val code = frame.string("code") ?: "failed"
        val target = synchronized(lock) {
            val chosen = when {
                id != null -> pending.firstOrNull { it.id == id }
                code in ANSWER_CODES -> null
                code in TURN_CODES -> pending.firstOrNull { it.op == Op.TURN } ?: pending.firstOrNull()
                else -> pending.firstOrNull()
            }
            chosen?.also { pending.remove(it) }
        } ?: return Routed.Dropped("error $code with nothing waiting")
        target.reply.complete(failure)
        return Routed.Resolved(target.id, target.op)
    }

    private fun result(group: String, frame: JsonObject): Routed {
        val id = frame.string("id")
        val op = frame.string("op")?.ifBlank { null }
        val target = synchronized(lock) {
            val chosen = if (id != null) pending.firstOrNull { it.id == id }
            else pending.firstOrNull { it.op.group == group && (op == null || it.op.op == op) }
            chosen?.also { pending.remove(it) }
        } ?: return Routed.Dropped("${group}_result with nothing waiting")
        target.reply.complete(Protocol.errorOf(frame) ?: Reply.Ok(frame))
        return Routed.Resolved(target.id, target.op)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content

    companion object {
        val TURN_CODES = setOf("busy", "no_session")
        val ANSWER_CODES = setOf("no_question")

        fun timeoutMillis(op: Op): Long = when (op) {
            Op.DOCTOR, Op.SETUP_PROBE, Op.MODELS -> 45_000
            Op.SESSIONS_EXPORT, Op.SESSIONS_IMPORT -> 60_000
            else -> 15_000
        }
    }
}
