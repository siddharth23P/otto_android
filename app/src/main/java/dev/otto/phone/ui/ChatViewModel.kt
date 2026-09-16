package dev.otto.phone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.otto.phone.OttoApp
import dev.otto.phone.access.OttoAccessibilityService
import dev.otto.phone.protocol.AgentEvent
import dev.otto.phone.protocol.Reply
import dev.otto.phone.service.OttoForegroundService
import dev.otto.phone.state.ChatAction
import dev.otto.phone.state.ChatBlock
import dev.otto.phone.state.ChatReducer
import dev.otto.phone.state.ChatState
import dev.otto.phone.state.problem
import dev.otto.phone.transport.AgentTransport
import dev.otto.phone.transport.Answers
import dev.otto.phone.transport.EventBus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The conversation: one session at a time, every change through ChatReducer. Turn events arrive on
 *  EventBus; the phone's guard log is read beside each one so only what a turn added becomes its
 *  notes. The foreground service lives exactly as long as a turn. */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val connection = (app as OttoApp).connection
    private val transport: AgentTransport? get() = connection.current
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Short confirmations and failures that are not part of the conversation. */
    val toasts: SharedFlow<String> = _toasts

    init {
        viewModelScope.launch { EventBus.events.collect { json -> onEvent(AgentEvent.parse(json)) } }
        // The agent-at-work card can answer a question from another app: show that answer here too.
        viewModelScope.launch {
            Answers.sent.collect { sent ->
                if (_state.value.ask?.threadId == sent.threadId) dispatch(ChatAction.Answered(sent.text, now()))
            }
        }
    }

    private fun now() = System.currentTimeMillis()
    private fun guardLog(): List<String>? = OttoAccessibilityService.instance?.guard?.log?.toList()
    private fun dispatch(action: ChatAction) = _state.update { ChatReducer.reduce(it, action) }

    /** A transport came up (or changed): carry on with this session if it still exists there. */
    fun onConnected() = viewModelScope.launch {
        val current = _state.value.sessionId.ifBlank { null }
        if (_state.value.running) return@launch
        if (!open(current) && current != null) open(null)
    }

    fun openSession(ref: String?) = viewModelScope.launch { open(ref) }

    /** Close the session on otto's side (its handle, not its history), then start a fresh one. */
    fun newSession() = viewModelScope.launch {
        if (_state.value.running) { _toasts.tryEmit("stop the running turn first"); return@launch }
        val t = transport ?: return@launch
        _state.value.sessionId.ifBlank { null }?.let { t.closeSession(it) }
        open(null)
    }

    private suspend fun open(ref: String?): Boolean {
        val t = transport ?: return false
        val opened = when (val reply = t.openSession(ref)) {
            is Reply.Ok -> reply.value
            else -> {
                dispatch(ChatAction.Notice("open", reply.problem("couldn't open the session") ?: "", now()))
                return false
            }
        }
        val transcript = if (ref != null) (t.transcript(opened.sessionId) as? Reply.Ok)?.value else null
        dispatch(ChatAction.Opened(opened.sessionId, opened.title, opened.turns, transcript))
        loadUsage()
        return true
    }

    fun loadUsage() = viewModelScope.launch {
        val sid = _state.value.sessionId.ifBlank { null } ?: return@launch
        (transport?.sessionUsage(sid) as? Reply.Ok)?.let { dispatch(ChatAction.UsageLoaded(it.value)) }
    }

    fun renamed(sessionId: String, title: String) {
        if (sessionId == _state.value.sessionId) dispatch(ChatAction.Renamed(title))
    }

    fun send(text: String): Boolean {
        val t = transport
        if (text.isBlank() || _state.value.running || t == null) return false
        dispatch(ChatAction.Sent(text, now(), guardLog() ?: emptyList()))
        OttoForegroundService.start(getApplication(), text.take(80))
        viewModelScope.launch {
            val reply = t.startTurn(_state.value.sessionId.ifBlank { null }, text)
            if (reply !is Reply.Ok) {
                val code = (reply as? Reply.Err)?.code ?: "unsupported"
                // Only a turn that never started fails here; a started one ends with its own event.
                if (_state.value.turn?.phase == "starting" || _state.value.running) {
                    dispatch(ChatAction.StartFailed(code, reply.problem("couldn't start") ?: "", now()))
                }
                OttoForegroundService.stop(getApplication())
            }
        }
        return true
    }

    fun answer(text: String) = viewModelScope.launch {
        val ask = _state.value.ask ?: return@launch
        if (text.isBlank()) return@launch
        dispatch(ChatAction.Answered(text, now()))
        val reply = transport?.answer(_state.value.sessionId, ask.threadId, text) ?: return@launch
        reply.problem("otto didn't get that answer")?.let { dispatch(ChatAction.Notice("answer", it, now())) }
    }

    fun stop() = viewModelScope.launch {
        val sid = _state.value.sessionId.ifBlank { null } ?: return@launch
        transport?.cancel(sid)
    }

    /** Otto's latest answer, for Copy last answer. */
    val lastAnswer: String? get() = _state.value.blocks.lastOrNull { it is ChatBlock.Otto }?.let { (it as ChatBlock.Otto).text }

    fun toast(message: String) { _toasts.tryEmit(message) }

    private fun onEvent(event: AgentEvent) {
        // Stop in the notification, from outside the chat: the same cancel as the Stop button.
        if (event is AgentEvent.CancelRequest) stop()
        dispatch(ChatAction.Event(event, now(), guardLog()))
        if (event is AgentEvent.Final || event is AgentEvent.Error) {
            OttoForegroundService.stop(getApplication())
            if (event is AgentEvent.Final) loadUsage()
        }
    }
}
