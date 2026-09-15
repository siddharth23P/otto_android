package dev.otto.phone.transport

import android.os.Build
import dev.otto.phone.bridge.PyBridge
import dev.otto.phone.protocol.Ack
import dev.otto.phone.protocol.Capabilities
import dev.otto.phone.protocol.ClosedSession
import dev.otto.phone.protocol.DeletedSession
import dev.otto.phone.protocol.DoctorReport
import dev.otto.phone.protocol.ExportedSession
import dev.otto.phone.protocol.FileBlob
import dev.otto.phone.protocol.Frames
import dev.otto.phone.protocol.Hello
import dev.otto.phone.protocol.ImportedSession
import dev.otto.phone.protocol.KeySet
import dev.otto.phone.protocol.LessonDeleted
import dev.otto.phone.protocol.LessonList
import dev.otto.phone.protocol.LessonsCleared
import dev.otto.phone.protocol.ModelList
import dev.otto.phone.protocol.NoteDetail
import dev.otto.phone.protocol.NoteList
import dev.otto.phone.protocol.Op
import dev.otto.phone.protocol.OpenedSession
import dev.otto.phone.protocol.PhoneMode
import dev.otto.phone.protocol.ProbeResult
import dev.otto.phone.protocol.Protocol
import dev.otto.phone.protocol.RenamedSession
import dev.otto.phone.protocol.Reply
import dev.otto.phone.protocol.RouteChange
import dev.otto.phone.protocol.RouteOptions
import dev.otto.phone.protocol.RoutingList
import dev.otto.phone.protocol.SessionList
import dev.otto.phone.protocol.SessionUsage
import dev.otto.phone.protocol.SetupStatus
import dev.otto.phone.protocol.Transcript
import dev.otto.phone.protocol.TurnStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** Otto on the other end of a WebSocket (`otto serve`): a laptop while the
 *  app is developed, or a Linux userland on this phone. The phone's hands are
 *  still local: every device_call runs through PyBridge. Which reply answers
 *  which request is the Correlator's call. */
class ServeTransport(private val url: String, private val token: String) : AgentTransport {
    override val name = "serve"
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val correlator = Correlator()
    @Volatile private var socket: WebSocket? = null
    @Volatile override var capabilities: Capabilities = Capabilities.V1
        private set

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(ServeProtocol.hello(token, "${Build.MANUFACTURER} ${Build.MODEL}"))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = ServeProtocol.parse(text) ?: return
            when (val routed = correlator.onFrame(frame)) {
                is Correlator.Routed.Event -> EventBus.emit(routed.event)
                is Correlator.Routed.DeviceCall -> scope.launch {
                    val id = frame["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@launch
                    val method = frame["method"]?.jsonPrimitive?.content ?: ""
                    webSocket.send(ServeProtocol.deviceResult(id, PyBridge.callJson(method, ServeProtocol.deviceCallArgs(frame))))
                }
                else -> Unit
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            lost(webSocket, reason.ifBlank { "otto serve closed the connection" })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            lost(webSocket, t.message ?: "connection lost")
        }
    }

    private fun lost(webSocket: WebSocket, message: String) {
        if (socket !== webSocket) return
        socket = null
        correlator.failAll("disconnected", message).forEach(EventBus::emit)
    }

    override suspend fun start(): Reply<Hello> {
        val waiter = correlator.beginHello()
        socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
        val reply = withTimeoutOrNull(15_000) { waiter.await() }
            ?: return Reply.Err("timeout", "otto serve did not answer within 15 s").also { close() }
        return when (reply) {
            is Reply.Ok -> Protocol.decode(Hello.serializer(), reply.value).also { hello ->
                if (hello is Reply.Ok) capabilities = Capabilities.of(hello.value)
            }
            is Reply.Err -> Reply.Err(if (reply.code == "disconnected") reply.code else "hello", reply.message.ifBlank { "refused" })
            Reply.Unsupported -> Reply.Err("hello", "refused")
        }
    }

    private suspend fun <T> request(
        op: Op, deserializer: DeserializationStrategy<T>, sessionId: String? = null, frame: (id: String) -> String,
    ): Reply<T> {
        if (!capabilities.supports(op)) return Reply.Unsupported
        val ws = socket ?: return Reply.Err("disconnected", "not connected")
        val pending = correlator.begin(op, sessionId)
        if (!ws.send(frame(pending.id))) correlator.fail(pending.id, "disconnected", "not connected")
        val reply = withTimeoutOrNull(pending.timeoutMillis) { pending.reply.await() } ?: correlator.expire(pending.id)
        val typed: Reply<T> = when (reply) {
            is Reply.Ok -> Protocol.decode(deserializer, reply.value)
            is Reply.Err -> reply
            Reply.Unsupported -> Reply.Unsupported
        }
        return typed
    }

    private fun sendOnly(frame: String): Reply<Ack> =
        if (socket?.send(frame) == true) Reply.Ok(Ack) else Reply.Err("disconnected", "not connected")

