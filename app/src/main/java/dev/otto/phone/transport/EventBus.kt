package dev.otto.phone.transport

import dev.otto.phone.log.OttoLog

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** agent/embed.py events (JSON) from whichever transport is live. */
object EventBus {
    private val flow = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256)
    val events: SharedFlow<JsonObject> = flow

    fun emit(json: String) {
        runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()?.let { flow.tryEmit(it) }
    }

    fun emit(event: JsonObject) {
        val kind = (event["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?"
        if (kind == "error") OttoLog.w("OttoEvent", "error: ${event["code"]}: ${event["message"]}")
        else OttoLog.d("OttoEvent", kind)
        flow.tryEmit(event)
    }
}
