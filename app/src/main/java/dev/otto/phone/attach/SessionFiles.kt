package dev.otto.phone.attach

import android.content.Context
import dev.otto.phone.BuildConfig
import dev.otto.phone.log.OttoLog
import dev.otto.phone.protocol.Protocol
import dev.otto.phone.protocol.Reply
import dev.otto.phone.state.Attachment
import dev.otto.phone.state.SessionFile
import dev.otto.phone.state.SessionFileList
import dev.otto.phone.transport.PythonRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A session's files (otto_app/session_files.py): what was attached, with its text kept in the
 * session's workspace for otto to read again, and either a lasting reference to the original or,
 * for a shared file, a copy. Only where otto runs on this phone -- a remote otto serve keeps its own
 * workspace, which the app cannot write to.
 */
class SessionFiles(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val reader = AttachmentReader(context)

    val available: Boolean get() = BuildConfig.EMBEDDED_PYTHON

    private suspend fun call(fn: String, vararg args: Any): Reply<JsonObject> = withContext(Dispatchers.IO) {
        if (!available) return@withContext Reply.Unsupported
        try {
            val out = PythonRuntime.get(context).getModule("otto_app.session_files").callAttr(fn, *args).toString()
            val obj = Protocol.parse(out) ?: return@withContext Reply.Err("malformed", "no answer")
            Protocol.errorOf(obj) ?: Reply.Ok(obj)
        } catch (e: Exception) {
            OttoLog.w(TAG, "$fn failed", e)
            Reply.Err("failed", e.message ?: e.javaClass.simpleName)
        }
    }

    /** Keeps one sent file; returns where its text is, relative to the session's workspace. */
    suspend fun store(sessionId: String, a: Attachment): String? {
        val ready = a.state as? Attachment.State.Ready ?: return null
        val meta = buildJsonObject {
            put("id", a.id); put("name", a.name); put("kind", a.kind.wire); put("size", a.size)
            put("mime", a.mime); put("uri", if (a.linked) a.uri else "")
            put("truncated", ready.truncated); put("note", ready.note)
            ready.pages?.let { put("pages", it) }
        }
        return when (val reply = call("store", sessionId, meta.toString(), ready.text, a.keptCopy.orEmpty())) {
            is Reply.Ok -> reply.value["path"]?.jsonPrimitive?.content
            else -> null
        }
    }

    suspend fun list(sessionId: String): Reply<SessionFileList> = when (val reply = call("listing", sessionId)) {
        is Reply.Ok -> runCatching { Reply.Ok(json.decodeFromJsonElement(SessionFileList.serializer(), reply.value)) }
            .getOrElse { Reply.Err("malformed", it.message ?: "unreadable") }
        is Reply.Err -> reply
        Reply.Unsupported -> Reply.Unsupported
    }

    suspend fun text(sessionId: String, fileId: String): Reply<String> = when (val reply = call("text", sessionId, fileId)) {
        is Reply.Ok -> Reply.Ok(reply.value["text"]?.jsonPrimitive?.content.orEmpty())
        is Reply.Err -> reply
        Reply.Unsupported -> Reply.Unsupported
    }

    /** Forgets one file, and gives back its lasting permission unless another session still uses it. */
    suspend fun remove(sessionId: String, file: SessionFile): Reply<Unit> = when (val reply = call("remove", sessionId, file.id)) {
        is Reply.Ok -> { releaseUnused(listOf(file.uri)); Reply.Ok(Unit) }
        is Reply.Err -> reply
        Reply.Unsupported -> Reply.Unsupported
    }

    /** A session was deleted: its files go, and the permissions no other session needs are released. */
    suspend fun forget(sessionId: String) {
        val reply = call("forget", sessionId) as? Reply.Ok ?: return
        val uris = (reply.value["uris"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        releaseUnused(uris)
    }

    private suspend fun releaseUnused(uris: List<String>) {
        val candidates = uris.filter { it.isNotBlank() }
        if (candidates.isEmpty()) return
        val inUse = (call("uris_in_use") as? Reply.Ok)?.value?.get("uris")
            ?.let { it as? kotlinx.serialization.json.JsonArray }?.map { it.jsonPrimitive.content }?.toSet() ?: return
        candidates.filterNot { it in inUse }.forEach(reader::release)
    }

    companion object {
        private const val TAG = "OttoFiles"
    }
}
