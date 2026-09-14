package dev.otto.phone.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.otto.phone.OttoApp
import dev.otto.phone.access.OttoAccessibilityService
import dev.otto.phone.service.OttoForegroundService
import dev.otto.phone.transport.AgentTransport
import dev.otto.phone.transport.EmbeddedTransport
import dev.otto.phone.transport.EventBus
import dev.otto.phone.transport.ServeProtocol
import dev.otto.phone.transport.ServeTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class Screen { DISCLOSURE, SETUP, CHAT, SESSIONS, SETTINGS }

data class Message(val role: String, val text: String)
data class Ask(val threadId: String, val question: String, val choices: List<String>)
data class SessionRow(val id: String, val title: String, val turns: Int, val age: String)

data class UiState(
    val screen: Screen = Screen.DISCLOSURE,
    val transportName: String = "embedded",
    val serviceEnabled: Boolean = false,
    val runtimeAvailable: Boolean = false,
    val ready: Boolean = false,
    val keys: Map<String, String> = emptyMap(),
    val versionLine: String = "",
    val sessionId: String = "",
    val sessionTitle: String = "",
    val messages: List<Message> = emptyList(),
    val status: String = "",
    val board: List<String> = emptyList(),
    val running: Boolean = false,
    val ask: Ask? = null,
    val handedOver: Boolean = false,
    val guardLog: List<String> = emptyList(),
    val sessions: List<SessionRow> = emptyList(),
    val serveUrl: String = "",
    val allowedToAct: Boolean = true,
    val error: String = "",
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = (app as OttoApp).prefs
    private var transport: AgentTransport? = null
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val accepted = prefs.disclosureAccepted()
            _state.update { it.copy(screen = if (accepted) Screen.SETUP else Screen.DISCLOSURE, transportName = prefs.transport(),
                serveUrl = prefs.serveUrl(), allowedToAct = prefs.allowedToAct()) }
            if (accepted) connect()
        }
        viewModelScope.launch { EventBus.events.collect { onEvent(it) } }
    }

    fun refreshService() {
        val enabled = OttoAccessibilityService.instance != null
        _state.update { it.copy(serviceEnabled = enabled, handedOver = OttoAccessibilityService.instance?.guard?.handedOver ?: false) }
    }

    fun acceptDisclosure() = viewModelScope.launch {
        prefs.setDisclosureAccepted(true)
        _state.update { it.copy(screen = Screen.SETUP) }
        connect()
    }

    fun openAccessibilitySettings() {
        getApplication<Application>().startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun connect() = viewModelScope.launch {
        transport?.close()
        val app = getApplication<Application>()
        val chosen: AgentTransport = if (prefs.transport() == "serve" && prefs.serveUrl().isNotBlank())
            ServeTransport(prefs.serveUrl(), prefs.serveToken()) else EmbeddedTransport(app, prefs)
        transport = chosen
        val started = runCatching { chosen.start() }.getOrElse { errorJson(it.message ?: "could not start") }
        val ok = started["ok"]?.jsonPrimitive?.content == "true"
        if (!ok && chosen.name == "embedded") {
            _state.update { it.copy(transportName = chosen.name, runtimeAvailable = false, ready = false,
                error = "The embedded runtime is not in this build. Pair with otto serve in Settings.") }
            return@launch
        }
        val status = runCatching { chosen.setupStatus() }.getOrElse { errorJson(it.message ?: "no status") }
        applyStatus(chosen, status, ok)
    }

    private fun applyStatus(chosen: AgentTransport, status: JsonObject, connected: Boolean) {
        val keys = status["keys"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        val version = status["version"]?.jsonObject
        val ready = status["ready"]?.jsonPrimitive?.content == "true"
        _state.update {
            it.copy(transportName = chosen.name, runtimeAvailable = connected, ready = ready, keys = keys,
                versionLine = version?.let { v -> "otto ${v["otto"]?.jsonPrimitive?.content} · api ${v["api"]?.jsonPrimitive?.content} · ${chosen.name}" } ?: "",
                error = status["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "")
        }
        if (ready) openSession(null)
    }

    fun setKey(name: String, value: String) = viewModelScope.launch {
        val t = transport ?: return@launch
        val reply = t.setKey(name, value)
        if (reply["ok"]?.jsonPrimitive?.content == "true") applyStatus(t, t.setupStatus(), true)
        else _state.update { it.copy(error = reply["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "could not save the key") }
    }

    fun pairServe(pairing: String) = viewModelScope.launch {
        val parsed = ServeProtocol.parsePairing(pairing)
        if (parsed == null) { _state.update { it.copy(error = "paste the ws://host:port/#token line otto serve printed") }; return@launch }
        prefs.setServeUrl(parsed.first); prefs.setServeToken(parsed.second); prefs.setTransport("serve")
        _state.update { it.copy(serveUrl = parsed.first, transportName = "serve", error = "") }
        connect()
    }

    fun useEmbedded() = viewModelScope.launch { prefs.setTransport("embedded"); connect() }

    fun setAllowedToAct(value: Boolean) = viewModelScope.launch {
        prefs.setAllowedToAct(value)
        OttoAccessibilityService.instance?.guard?.allowedToAct = value
        _state.update { it.copy(allowedToAct = value) }
    }

    fun resumeAfterHandover() {
        OttoAccessibilityService.instance?.guard?.handedOver = false
        _state.update { it.copy(handedOver = false) }
    }

    fun show(screen: Screen) {
        _state.update { it.copy(screen = screen, error = "") }
        if (screen == Screen.SESSIONS) loadSessions()
        if (screen == Screen.CHAT) refreshService()
    }

    fun openSession(ref: String?) = viewModelScope.launch {
        val t = transport ?: return@launch
        val reply = t.openSession(ref)
        val id = reply["session_id"]?.jsonPrimitive?.content ?: return@launch
        val messages = if (ref != null) runCatching { t.transcript(id) }.getOrNull()?.get("messages")?.jsonArray?.map { m ->
            Message(m.jsonObject["role"]?.jsonPrimitive?.content ?: "otto", m.jsonObject["text"]?.jsonPrimitive?.content ?: "")
        } ?: emptyList() else emptyList()
        _state.update { it.copy(sessionId = id, sessionTitle = reply["title"]?.jsonPrimitive?.content ?: "", messages = messages,
            board = emptyList(), status = "", screen = Screen.CHAT) }
    }

    fun loadSessions() = viewModelScope.launch {
        val rows = transport?.listSessions()?.get("sessions")?.jsonArray?.map { r ->
            val o = r.jsonObject
            SessionRow(o["id"]?.jsonPrimitive?.content ?: "", o["title"]?.jsonPrimitive?.content ?: "", o["turns"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0, o["age"]?.jsonPrimitive?.content ?: "")
        } ?: emptyList()
        _state.update { it.copy(sessions = rows) }
    }

    fun deleteSession(id: String) = viewModelScope.launch { transport?.deleteSession(id); loadSessions() }

    fun send(text: String) = viewModelScope.launch {
        val t = transport ?: return@launch
        if (text.isBlank() || _state.value.running) return@launch
        refreshService()
        _state.update { it.copy(messages = it.messages + Message("you", text), running = true, status = "starting", board = emptyList(), error = "") }
        OttoForegroundService.start(getApplication(), text)
        val reply = t.startTurn(_state.value.sessionId, text)
        if (reply["ok"]?.jsonPrimitive?.content != "true") {
            _state.update { it.copy(running = false, error = reply["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "could not start") }
            OttoForegroundService.stop(getApplication())
        }
    }

    fun answer(text: String) = viewModelScope.launch {
        val ask = _state.value.ask ?: return@launch
        transport?.answer(_state.value.sessionId, ask.threadId, text)
        _state.update { it.copy(ask = null, messages = it.messages + Message("you", text)) }
    }

    fun stop() = viewModelScope.launch { transport?.cancel(_state.value.sessionId) }

    private fun onEvent(event: JsonObject) {
        val type = event["type"]?.jsonPrimitive?.content ?: return
        event["session_id"]?.jsonPrimitive?.content?.let { sid -> if (_state.value.sessionId.isBlank()) _state.update { it.copy(sessionId = sid) } }
        when (type) {
            "started" -> _state.update { it.copy(running = true, status = "thinking") }
            "progress" -> _state.update {
                val text = event["text"]?.jsonPrimitive?.content ?: ""
                val kind = event["kind"]?.jsonPrimitive?.content ?: ""
                val calls = event["calls"]?.jsonPrimitive?.content ?: "0"
                it.copy(status = if (kind == "partial") it.status else "$kind $text · $calls calls".trim())
            }
            "board" -> _state.update { it.copy(board = (it.board + (event["lines"]?.jsonArray?.map { l -> l.jsonPrimitive.content } ?: emptyList())).takeLast(40)) }
            "ask" -> _state.update { it.copy(ask = Ask(event["thread_id"]?.jsonPrimitive?.content ?: "", event["question"]?.jsonPrimitive?.content ?: "",
                event["choices"]?.jsonArray?.map { c -> c.jsonPrimitive.content } ?: emptyList())) }
            "final" -> {
                _state.update { it.copy(running = false, status = "", ask = null, messages = it.messages + Message("otto", event["text"]?.jsonPrimitive?.content ?: "(no output)")) }
                OttoForegroundService.stop(getApplication())
                refreshService()
            }
            "error" -> {
                val message = event["message"]?.jsonPrimitive?.content ?: "failed"
                val code = event["code"]?.jsonPrimitive?.content ?: ""
                _state.update { it.copy(running = false, status = "", ask = null, error = if (code == "cancelled") "" else message,
                    messages = if (code == "cancelled") it.messages + Message("otto", "stopped") else it.messages) }
                OttoForegroundService.stop(getApplication())
                refreshService()
            }
        }
        OttoAccessibilityService.instance?.guard?.let { g -> _state.update { it.copy(handedOver = g.handedOver, guardLog = g.log.toList().takeLast(20)) } }
    }

    private fun errorJson(message: String): JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("ok", kotlinx.serialization.json.JsonPrimitive(false))
        put("error", kotlinx.serialization.json.buildJsonObject { put("code", kotlinx.serialization.json.JsonPrimitive("failed")); put("message", kotlinx.serialization.json.JsonPrimitive(message)) })
    }

    override fun onCleared() { transport?.close(); super.onCleared() }
}
