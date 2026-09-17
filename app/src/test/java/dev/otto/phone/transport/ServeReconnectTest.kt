package dev.otto.phone.transport

import dev.otto.phone.protocol.Reply
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** #16: a dropped `otto serve` socket comes back by itself, and a closed transport stays closed. */
class ServeReconnectTest {
    private val server = MockWebServer()
    /** The server side of every socket the app opened, in order. */
    private val sockets = CopyOnWriteArrayList<WebSocket>()

    private fun acceptOne() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { sockets.add(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if ("\"hello\"" in text) webSocket.send("""{"type":"hello_ok","otto_version":"0.1.2","api_version":1,"protocol_version":2,"min_protocol":1}""")
            }
        }))
    }

    @After fun stop() {
        // Open server-side sockets keep MockWebServer's queue from shutting down.
        sockets.forEach { runCatching { it.close(1000, null) } }
        runCatching { server.shutdown() }
    }

    @Test fun aDroppedSocketIsReopenedAndHelloedAgain() = runBlocking {
        acceptOne(); acceptOne()
        server.start()
        val transport = ServeTransport(server.url("/").toString().replaceFirst("http", "ws"), "t0k")
        val hello = transport.start()
        assertTrue(hello.toString(), hello is Reply.Ok)
        assertEquals(false, transport.reconnecting.value)

        sockets.first().close(1001, "going away")
        withTimeout(5_000) { transport.reconnecting.first { it } }
        withTimeout(10_000) { transport.reconnecting.first { !it } }
        assertEquals(2, server.requestCount)
        assertEquals(2, sockets.size)
        transport.close()
    }

    @Test fun aClosedTransportDoesNotComeBack() = runBlocking {
        acceptOne(); acceptOne()
        server.start()
        val transport = ServeTransport(server.url("/").toString().replaceFirst("http", "ws"), "t0k")
        assertTrue(transport.start() is Reply.Ok)
        transport.close()
        Thread.sleep(1_500)
        assertEquals(false, transport.reconnecting.value)
        assertEquals(1, server.requestCount)
    }

    @Test fun aServeThatNeverAnsweredIsNotRetried() = runBlocking {
        server.start()
        val url = server.url("/").toString().replaceFirst("http", "ws")
        server.shutdown()
        val transport = ServeTransport(url, "t0k")
        val reply = transport.start()
        assertTrue(reply.toString(), reply is Reply.Err)
        Thread.sleep(1_500)
        assertEquals(false, transport.reconnecting.value)
    }
}
