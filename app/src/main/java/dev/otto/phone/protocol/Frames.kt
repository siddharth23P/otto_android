package dev.otto.phone.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Client frames for `otto serve` (agent/server/protocol.py), as pure functions.
 *
 *  Every request may carry an `id`, which a protocol-2 server echoes on its `<group>_result` or
 *  `error`; a protocol-1 server ignores it. `hello` still says protocol_version 1 so an older otto
 *  accepts the app; the server's own version comes back in `hello_ok`.
 *
 *  Lesson and note deletes name the row `lesson_id`, because `id` is the request's. */
object Frames {
    const val HELLO_PROTOCOL_VERSION = 1

    fun hello(token: String, device: String, capabilities: List<String> = listOf("phone")): String = buildJsonObject {
        put("type", "hello"); put("protocol_version", HELLO_PROTOCOL_VERSION); put("token", token); put("device", device)
        put("capabilities", JsonArray(capabilities.map(::JsonPrimitive)))
    }.toString()

    /** The generic shape: `{type: group, id?, op?, ...fields}`. */
    fun request(op: Op, id: String?, fields: JsonObjectBuilder.() -> Unit = {}): String = buildJsonObject {
        put("type", op.group)
        if (id != null) put("id", id)
        if (op.op != null && op != Op.TURN_PHONE) put("op", op.op)
        fields()
    }.toString()

    fun turn(sessionId: String?, text: String, phone: PhoneMode? = null, id: String? = null): String = request(Op.TURN, id) {
        if (!sessionId.isNullOrBlank()) put("session_id", sessionId)
        put("text", text)
        if (phone != null) put("phone", phone.wire)
    }

    fun answer(sessionId: String, threadId: String, text: String, id: String? = null): String = request(Op.ANSWER, id) {
        put("session_id", sessionId); put("thread_id", threadId); put("text", text)
    }

    fun cancel(sessionId: String, id: String? = null): String = request(Op.CANCEL, id) { put("session_id", sessionId) }

    fun sessions(
        op: String, ref: String? = null, sessionId: String? = null, id: String? = null,
        limit: Int? = null, title: String? = null, data: JsonElement? = null,
    ): String = buildJsonObject {
        put("type", "sessions")
        if (id != null) put("id", id)
        put("op", op)
        if (ref != null) put("ref", ref)
        if (sessionId != null) put("session_id", sessionId)
        if (limit != null) put("limit", limit)
        if (title != null) put("title", title)
        if (data != null) put("data", data)
    }.toString()

    fun setupStatus(id: String? = null) = request(Op.SETUP_STATUS, id)
    fun setKey(name: String, value: String, id: String? = null) = request(Op.SETUP_SET_KEY, id) { put("name", name); put("value", value) }
    fun probe(name: String, id: String? = null) = request(Op.SETUP_PROBE, id) { put("name", name) }
    fun doctor(id: String? = null) = request(Op.DOCTOR, id)
    fun models(id: String? = null) = request(Op.MODELS, id)

    fun routes(id: String? = null) = request(Op.ROUTING_LIST, id)
    fun routeOptions(task: String, id: String? = null) = request(Op.ROUTING_OPTIONS, id) { put("task", task) }
    fun pinRoute(task: String, spec: String, id: String? = null) = request(Op.ROUTING_PIN, id) { put("task", task); put("spec", spec) }
    fun clearRoute(task: String, id: String? = null) = request(Op.ROUTING_CLEAR, id) { put("task", task) }

    fun lessons(kind: String, id: String? = null) = request(Op.LESSONS_LIST, id) { put("kind", kind) }
    fun deleteLesson(kind: String, lessonId: String, id: String? = null) = request(Op.LESSONS_DELETE, id) { put("kind", kind); put("lesson_id", lessonId) }
    fun clearLessons(kind: String, id: String? = null) = request(Op.LESSONS_CLEAR, id) { put("kind", kind) }

    fun notes(id: String? = null) = request(Op.NOTES_LIST, id)
    fun note(packageName: String, id: String? = null) = request(Op.NOTES_GET, id) { put("package", packageName) }
    fun deleteNote(packageName: String, lessonId: String, id: String? = null) = request(Op.NOTES_DELETE, id) { put("package", packageName); put("lesson_id", lessonId) }

    fun file(sessionId: String, name: String, id: String? = null) = request(Op.FILES, id) { put("session_id", sessionId); put("name", name) }

    fun ping(id: String? = null): String = buildJsonObject { put("type", "ping"); if (id != null) put("id", id) }.toString()

    fun deviceResult(id: Int, envelope: JsonObject): String = buildJsonObject {
        put("type", "device_result"); put("id", id)
        envelope.forEach { (k, v) -> put(k, v) }
    }.toString()
}
