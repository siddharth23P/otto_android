package dev.otto.phone.transport

import android.os.Build
import dev.otto.phone.bridge.PyBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** Otto on the other end of a WebSocket (`otto serve`): a laptop while the
 *  app is developed, or a Linux userland on this phone. The phone's hands are
 *  still local: every device_call runs through PyBridge. */
class ServeTransport(private val url: String, private val token: String) : AgentTransport {
    override val name = "serve"
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: WebSocket? = null
    private var hello = CompletableDeferred<JsonObject>()
    private val waiting = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    @Volatile var helloOk: JsonObject? = null

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(ServeProtocol.hello(token, "${Build.MANUFACTURER} ${Build.MODEL}"))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = ServeProtocol.parse(text) ?: return
            when (ServeProtocol.type(frame)) {
                "hello_ok" -> { helloOk = frame; hello.complete(frame) }
                "event" -> frame["event"]?.jsonObject?.let { event ->
                    val withSession = buildJsonObject { event.forEach { (k, v) -> put(k, v) }; frame["session_id"]?.let { put("session_id", it) } }
                    EventBus.emit(withSession)
                }
                "device_call" -> scope.launch {
                    val id = frame["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@launch
                    val method = frame["method"]?.jsonPrimitive?.content ?: ""
                    val envelope = Json.parseToJsonElement(PyBridge.call(method, ServeProtocol.deviceCallArgs(frame))).jsonObject
                    webSocket.send(ServeProtocol.deviceResult(id, envelope))
                }
                "sessions_result" -> waiting.remove("sessions:" + (frame["op"]?.jsonPrimitive?.content ?: ""))?.complete(frame)
                "error" -> {
                    if (!hello.isCompleted) hello.complete(frame)
                    waiting.keys.toList().forEach { key -> waiting.remove(key)?.complete(frame) }
                    EventBus.emit(buildJsonObject { put("type", "error"); put("code", frame["code"]?.jsonPrimitive?.content ?: "failed"); put("message", frame["message"]?.jsonPrimitive?.content ?: "") })
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val error = buildJsonObject { put("type", "error"); put("code", "disconnected"); put("message", t.message ?: "connection lost") }
            if (!hello.isCompleted) hello.complete(error)
            EventBus.emit(error)
        }
    }

    override suspend fun start(): JsonObject {
        hello = CompletableDeferred()
        socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
        val reply = withTimeout(15_000) { hello.await() }
        return if (ServeProtocol.type(reply) == "hello_ok") buildJsonObject { put("ok", true); reply.forEach { (k, v) -> put(k, v) } }
        else buildJsonObject { put("ok", false); put("error", buildJsonObject { put("code", "hello"); put("message", reply["message"]?.jsonPrimitive?.content ?: "refused") }) }
    }

    override suspend fun setupStatus(): JsonObject = buildJsonObject {
        val ok = helloOk
        put("ok", ok != null); put("available", true); put("ready", ok != null)
        put("keys", buildJsonObject { })
        put("version", buildJsonObject { put("otto", ok?.get("otto_version")?.jsonPrimitive?.content ?: "?"); put("api", ok?.get("api_version")?.jsonPrimitive?.content?.toIntOrNull() ?: 0) })
        put("note", "keys are configured on the machine running otto serve")
    }

    private fun sendOnly(frame: String): JsonObject {
        val sent = socket?.send(frame) ?: false
        return buildJsonObject { put("ok", sent); if (!sent) put("error", buildJsonObject { put("code", "disconnected"); put("message", "not connected") }) }
    }

    private suspend fun sessionsOp(op: String, ref: String? = null, sessionId: String? = null): JsonObject {
        val deferred = CompletableDeferred<JsonObject>()
        waiting["sessions:$op"] = deferred
        val sent = sendOnly(ServeProtocol.sessions(op, ref, sessionId))
        if (sent["ok"]?.jsonPrimitive?.content != "true") return sent
        val reply = withTimeout(15_000) { deferred.await() }
        return buildJsonObject { put("ok", ServeProtocol.type(reply) != "error"); reply.forEach { (k, v) -> put(k, v) } }
    }

    override suspend fun setKey(name: String, value: String): JsonObject = buildJsonObject {
        put("ok", false); put("error", buildJsonObject { put("code", "unsupported"); put("message", "keys live on the machine running otto serve") })
    }

    override suspend fun doctor(): JsonObject = buildJsonObject { put("ok", true); put("providers", buildJsonObject { }) }
    override suspend fun listSessions(): JsonObject = sessionsOp("list")
    override suspend fun openSession(ref: String?): JsonObject = sessionsOp("open", ref = ref ?: "")
    override suspend fun transcript(ref: String): JsonObject = sessionsOp("transcript", ref = ref)
    override suspend fun deleteSession(sessionId: String): JsonObject = sessionsOp("delete", sessionId = sessionId)
    override suspend fun startTurn(sessionId: String, text: String): JsonObject = sendOnly(ServeProtocol.turn(sessionId.ifBlank { null }, text))
    override suspend fun answer(sessionId: String, threadId: String, text: String): JsonObject = sendOnly(ServeProtocol.answer(sessionId, threadId, text))
    override suspend fun cancel(sessionId: String): JsonObject = sendOnly(ServeProtocol.cancel(sessionId))
    override fun close() { socket?.close(1000, "bye"); socket = null }
}
