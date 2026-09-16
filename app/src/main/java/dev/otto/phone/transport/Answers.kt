package dev.otto.phone.transport

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Answers sent from outside the chat screen -- the agent-at-work card offers a question's choices while
 * Otto is working in another app (access/AgentOverlay.kt). The transport carries the answer to the agent;
 * this carries it to the chat, so the screen the person opens next shows their answer and not the
 * question again.
 */
object Answers {
    private val flow = MutableSharedFlow<Answer>(extraBufferCapacity = 16)

    data class Answer(val threadId: String, val text: String)

    val sent: SharedFlow<Answer> = flow

    fun send(threadId: String, text: String) {
        flow.tryEmit(Answer(threadId, text))
    }
}
