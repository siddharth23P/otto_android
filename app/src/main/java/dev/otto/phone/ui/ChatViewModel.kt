package dev.otto.phone.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.otto.phone.OttoApp
import dev.otto.phone.access.OttoAccessibilityService
import dev.otto.phone.protocol.Hello
import dev.otto.phone.protocol.Reply
import dev.otto.phone.protocol.SetupStatus
import dev.otto.phone.service.OttoForegroundService
import dev.otto.phone.transport.AgentTransport
import dev.otto.phone.transport.EventBus
import dev.otto.phone.transport.NEEDS_NEWER_OTTO
import dev.otto.phone.transport.ServeProtocol
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

private fun Reply<*>.failure(fallback: String): String? = when (this) {
    is Reply.Ok -> null
    is Reply.Err -> message.ifBlank { fallback }
    Reply.Unsupported -> NEEDS_NEWER_OTTO
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = (app as OttoApp).prefs
    private val connection = (app as OttoApp).connection
    private val transport: AgentTransport? get() = connection.current
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
        val (chosen, hello) = connection.connect()
        if (hello !is Reply.Ok) {
            _state.update { it.copy(transportName = chosen.name, runtimeAvailable = false, ready = false,
                error = if (chosen.name == "embedded") "The embedded runtime is not in this build. Pair with otto serve in Settings."
                else hello.failure("could not connect") ?: "") }
            return@launch
        }
        when (val status = chosen.setupStatus()) {
            is Reply.Ok -> applyStatus(chosen, status.value)
            // An otto serve from before the setup op: keys live on that machine, and a hello is ready enough.
            Reply.Unsupported -> applyHello(chosen, hello.value)
            is Reply.Err -> _state.update { it.copy(transportName = chosen.name, runtimeAvailable = true, ready = false, error = status.message) }
        }
    }

    private fun applyStatus(chosen: AgentTransport, status: SetupStatus) {
        _state.update {
            it.copy(transportName = chosen.name, runtimeAvailable = true, ready = status.ready, keys = status.maskedKeys,
                versionLine = "otto ${status.version.otto} · api ${status.version.api} · ${chosen.name}", error = "")
        }
        if (status.ready) openSession(null)
    }

    private fun applyHello(chosen: AgentTransport, hello: Hello) {
        _state.update {
            it.copy(transportName = chosen.name, runtimeAvailable = true, ready = true, keys = emptyMap(),
                versionLine = "otto ${hello.ottoVersion} · api ${hello.apiVersion} · ${chosen.name}", error = "")
        }
        openSession(null)
    }

    fun setKey(name: String, value: String) = viewModelScope.launch {
        val t = transport ?: return@launch
        val reply = t.setKey(name, value)
        if (reply is Reply.Ok) (t.setupStatus() as? Reply.Ok)?.let { applyStatus(t, it.value) }
        else _state.update { it.copy(error = if (reply is Reply.Unsupported) "keys live on the machine running otto serve" else reply.failure("could not save the key") ?: "") }
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
        val opened = when (val reply = t.openSession(ref)) {
            is Reply.Ok -> reply.value
            else -> { _state.update { it.copy(error = reply.failure("could not open the session") ?: "") }; return@launch }
        }
        val messages = if (ref != null) (t.transcript(opened.sessionId) as? Reply.Ok)?.value?.messages?.map { Message(it.role, it.text) } ?: emptyList()
        else emptyList()
        _state.update { it.copy(sessionId = opened.sessionId, sessionTitle = opened.title, messages = messages,
            board = emptyList(), status = "", screen = Screen.CHAT) }
    }

    fun loadSessions() = viewModelScope.launch {
        val reply = transport?.listSessions() ?: return@launch
        val rows = (reply as? Reply.Ok)?.value?.sessions?.map { SessionRow(it.id, it.title, it.turns, it.age) } ?: emptyList()
        _state.update { it.copy(sessions = rows, error = reply.failure("could not list sessions") ?: it.error) }
    }

    fun deleteSession(id: String) = viewModelScope.launch {
        val reply = transport?.deleteSession(id) ?: return@launch
        reply.failure("could not delete the session")?.let { message -> _state.update { it.copy(error = message) } }
        loadSessions()
    }

    fun send(text: String) = viewModelScope.launch {
        val t = transport ?: return@launch
        if (text.isBlank() || _state.value.running) return@launch
        refreshService()
        _state.update { it.copy(messages = it.messages + Message("you", text), running = true, status = "starting", board = emptyList(), error = "") }
        OttoForegroundService.start(getApplication(), text)
        val reply = t.startTurn(_state.value.sessionId.ifBlank { null }, text)
        if (reply !is Reply.Ok) {
            _state.update { it.copy(running = false, error = reply.failure("could not start") ?: "") }
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
            // Stop in the notification, from outside the chat: the same cancel as the Stop button.
            "cancel_request" -> stop()
        }
        OttoAccessibilityService.instance?.guard?.let { g -> _state.update { it.copy(handedOver = g.handedOver, guardLog = g.log.toList().takeLast(20)) } }
    }
}
