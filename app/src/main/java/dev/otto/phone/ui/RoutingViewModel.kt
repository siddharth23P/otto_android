package dev.otto.phone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.otto.phone.OttoApp
import dev.otto.phone.protocol.Reply
import dev.otto.phone.state.RoutingAction
import dev.otto.phone.state.RoutingReducer
import dev.otto.phone.state.RoutingState
import dev.otto.phone.state.writeProblem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which model does which task: the routes, the choices for one, pin and clear. */
class RoutingViewModel(app: Application) : AndroidViewModel(app) {
    private val connection = (app as OttoApp).connection
    private val _state = MutableStateFlow(RoutingState())
    val state: StateFlow<RoutingState> = _state
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts

    private fun dispatch(action: RoutingAction) = _state.update { RoutingReducer.reduce(it, action) }

    fun load() = viewModelScope.launch {
        val t = connection.current ?: run { dispatch(RoutingAction.Loaded(Reply.Err("disconnected", ""))); return@launch }
        dispatch(RoutingAction.Loading)
        dispatch(RoutingAction.Loaded(t.routes()))
        val models = t.models()
        if (models !is Reply.Unsupported) dispatch(RoutingAction.ModelsLoaded(models))
    }

    fun loadOptions(task: String) = viewModelScope.launch {
        val t = connection.current ?: return@launch
        dispatch(RoutingAction.OptionsLoaded(task, t.routeOptions(task)))
    }

    fun setTurnRunning(running: Boolean) {
        if (_state.value.turnRunning != running) dispatch(RoutingAction.TurnRunning(running))
    }

    /** An empty spec is "no pin": the default route. */
    fun pin(task: String, spec: String) = change(task) { t -> if (spec.isBlank()) t.clearRoute(task) else t.pinRoute(task, spec) }

    fun clear(task: String) = change(task) { t -> t.clearRoute(task) }

    private fun change(task: String, call: suspend (dev.otto.phone.transport.AgentTransport) -> Reply<dev.otto.phone.protocol.RouteChange>) = viewModelScope.launch {
        val t = connection.current ?: return@launch
        if (!_state.value.editable) return@launch
        dispatch(RoutingAction.ChangeRequested(task))
        val reply = call(t)
        dispatch(RoutingAction.Changed(task, reply))
        when (reply) {
            is Reply.Ok -> reply.value.problems.firstOrNull()?.let { _toasts.tryEmit(it) }
            else -> {
                reply.writeProblem("couldn't change the pin")?.let { _toasts.tryEmit(it) }
                dispatch(RoutingAction.ErrorShown)
            }
        }
    }
}
