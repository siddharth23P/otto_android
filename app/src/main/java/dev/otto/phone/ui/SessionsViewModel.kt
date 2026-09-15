package dev.otto.phone.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.otto.phone.OttoApp
import dev.otto.phone.protocol.ExportedSession
import dev.otto.phone.protocol.Reply
import dev.otto.phone.state.SessionsAction
import dev.otto.phone.state.SessionsReducer
import dev.otto.phone.state.SessionsState
import dev.otto.phone.state.problem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** The sessions drawer: the list, the current session's spend, rename, export, import, delete. */
class SessionsViewModel(app: Application) : AndroidViewModel(app) {
    private val connection = (app as OttoApp).connection
    private val _state = MutableStateFlow(SessionsState())
    val state: StateFlow<SessionsState> = _state
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts

    /** An export waiting for the person to pick where it goes. */
    private val _pendingExport = MutableStateFlow<ExportedSession?>(null)
    val pendingExport: StateFlow<ExportedSession?> = _pendingExport

    /** A session that was imported, for the chat to open. */
    private val _imported = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val imported: SharedFlow<String> = _imported

    private fun dispatch(action: SessionsAction) = _state.update { SessionsReducer.reduce(it, action) }

    fun load() = viewModelScope.launch {
        val t = connection.current ?: return@launch
        dispatch(SessionsAction.Loading)
        dispatch(SessionsAction.Loaded(t.listSessions()))
    }

    /** The drawer opened, or the chat moved to another session. */
    fun setCurrent(id: String) {
        if (id != _state.value.currentId) dispatch(SessionsAction.Current(id))
    }

    fun loadUsage() = viewModelScope.launch {
        val t = connection.current ?: return@launch
        val id = _state.value.currentId.ifBlank { null } ?: return@launch
        val reply = t.sessionUsage(id)
        // An older otto has no usage op; the drawer just leaves the section out.
        if (reply !is Reply.Unsupported) dispatch(SessionsAction.UsageLoaded(reply))
    }

    fun rename(id: String, title: String, onDone: (String) -> Unit = {}) = viewModelScope.launch {
        val t = connection.current ?: return@launch
        val reply = t.renameSession(id, title.trim())
        dispatch(SessionsAction.Renamed(reply))
        if (reply is Reply.Ok) onDone(reply.value.title) else _state.value.error?.let(::say)
    }

    /** First tap arms, second tap within the window deletes. Returns true when it deleted. */
    fun tapDelete(id: String, onDeleted: (String) -> Unit = {}) {
        val now = System.currentTimeMillis()
        if (!_state.value.deleteArmed(id, now)) { dispatch(SessionsAction.ArmDelete(id, now)); return }
        viewModelScope.launch {
            val t = connection.current ?: return@launch
            val reply = t.deleteSession(id)
            dispatch(SessionsAction.Deleted(id, reply))
            if (reply is Reply.Ok && reply.value.deleted) onDeleted(id) else _state.value.error?.let(::say)
        }
    }

    fun export(id: String) = viewModelScope.launch {
        val t = connection.current ?: return@launch
        when (val reply = t.exportSession(id)) {
            is Reply.Ok -> _pendingExport.value = reply.value
            else -> say(reply.problem("couldn't export the session") ?: "")
        }
    }

    /** The person picked a file for the pending export (or cancelled, with null). */
    fun writeExport(uri: Uri?) = viewModelScope.launch {
        val export = _pendingExport.value ?: return@launch
        _pendingExport.value = null
        if (uri == null) return@launch
        val written = withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(export.data.toString().toByteArray()) } != null
            }.getOrDefault(false)
        }
        say(if (written) "exported" else "couldn't write that file")
    }

    fun import(uri: Uri?) = viewModelScope.launch {
        if (uri == null) return@launch
        val t = connection.current ?: return@launch
        val data = withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { Json.parseToJsonElement(it.readBytes().decodeToString()) }
            }.getOrNull()
        }
        if (data == null) { say("that file isn't an otto session export"); return@launch }
        val reply = t.importSession(data)
        dispatch(SessionsAction.Imported(reply))
        when (reply) {
            is Reply.Ok -> { say("imported"); _imported.tryEmit(reply.value.sessionId) }
            else -> _state.value.error?.let(::say)
        }
    }

    private fun say(message: String) {
        if (message.isBlank()) return
        _toasts.tryEmit(message)
        dispatch(SessionsAction.ErrorShown)
    }

}
