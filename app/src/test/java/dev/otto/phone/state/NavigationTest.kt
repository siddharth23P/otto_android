package dev.otto.phone.state

import dev.otto.phone.protocol.AgentEvent
import dev.otto.phone.protocol.DocumentInfo
import dev.otto.phone.protocol.Reply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
    @Test fun theRootNeverPopsAndTheTopDoesNotStack() {
        var s = BackStack()
        assertEquals(Route.Chat, s.top); assertFalse(s.canPop)
        assertEquals(s, s.pop())
        s = s.push(Route.Settings).push(Route.Settings).push(Route.Keys)
        assertEquals(listOf(Route.Chat, Route.Settings, Route.Keys), s.routes)
        assertTrue(s.canPop)
        s = s.pop()
        assertEquals(Route.Settings, s.top)
        s = s.push(Route.Document(DocumentInfo(path = "a.md"), "s1")).push(Route.Document(DocumentInfo(path = "a.md"), "s1"))
        assertEquals(3, s.routes.size)
        assertEquals(BackStack(), s.home())
    }

    @Test fun aLoadReadsAReply() {
        assertEquals(Load.Ready(3), Load.of(Reply.Ok(3)))
        assertEquals(Load.Failed("gone"), Load.of(Reply.Err("x", "gone")))
        assertEquals(Load.Failed(Load.COULD_NOT_LOAD), Load.of(Reply.Err("x", "")))
        assertEquals(Load.Unsupported, Load.of(Reply.Unsupported))
        assertEquals(3, Load.of(Reply.Ok(3)).valueOrNull)
        assertNull(Load.Loading.valueOrNull)
    }

    @Test fun aNoticeIsASystemRowThatLeavesTheTurnRunning() {
        var s = ChatReducer.reduce(ChatState(sessionId = "s1"), ChatAction.Sent("hi", 1_000))
        s = ChatReducer.reduce(s, ChatAction.Event(AgentEvent.Started("s1"), 1_100))
        s = ChatReducer.reduce(s, ChatAction.Notice("answer", "no question is waiting for that answer", 1_200))
        assertTrue(s.running)
        assertEquals(ChatBlock.System("no question is waiting for that answer", "answer", atMs = 1_200), s.blocks.last())
    }
}
