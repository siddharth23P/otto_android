package dev.otto.phone.transport

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json

/** Frames for `otto serve` (agent/server/protocol.py), as pure functions. */
object ServeProtocol {
    const val PROTOCOL_VERSION = 1

    fun hello(token: String, device: String): String = buildJsonObject {
        put("type", "hello"); put("protocol_version", PROTOCOL_VERSION); put("token", token); put("device", device)
        put("capabilities", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("phone")) })
    }.toString()

    fun turn(sessionId: String?, text: String): String = buildJsonObject {
        put("type", "turn"); if (sessionId != null) put("session_id", sessionId); put("text", text)
    }.toString()

    fun answer(sessionId: String, threadId: String, text: String): String = buildJsonObject {
        put("type", "answer"); put("session_id", sessionId); put("thread_id", threadId); put("text", text)
    }.toString()

    fun cancel(sessionId: String): String = buildJsonObject { put("type", "cancel"); put("session_id", sessionId) }.toString()

    fun sessions(op: String, ref: String? = null, sessionId: String? = null): String = buildJsonObject {
        put("type", "sessions"); put("op", op)
        if (ref != null) put("ref", ref); if (sessionId != null) put("session_id", sessionId)
    }.toString()

    fun deviceResult(id: Int, envelope: JsonObject): String = buildJsonObject {
        put("type", "device_result"); put("id", id)
        envelope.forEach { (k, v) -> put(k, v) }
    }.toString()

    fun parse(frame: String): JsonObject? = runCatching { Json.parseToJsonElement(frame).jsonObject }.getOrNull()

    fun type(frame: JsonObject): String = frame["type"]?.jsonPrimitive?.content ?: ""

    fun deviceCallArgs(frame: JsonObject): List<JsonElement> = frame["args"]?.jsonArray?.toList() ?: emptyList()

    /** `ws://host:port/#token` as printed by `otto serve`. */
    fun parsePairing(text: String): Pair<String, String>? {
        val trimmed = text.trim()
        val hash = trimmed.indexOf('#')
        if (hash <= 0) return null
        val url = trimmed.substring(0, hash)
        val token = trimmed.substring(hash + 1)
        if (!(url.startsWith("ws://") || url.startsWith("wss://")) || token.isBlank()) return null
        return url to token
    }
}
