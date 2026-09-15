package dev.otto.phone.transport

import dev.otto.phone.protocol.Frames
import dev.otto.phone.protocol.Protocol
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Frames for `otto serve`, as pure functions. A facade over protocol/Frames kept for its callers. */
object ServeProtocol {
    const val PROTOCOL_VERSION = Frames.HELLO_PROTOCOL_VERSION

    fun hello(token: String, device: String): String = Frames.hello(token, device)

    fun turn(sessionId: String?, text: String): String = Frames.turn(sessionId, text)

    fun answer(sessionId: String, threadId: String, text: String): String = Frames.answer(sessionId, threadId, text)

    fun cancel(sessionId: String): String = Frames.cancel(sessionId)

    fun sessions(op: String, ref: String? = null, sessionId: String? = null): String = Frames.sessions(op, ref, sessionId)

    fun deviceResult(id: Int, envelope: JsonObject): String = Frames.deviceResult(id, envelope)

    fun parse(frame: String): JsonObject? = Protocol.parse(frame)

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
