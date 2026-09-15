package dev.otto.phone.transport

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dev.otto.phone.BuildConfig
import dev.otto.phone.data.Prefs
import dev.otto.phone.protocol.Ack
import dev.otto.phone.protocol.Capabilities
import dev.otto.phone.protocol.ClosedSession
import dev.otto.phone.protocol.DeletedSession
import dev.otto.phone.protocol.DoctorReport
import dev.otto.phone.protocol.ExportedSession
import dev.otto.phone.protocol.FileBlob
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
import dev.otto.phone.protocol.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Otto inside the APK, through Chaquopy. Every call runs off the main thread;
 *  events arrive through PyBridge.onEvent. A function otto_app/entry.py does not have yet is
 *  `Unsupported`, the same answer an older `otto serve` gives. */
class EmbeddedTransport(private val context: Context, private val prefs: Prefs) : AgentTransport {
    override val name = "embedded"
    @Volatile override var capabilities: Capabilities = Capabilities.V1
        private set

    private fun entry(): PyObject = Python.getInstance().getModule("otto_app.entry")

    private suspend fun <T> call(deserializer: DeserializationStrategy<T>, fn: String, vararg args: Any): Reply<T> = withContext(Dispatchers.IO) {
        if (!BuildConfig.EMBEDDED_PYTHON) return@withContext Reply.Err("unavailable", "this build has no embedded runtime")
        if (!Python.isStarted()) return@withContext Reply.Err("unavailable", "the embedded runtime has not started")
        val module = entry()
        if (!module.containsKey(fn)) return@withContext Reply.Unsupported
        runCatching {
            val frame = Protocol.parse(module.callAttr(fn, *args).toString())
                ?: return@withContext Reply.Err("malformed", "$fn did not return a JSON object")
            Protocol.decode(deserializer, frame)
        }.getOrElse { Reply.Err("failed", "${it.javaClass.simpleName}: ${it.message}") }
    }

