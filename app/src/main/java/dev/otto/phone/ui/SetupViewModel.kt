package dev.otto.phone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.otto.phone.OttoApp
import dev.otto.phone.protocol.DoctorReport
import dev.otto.phone.protocol.ModelList
import dev.otto.phone.protocol.ProbeResult
import dev.otto.phone.protocol.Reply
import dev.otto.phone.protocol.SessionUsage
import dev.otto.phone.protocol.SetupStatus
import dev.otto.phone.state.Load
import dev.otto.phone.state.writeProblem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupState(
    val status: Load<SetupStatus> = Load.Idle,
    val doctor: Load<DoctorReport> = Load.Idle,
    val models: Load<ModelList> = Load.Idle,
    val usage: Load<SessionUsage> = Load.Idle,
    /** Probe results by vendor name, and which are running now. */
    val probes: Map<String, Load<ProbeResult>> = emptyMap(),
    /** The key being saved, and what the last save said (the server's refusal, verbatim). */
    val saving: String? = null,
    val keyMessage: Pair<String, String>? = null,
)

/** Keys, probe, doctor, models and usage: the setup screens' data. */
class SetupViewModel(app: Application) : AndroidViewModel(app) {
    private val connection = (app as OttoApp).connection
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state

    fun loadStatus() = viewModelScope.launch {
        val t = connection.current ?: run { _state.update { it.copy(status = Load.Failed(Load.COULD_NOT_LOAD)) }; return@launch }
        _state.update { it.copy(status = Load.Loading) }
        val reply = t.setupStatus()
        _state.update { it.copy(status = Load.of(reply)) }
    }

    /** Saves a key; `onSaved` runs when otto took it, so the app can ask whether it is ready now. */
    fun setKey(name: String, value: String, onSaved: () -> Unit = {}) = viewModelScope.launch {
        val t = connection.current ?: return@launch
        if (value.isBlank()) return@launch
        _state.update { it.copy(saving = name, keyMessage = null) }
        val reply = t.setKey(name, value.trim())
        _state.update { it.copy(saving = null, keyMessage = name to (if (reply is Reply.Ok) "saved · ${reply.value.masked}" else reply.writeProblem("couldn't save the key") ?: "")) }
        if (reply is Reply.Ok) { onSaved(); loadStatus() }
    }

    fun probe(name: String) = viewModelScope.launch {
        val t = connection.current ?: return@launch
        _state.update { it.copy(probes = it.probes + (name to Load.Loading)) }
        val reply = t.probe(name)
        _state.update { it.copy(probes = it.probes + (name to Load.of(reply))) }
    }

    fun loadDoctor() = viewModelScope.launch {
        val t = connection.current ?: run { _state.update { it.copy(doctor = Load.Failed(Load.COULD_NOT_LOAD)) }; return@launch }
        _state.update { it.copy(doctor = Load.Loading) }
        val reply = t.doctor()
        _state.update { it.copy(doctor = Load.of(reply)) }
    }

    fun loadModels() = viewModelScope.launch {
        val t = connection.current ?: return@launch
        _state.update { it.copy(models = Load.Loading) }
        val reply = t.models()
        _state.update { it.copy(models = Load.of(reply)) }
    }

    fun loadUsage(sessionId: String) = viewModelScope.launch {
        val t = connection.current ?: run { _state.update { it.copy(usage = Load.Failed(Load.COULD_NOT_LOAD)) }; return@launch }
        if (sessionId.isBlank()) { _state.update { it.copy(usage = Load.Ready(SessionUsage())) }; return@launch }
        _state.update { it.copy(usage = Load.Loading) }
        val reply = t.sessionUsage(sessionId)
        _state.update { it.copy(usage = Load.of(reply)) }
    }
}
