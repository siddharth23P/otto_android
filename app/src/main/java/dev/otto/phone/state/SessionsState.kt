package dev.otto.phone.state

import dev.otto.phone.protocol.DeletedSession
import dev.otto.phone.protocol.ImportedSession
import dev.otto.phone.protocol.RenamedSession
import dev.otto.phone.protocol.Reply
import dev.otto.phone.protocol.SessionList
import dev.otto.phone.protocol.SessionRow
import dev.otto.phone.protocol.SessionUsage

data class SessionsState(
    val rows: List<SessionRow> = emptyList(),
    val currentId: String = "",
    val loading: Boolean = false,
    val loaded: Boolean = false,
    /** The last failure, in the server's words. */
    val error: String? = null,
    val usage: SessionUsage? = null,
    val armed: Armed? = null,
) {
    fun deleteArmed(id: String, nowMs: Long): Boolean = armed?.holds(deleteKey(id), nowMs) == true

    companion object {
        fun deleteKey(id: String) = "delete:$id"
    }
}

sealed interface SessionsAction {
    data object Loading : SessionsAction
    data class Loaded(val reply: Reply<SessionList>) : SessionsAction
    data class Current(val id: String) : SessionsAction
    data class Renamed(val reply: Reply<RenamedSession>) : SessionsAction
    /** First tap on delete: arm it. The ViewModel checks `deleteArmed` before sending. */
    data class ArmDelete(val id: String, val nowMs: Long) : SessionsAction
    data class Deleted(val id: String, val reply: Reply<DeletedSession>) : SessionsAction
    data class Imported(val reply: Reply<ImportedSession>) : SessionsAction
    data class UsageLoaded(val reply: Reply<SessionUsage>) : SessionsAction
    data object ErrorShown : SessionsAction
}

object SessionsReducer {
    fun reduce(state: SessionsState, action: SessionsAction): SessionsState = when (action) {
        SessionsAction.Loading -> state.copy(loading = true, error = null)
        is SessionsAction.Loaded -> when (val r = action.reply) {
            is Reply.Ok -> state.copy(rows = r.value.sessions, loading = false, loaded = true, error = null)
            else -> state.copy(loading = false, error = r.problem("couldn't load sessions"))
        }
        is SessionsAction.Current -> state.copy(currentId = action.id, usage = state.usage?.takeIf { action.id == state.currentId })
        is SessionsAction.Renamed -> when (val r = action.reply) {
            is Reply.Ok -> state.copy(rows = state.rows.map { if (it.id == r.value.sessionId) it.copy(title = r.value.title) else it }, error = null)
            else -> state.copy(error = r.problem("couldn't rename the session"))
        }
        is SessionsAction.ArmDelete -> state.copy(armed = Armed(SessionsState.deleteKey(action.id), action.nowMs))
        is SessionsAction.Deleted -> when (val r = action.reply) {
            is Reply.Ok -> state.copy(
                rows = if (r.value.deleted) state.rows.filterNot { it.id == action.id } else state.rows,
                armed = null,
                usage = if (action.id == state.currentId) null else state.usage,
                error = if (r.value.deleted) null else "that session was already gone",
            )
            else -> state.copy(armed = null, error = r.problem("couldn't delete the session"))
        }
        is SessionsAction.Imported -> when (val r = action.reply) {
            is Reply.Ok -> state.copy(
                rows = listOf(SessionRow(id = r.value.sessionId, shortId = r.value.sessionId.take(8), title = r.value.title,
                    turns = r.value.turns, age = "just now")) + state.rows.filterNot { it.id == r.value.sessionId },
                error = null,
            )
            else -> state.copy(error = r.problem("couldn't import that file"))
        }
        is SessionsAction.UsageLoaded -> when (val r = action.reply) {
            is Reply.Ok -> state.copy(usage = r.value)
            else -> state.copy(usage = null, error = r.problem("couldn't load usage"))
        }
        SessionsAction.ErrorShown -> state.copy(error = null)
    }
}
