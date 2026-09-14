package dev.otto.phone.transport

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dev.otto.phone.BuildConfig
import dev.otto.phone.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Otto inside the APK, through Chaquopy. Every call runs off the main thread;
 *  events arrive through PyBridge.onEvent. */
class EmbeddedTransport(private val context: Context, private val prefs: Prefs) : AgentTransport {
    override val name = "embedded"

    private fun entry() = Python.getInstance().getModule("otto_app.entry")

    private suspend fun call(module: String, fn: String, vararg args: Any): JsonObject = withContext(Dispatchers.IO) {
        val result = Python.getInstance().getModule(module).callAttr(fn, *args).toString()
        Json.parseToJsonElement(result).jsonObject
    }

    override suspend fun start(): JsonObject = withContext(Dispatchers.IO) {
        if (!BuildConfig.EMBEDDED_PYTHON) return@withContext unavailable("this build has no embedded runtime")
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        val home = File(context.filesDir, "otto").absolutePath
        val keys = prefs.vendorKeysJson(prefs.vendorKeys())
        call("otto_app.bootstrap", "configure", home, keys)
    }

    override suspend fun setupStatus(): JsonObject =
        if (!BuildConfig.EMBEDDED_PYTHON) unavailable("this build has no embedded runtime") else call("otto_app.entry", "setup_status")

    override suspend fun setKey(name: String, value: String): JsonObject {
        prefs.setVendorKey(name, value)
        return call("otto_app.entry", "set_key", name, value)
    }

    override suspend fun doctor(): JsonObject = call("otto_app.entry", "doctor")
    override suspend fun listSessions(): JsonObject = call("otto_app.entry", "list_sessions", 20)
    override suspend fun openSession(ref: String?): JsonObject = call("otto_app.entry", "open_session", ref ?: "")
    override suspend fun transcript(ref: String): JsonObject = call("otto_app.entry", "transcript", ref)
    override suspend fun deleteSession(sessionId: String): JsonObject = call("otto_app.entry", "delete_session", sessionId)
    override suspend fun startTurn(sessionId: String, text: String): JsonObject = call("otto_app.entry", "start_turn", sessionId, text)
    override suspend fun answer(sessionId: String, threadId: String, text: String): JsonObject = call("otto_app.entry", "answer", sessionId, threadId, text)
    override suspend fun cancel(sessionId: String): JsonObject = call("otto_app.entry", "cancel", sessionId)
    override fun close() = Unit

    private fun unavailable(why: String): JsonObject = buildJsonObject {
        put("ok", false); put("available", false)
        put("error", buildJsonObject { put("code", "unavailable"); put("message", why) })
    }
}