    override suspend fun start(): Reply<Hello> = withContext(Dispatchers.IO) {
        if (!BuildConfig.EMBEDDED_PYTHON) return@withContext Reply.Err("unavailable", "this build has no embedded runtime")
        runCatching {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context))
            val home = File(context.filesDir, "otto").absolutePath
            val keys = prefs.vendorKeysJson(prefs.vendorKeys())
            val configured = Protocol.parse(Python.getInstance().getModule("otto_app.bootstrap").callAttr("configure", home, keys).toString())
                ?: return@withContext Reply.Err("malformed", "configure did not return a JSON object")
            Protocol.errorOf(configured)?.let { return@withContext it }
            val features = featuresPresent()
            capabilities = Capabilities(2, features)
            Reply.Ok(Hello(
                ottoVersion = configured["otto"]?.jsonPrimitive?.content ?: "",
                apiVersion = configured["api"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                protocolVersion = 2,
                features = features.sorted(),
            ))
        }.getOrElse { Reply.Err("unavailable", it.message ?: "the embedded runtime did not start") }
    }

    /** Which ops entry.py can answer, named as serve's features name them. */
    private fun featuresPresent(): Set<String> {
        val module = entry()
        val present = FUNCTIONS.filterValues { module.containsKey(it) }.keys.map { it.key }.toMutableSet()
        present += Capabilities.V1_OPS.map { it.key }
        val takesPhone = runCatching {
            Python.getInstance().getModule("inspect").callAttr("signature", module["start_turn"])["parameters"]!!
                .callAttr("__contains__", "phone").toBoolean()
        }.getOrDefault(false)
        if (takesPhone) present += Op.TURN_PHONE.key
        return present
    }

    override suspend fun listSessions(limit: Int) = call(SessionList.serializer(), "list_sessions", limit)
    override suspend fun openSession(ref: String?) = call(OpenedSession.serializer(), "open_session", ref ?: "")
    override suspend fun transcript(ref: String) = call(Transcript.serializer(), "transcript", ref)
    override suspend fun deleteSession(sessionId: String) = call(DeletedSession.serializer(), "delete_session", sessionId)
    override suspend fun closeSession(sessionId: String) = call(ClosedSession.serializer(), fn(Op.SESSIONS_CLOSE), sessionId)
    override suspend fun renameSession(sessionId: String, title: String) = call(RenamedSession.serializer(), fn(Op.SESSIONS_RENAME), sessionId, title)
    override suspend fun exportSession(sessionId: String) = call(ExportedSession.serializer(), fn(Op.SESSIONS_EXPORT), sessionId)
    /** The payload crosses the bridge as a JSON string. */
    override suspend fun importSession(data: JsonElement) = call(ImportedSession.serializer(), fn(Op.SESSIONS_IMPORT), data.toString())
    override suspend fun sessionUsage(sessionId: String) = call(SessionUsage.serializer(), fn(Op.SESSIONS_USAGE), sessionId)

    override suspend fun startTurn(sessionId: String?, text: String, phone: PhoneMode): Reply<TurnStarted> {
        if (phone != PhoneMode.AUTO && !capabilities.supports(Op.TURN_PHONE)) return Reply.Unsupported
        val sid = sessionId.orEmpty()
        val reply = if (phone != PhoneMode.AUTO) call(JsonObject.serializer(), "start_turn", sid, text, phone.wire)
        else call(JsonObject.serializer(), "start_turn", sid, text)
        return reply.map { TurnStarted(it["session_id"]?.jsonPrimitive?.content ?: sid, it["budget_max"]?.jsonPrimitive?.content?.toIntOrNull()) }
    }

    override suspend fun answer(sessionId: String, threadId: String, text: String) = call(JsonObject.serializer(), "answer", sessionId, threadId, text).map { Ack }
    override suspend fun cancel(sessionId: String) = call(JsonObject.serializer(), "cancel", sessionId).map { Ack }

    override suspend fun setupStatus() = call(SetupStatus.serializer(), "setup_status")

    override suspend fun setKey(name: String, value: String): Reply<KeySet> {
        prefs.setVendorKey(name, value)
        return call(KeySet.serializer(), "set_key", name, value)
    }

    override suspend fun probe(name: String) = call(ProbeResult.serializer(), fn(Op.SETUP_PROBE), name)
    override suspend fun doctor() = call(DoctorReport.serializer(), "doctor")
    override suspend fun models() = call(ModelList.serializer(), fn(Op.MODELS))

    override suspend fun routes() = call(RoutingList.serializer(), fn(Op.ROUTING_LIST))
    override suspend fun routeOptions(task: String) = call(RouteOptions.serializer(), fn(Op.ROUTING_OPTIONS), task)
    override suspend fun pinRoute(task: String, spec: String) = call(RouteChange.serializer(), fn(Op.ROUTING_PIN), task, spec)
    override suspend fun clearRoute(task: String) = call(RouteChange.serializer(), fn(Op.ROUTING_CLEAR), task)

    override suspend fun lessons(kind: String) = call(LessonList.serializer(), fn(Op.LESSONS_LIST), kind)
    override suspend fun deleteLesson(kind: String, lessonId: String) = call(LessonDeleted.serializer(), fn(Op.LESSONS_DELETE), kind, lessonId)
    override suspend fun clearLessons(kind: String) = call(LessonsCleared.serializer(), fn(Op.LESSONS_CLEAR), kind)
    override suspend fun notes() = call(NoteList.serializer(), fn(Op.NOTES_LIST))
    override suspend fun note(packageName: String) = call(NoteDetail.serializer(), fn(Op.NOTES_GET), packageName)
    override suspend fun deleteNote(packageName: String, lessonId: String) = call(LessonDeleted.serializer(), fn(Op.NOTES_DELETE), packageName, lessonId)

    override suspend fun file(sessionId: String, name: String) = call(FileBlob.serializer(), fn(Op.FILES), sessionId, name)

    override fun close() = Unit

    companion object {
        /** The entry.py function behind each op beyond protocol 1 — the names C8 implements. */
        val FUNCTIONS: Map<Op, String> = mapOf(
            Op.SESSIONS_CLOSE to "close_session",
            Op.SESSIONS_RENAME to "rename_session",
            Op.SESSIONS_EXPORT to "export_session",
            Op.SESSIONS_IMPORT to "import_session",
            Op.SESSIONS_USAGE to "session_usage",
            Op.SETUP_STATUS to "setup_status",
            Op.SETUP_SET_KEY to "set_key",
            Op.SETUP_PROBE to "probe",
            Op.DOCTOR to "doctor",
            Op.MODELS to "models",
            Op.ROUTING_LIST to "routes",
            Op.ROUTING_OPTIONS to "route_options",
            Op.ROUTING_PIN to "pin_route",
            Op.ROUTING_CLEAR to "clear_route",
            Op.LESSONS_LIST to "list_lessons",
            Op.LESSONS_DELETE to "delete_lesson",
            Op.LESSONS_CLEAR to "clear_lessons",
            Op.NOTES_LIST to "list_notes",
            Op.NOTES_GET to "get_note",
            Op.NOTES_DELETE to "delete_note",
            Op.FILES to "read_file",
        )

        private fun fn(op: Op): String = FUNCTIONS.getValue(op)
    }
}
