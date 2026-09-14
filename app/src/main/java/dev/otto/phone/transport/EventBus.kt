package dev.otto.phone.transport

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

    fun emit(event: JsonObject) { flow.tryEmit(event) }
}
