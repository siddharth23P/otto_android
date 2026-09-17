package dev.otto.phone.state

import dev.otto.phone.protocol.DocumentInfo

/** Every place the app can be. Chat is the root; everything else is pushed over it with a back arrow. */
sealed interface Route {
    data object Chat : Route
    data object Settings : Route
    data object Keys : Route
    data object Doctor : Route
    data object Usage : Route
    data object Routing : Route
    data object Memory : Route
    /** The current conversation's files. */
    data object Files : Route
    data class Note(val packageName: String) : Route
    /** A research document a turn wrote. `sessionId` names where its files live. */
    data class Document(val document: DocumentInfo, val sessionId: String = "") : Route
}

/** A simple route stack: no navigation library, one list. The root never pops, and pushing the
 *  screen already on top does nothing, so a double tap does not stack two copies. */
data class BackStack(val routes: List<Route> = listOf(Route.Chat)) {
    val top: Route get() = routes.last()
    val canPop: Boolean get() = routes.size > 1

    fun push(route: Route): BackStack = if (top == route) this else copy(routes = routes + route)
    fun pop(): BackStack = if (canPop) copy(routes = routes.dropLast(1)) else this
    /** Back to chat, forgetting everything pushed. */
    fun home(): BackStack = copy(routes = listOf(routes.first()))
}

/** A value loaded from otto, as a screen draws it: a pulse while loading, the server's words when it
 *  failed, "needs a newer otto" when the other end has no such request. */
sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Ready<out T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
    data object Unsupported : Load<Nothing>

    val valueOrNull: T? get() = (this as? Ready)?.value

    companion object {
        const val COULD_NOT_LOAD = "couldn't load — check your connection, then try again."

        fun <T> of(reply: dev.otto.phone.protocol.Reply<T>): Load<T> = when (reply) {
            is dev.otto.phone.protocol.Reply.Ok -> Ready(reply.value)
            is dev.otto.phone.protocol.Reply.Err -> Failed(reply.message.ifBlank { COULD_NOT_LOAD })
            dev.otto.phone.protocol.Reply.Unsupported -> Unsupported
        }
    }
}
