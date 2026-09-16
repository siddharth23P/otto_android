package dev.otto.phone.transport

import android.content.Context
import dev.otto.phone.data.Prefs
import dev.otto.phone.protocol.Hello
import dev.otto.phone.protocol.Reply
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The one live transport, shared by every screen: held by OttoApp, so several ViewModels use one
 *  socket and a screen going away does not close it. */
class Connection(private val context: Context, private val prefs: Prefs) {
    data class Started(val transport: AgentTransport, val hello: Reply<Hello>)

    private val mutex = Mutex()
    private val _transport = MutableStateFlow<AgentTransport?>(null)
    val transport: StateFlow<AgentTransport?> = _transport
    val current: AgentTransport? get() = _transport.value

    /** Close whatever is live, then start the transport Prefs names. */
    suspend fun connect(): Started = mutex.withLock {
        _transport.value?.close()
        val chosen: AgentTransport = if (prefs.transport() == "serve" && prefs.serveUrl().isNotBlank())
            ServeTransport(prefs.serveUrl(), prefs.serveToken()) else EmbeddedTransport(context, prefs)
        _transport.value = chosen
        val hello = runCatching { chosen.start() }.getOrElse { Reply.Err("failed", it.message ?: "could not start") }
        Started(chosen, hello)
    }

    fun close() {
        _transport.value?.close()
        _transport.value = null
    }
}
