package dev.otto.phone.transport

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
import dev.otto.phone.protocol.OpenedSession
import dev.otto.phone.protocol.PhoneMode
import dev.otto.phone.protocol.ProbeResult
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement

/** How the UI reaches otto: the embedded runtime or `otto serve`, one typed shape. A request the
 *  other end cannot answer comes back `Reply.Unsupported` without being sent; turn events arrive on
 *  EventBus, not here. */
interface AgentTransport {
    val name: String
    /** What the other end can do; `Capabilities.V1` until `start` has answered. */
    val capabilities: Capabilities
    /** True while a transport that was connected is trying to get the connection back. */
    val reconnecting: StateFlow<Boolean> get() = NEVER_RECONNECTING

    suspend fun start(): Reply<Hello>

    // sessions
    suspend fun listSessions(limit: Int = 20): Reply<SessionList>
    suspend fun openSession(ref: String?): Reply<OpenedSession>
    suspend fun transcript(ref: String): Reply<Transcript>
    suspend fun deleteSession(sessionId: String): Reply<DeletedSession>
    suspend fun closeSession(sessionId: String): Reply<ClosedSession>
    suspend fun renameSession(sessionId: String, title: String): Reply<RenamedSession>
    suspend fun exportSession(sessionId: String): Reply<ExportedSession>
    suspend fun importSession(data: JsonElement): Reply<ImportedSession>
    suspend fun sessionUsage(sessionId: String): Reply<SessionUsage>

    // turns
    /** `phone` other than AUTO needs `Op.TURN_PHONE`; without it the reply is Unsupported. */
    suspend fun startTurn(sessionId: String?, text: String, phone: PhoneMode = PhoneMode.AUTO): Reply<TurnStarted>
    suspend fun answer(sessionId: String, threadId: String, text: String): Reply<Ack>
    suspend fun cancel(sessionId: String): Reply<Ack>

    // setup, doctor, models
    suspend fun setupStatus(): Reply<SetupStatus>
    suspend fun setKey(name: String, value: String): Reply<KeySet>
    suspend fun probe(name: String): Reply<ProbeResult>
    suspend fun doctor(): Reply<DoctorReport>
    suspend fun models(): Reply<ModelList>

    // routing
    suspend fun routes(): Reply<RoutingList>
    suspend fun routeOptions(task: String): Reply<RouteOptions>
    suspend fun pinRoute(task: String, spec: String): Reply<RouteChange>
    suspend fun clearRoute(task: String): Reply<RouteChange>

    // lessons & app notes; kind is "lesson" | "phone_lesson" | "app_note:<package>"
    suspend fun lessons(kind: String): Reply<LessonList>
    suspend fun deleteLesson(kind: String, lessonId: String): Reply<LessonDeleted>
    suspend fun clearLessons(kind: String): Reply<LessonsCleared>
    suspend fun notes(): Reply<NoteList>
    suspend fun note(packageName: String): Reply<NoteDetail>
    suspend fun deleteNote(packageName: String, lessonId: String): Reply<LessonDeleted>

    // files a research turn wrote in the session's workspace
    suspend fun file(sessionId: String, name: String): Reply<FileBlob>

    fun close()
}

/** The words for Unsupported, said the same way everywhere. */
const val NEEDS_NEWER_OTTO = "needs a newer otto"

private val NEVER_RECONNECTING: StateFlow<Boolean> = MutableStateFlow(false)