    override suspend fun listSessions(limit: Int) = request(Op.SESSIONS_LIST, SessionList.serializer()) { Frames.sessions("list", id = it, limit = limit) }
    override suspend fun openSession(ref: String?) = request(Op.SESSIONS_OPEN, OpenedSession.serializer()) { Frames.sessions("open", ref = ref ?: "", id = it) }
    override suspend fun transcript(ref: String) = request(Op.SESSIONS_TRANSCRIPT, Transcript.serializer()) { Frames.sessions("transcript", ref = ref, id = it) }
    override suspend fun deleteSession(sessionId: String) = request(Op.SESSIONS_DELETE, DeletedSession.serializer()) { Frames.sessions("delete", sessionId = sessionId, id = it) }
    override suspend fun closeSession(sessionId: String) = request(Op.SESSIONS_CLOSE, ClosedSession.serializer()) { Frames.sessions("close", sessionId = sessionId, id = it) }
    override suspend fun renameSession(sessionId: String, title: String) = request(Op.SESSIONS_RENAME, RenamedSession.serializer()) { Frames.sessions("rename", sessionId = sessionId, title = title, id = it) }
    override suspend fun exportSession(sessionId: String) = request(Op.SESSIONS_EXPORT, ExportedSession.serializer()) { Frames.sessions("export", sessionId = sessionId, id = it) }
    override suspend fun importSession(data: JsonElement) = request(Op.SESSIONS_IMPORT, ImportedSession.serializer()) { Frames.sessions("import", data = data, id = it) }
    override suspend fun sessionUsage(sessionId: String) = request(Op.SESSIONS_USAGE, SessionUsage.serializer()) { Frames.sessions("usage", sessionId = sessionId, id = it) }

    override suspend fun startTurn(sessionId: String?, text: String, phone: PhoneMode): Reply<TurnStarted> {
        if (phone != PhoneMode.AUTO && !capabilities.supports(Op.TURN_PHONE)) return Reply.Unsupported
        val flag = if (capabilities.supports(Op.TURN_PHONE)) phone else null
        val started = request(Op.TURN, JsonObject.serializer(), sessionId) { Frames.turn(sessionId, text, flag, it) }
        val typed: Reply<TurnStarted> = when (started) {
            is Reply.Ok -> Reply.Ok(TurnStarted(
                started.value["session_id"]?.jsonPrimitive?.content ?: sessionId.orEmpty(),
                started.value["budget_max"]?.jsonPrimitive?.content?.toIntOrNull(),
            ))
            is Reply.Err -> started
            Reply.Unsupported -> Reply.Unsupported
        }
        return typed
    }

    override suspend fun answer(sessionId: String, threadId: String, text: String) = sendOnly(Frames.answer(sessionId, threadId, text))
    override suspend fun cancel(sessionId: String) = sendOnly(Frames.cancel(sessionId))

    override suspend fun setupStatus() = request(Op.SETUP_STATUS, SetupStatus.serializer()) { Frames.setupStatus(it) }
    override suspend fun setKey(name: String, value: String) = request(Op.SETUP_SET_KEY, KeySet.serializer()) { Frames.setKey(name, value, it) }
    override suspend fun probe(name: String) = request(Op.SETUP_PROBE, ProbeResult.serializer()) { Frames.probe(name, it) }
    override suspend fun doctor() = request(Op.DOCTOR, DoctorReport.serializer()) { Frames.doctor(it) }
    override suspend fun models() = request(Op.MODELS, ModelList.serializer()) { Frames.models(it) }

    override suspend fun routes() = request(Op.ROUTING_LIST, RoutingList.serializer()) { Frames.routes(it) }
    override suspend fun routeOptions(task: String) = request(Op.ROUTING_OPTIONS, RouteOptions.serializer()) { Frames.routeOptions(task, it) }
    override suspend fun pinRoute(task: String, spec: String) = request(Op.ROUTING_PIN, RouteChange.serializer()) { Frames.pinRoute(task, spec, it) }
    override suspend fun clearRoute(task: String) = request(Op.ROUTING_CLEAR, RouteChange.serializer()) { Frames.clearRoute(task, it) }

    override suspend fun lessons(kind: String) = request(Op.LESSONS_LIST, LessonList.serializer()) { Frames.lessons(kind, it) }
    override suspend fun deleteLesson(kind: String, lessonId: String) = request(Op.LESSONS_DELETE, LessonDeleted.serializer()) { Frames.deleteLesson(kind, lessonId, it) }
    override suspend fun clearLessons(kind: String) = request(Op.LESSONS_CLEAR, LessonsCleared.serializer()) { Frames.clearLessons(kind, it) }
    override suspend fun notes() = request(Op.NOTES_LIST, NoteList.serializer()) { Frames.notes(it) }
    override suspend fun note(packageName: String) = request(Op.NOTES_GET, NoteDetail.serializer()) { Frames.note(packageName, it) }
    override suspend fun deleteNote(packageName: String, lessonId: String) = request(Op.NOTES_DELETE, LessonDeleted.serializer()) { Frames.deleteNote(packageName, lessonId, it) }

    override suspend fun file(sessionId: String, name: String) = request(Op.FILES, FileBlob.serializer(), sessionId) { Frames.file(sessionId, name, it) }

    override fun close() {
        val ws = socket
        socket = null
        correlator.failAll("disconnected", "closed").forEach(EventBus::emit)
        ws?.close(1000, "bye")
    }
}
