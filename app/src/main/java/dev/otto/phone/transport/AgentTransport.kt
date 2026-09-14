package dev.otto.phone.transport

import kotlinx.serialization.json.JsonObject

/** How the UI reaches otto. Two implementations, one shape: every method
 *  answers with the JSON object the Python entry (or the server) returned. */
interface AgentTransport {
    val name: String
    suspend fun start(): JsonObject
    suspend fun setupStatus(): JsonObject
    suspend fun setKey(name: String, value: String): JsonObject
    suspend fun doctor(): JsonObject
    suspend fun listSessions(): JsonObject
    suspend fun openSession(ref: String?): JsonObject
    suspend fun transcript(ref: String): JsonObject
    suspend fun deleteSession(sessionId: String): JsonObject
    suspend fun startTurn(sessionId: String, text: String): JsonObject
    suspend fun answer(sessionId: String, threadId: String, text: String): JsonObject
    suspend fun cancel(sessionId: String): JsonObject
    fun close()
}
