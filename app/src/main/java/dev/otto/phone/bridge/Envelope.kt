package dev.otto.phone.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The JSON envelope every bridge method answers with, in both transports:
 *  {"ok": true, "data": ...} or {"ok": false, "error": {"code", "message", "handover"}}.
 *  Mirrors agent/phone/backend.py's JsonBackend, which reads exactly these keys. */
object Envelope {
    fun ok(data: JsonElement): JsonObject = buildJsonObject { put("ok", true); put("data", data) }

    fun error(code: String, message: String, handover: Boolean = false): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", buildJsonObject { put("code", code); put("message", message); put("handover", handover) })
    }

    fun okText(text: String): JsonObject = ok(JsonPrimitive(text))
}

/** A step the phone will not do. Codes match agent/phone/backend.py's ERROR_CODES. */
class DeviceException(message: String, val code: String = "failed", val handover: Boolean = false) :
    RuntimeException(message)
