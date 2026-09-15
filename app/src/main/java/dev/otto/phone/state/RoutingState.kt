package dev.otto.phone.state

import dev.otto.phone.protocol.ModelInfo
import dev.otto.phone.protocol.ModelList
import dev.otto.phone.protocol.Reply
import dev.otto.phone.protocol.RouteChange
import dev.otto.phone.protocol.RouteOption
import dev.otto.phone.protocol.RouteOptions
import dev.otto.phone.protocol.RouteRow
import dev.otto.phone.protocol.RoutingList
import dev.otto.phone.transport.NEEDS_NEWER_OTTO

data class RoutingState(
    val routes: List<RouteRow> = emptyList(),
    val options: Map<String, List<RouteOption>> = emptyMap(),
    val models: List<ModelInfo> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val turnRunning: Boolean = false,
    /** The task whose pin is being changed right now. */
    val pending: String? = null,
    val error: String? = null,
    val unsupported: Boolean = false,
) {
    /** Why pins can't change now, or null when they can. */
    val disabledReason: String?
        get() = when {
            unsupported -> NEEDS_NEWER_OTTO
            turnRunning -> "routing can't change while a turn runs"
            pending != null -> "changing ${pending}…"
            else -> null
        }
    val editable: Boolean get() = disabledReason == null
}

sealed interface RoutingAction {
    data object Loading : RoutingAction
    data class Loaded(val reply: Reply<RoutingList>) : RoutingAction
    data class OptionsLoaded(val task: String, val reply: Reply<RouteOptions>) : RoutingAction
    data class ModelsLoaded(val reply: Reply<ModelList>) : RoutingAction
    data class ChangeRequested(val task: String) : RoutingAction
    data class Changed(val task: String, val reply: Reply<RouteChange>) : RoutingAction
    data class TurnRunning(val running: Boolean) : RoutingAction
    data object ErrorShown : RoutingAction
}

object RoutingReducer {
    fun reduce(state: RoutingState, action: RoutingAction): RoutingState = when (action) {
        RoutingAction.Loading -> state.copy(loading = true, error = null)
        is RoutingAction.Loaded -> when (val r = action.reply) {
            is Reply.Ok -> state.copy(routes = r.value.routes, loading = false, loaded = true, error = null, unsupported = false)
            Reply.Unsupported -> state.copy(loading = false, unsupported = true, error = null)
            is Reply.Err -> state.copy(loading = false, error = r.message.ifBlank { "couldn't load routing" })
        }
        is RoutingAction.OptionsLoaded -> when (val r = action.reply) {
            is Reply.Ok -> state.copy(options = state.options + (action.task to r.value.choices))
            else -> state.copy(error = r.problem("couldn't load the choices"))
        }
        is RoutingAction.ModelsLoaded -> when (val r = action.reply) {
            is Reply.Ok -> state.copy(models = r.value.models)
            else -> state.copy(error = r.problem("couldn't load models"))
        }
        is RoutingAction.ChangeRequested -> if (!state.editable) state else state.copy(pending = action.task, error = null)
        is RoutingAction.Changed -> when (val r = action.reply) {
            is Reply.Ok -> state.copy(
                pending = null, error = null,
                routes = state.routes.map { if (it.task == action.task) it.copy(pin = r.value.pin?.ifBlank { null }) else it },
            )
            Reply.Unsupported -> state.copy(pending = null, unsupported = true)
            is Reply.Err -> state.copy(pending = null, error = r.message.ifBlank { "couldn't change the pin" })
        }
        is RoutingAction.TurnRunning -> state.copy(turnRunning = action.running)
        RoutingAction.ErrorShown -> state.copy(error = null)
    }
}
