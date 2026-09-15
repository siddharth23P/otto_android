package dev.otto.phone.state

import dev.otto.phone.protocol.DeletedSession
import dev.otto.phone.protocol.ImportedSession
import dev.otto.phone.protocol.LessonDeleted
import dev.otto.phone.protocol.LessonList
import dev.otto.phone.protocol.LessonRow
import dev.otto.phone.protocol.LessonsCleared
import dev.otto.phone.protocol.ModelInfo
import dev.otto.phone.protocol.ModelList
import dev.otto.phone.protocol.NoteDetail
import dev.otto.phone.protocol.NoteList
import dev.otto.phone.protocol.NoteSummary
import dev.otto.phone.protocol.RenamedSession
import dev.otto.phone.protocol.Reply
import dev.otto.phone.protocol.RouteChange
import dev.otto.phone.protocol.RouteOption
import dev.otto.phone.protocol.RouteOptions
import dev.otto.phone.protocol.RouteRow
import dev.otto.phone.protocol.RoutingList
import dev.otto.phone.protocol.SessionList
import dev.otto.phone.protocol.SessionRow
import dev.otto.phone.protocol.SessionUsage
import dev.otto.phone.protocol.UsageSnapshot
import dev.otto.phone.transport.NEEDS_NEWER_OTTO
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListReducersTest {
    // -- confirm ------------------------------------------------------------------------------------

    @Test fun tapTwiceHoldsForThreeAndAHalfSeconds() {
        val armed = Armed("delete:a", 1_000)
        assertTrue(armed.holds("delete:a", 1_000))
        assertTrue(armed.holds("delete:a", 4_500))
        assertFalse(armed.holds("delete:a", 4_501))
        assertFalse(armed.holds("delete:b", 1_200))
        assertEquals(NEEDS_NEWER_OTTO, Reply.Unsupported.problem())
        assertEquals("fallback", Reply.Err("x", "").problem("fallback"))
        assertNull(Reply.Ok(1).problem())
    }

    // -- sessions -----------------------------------------------------------------------------------

    private val a = SessionRow(id = "a", title = "one", turns = 1)
    private val b = SessionRow(id = "b", title = "two", turns = 2)

    @Test fun sessionsLoadRenameAndFail() {
        var s = SessionsReducer.reduce(SessionsState(), SessionsAction.Loading)
        assertTrue(s.loading)
        s = SessionsReducer.reduce(s, SessionsAction.Loaded(Reply.Ok(SessionList(listOf(a, b)))))
        assertTrue(s.loaded); assertFalse(s.loading); assertEquals(2, s.rows.size)
        s = SessionsReducer.reduce(s, SessionsAction.Renamed(Reply.Ok(RenamedSession("b", "renamed"))))
        assertEquals("renamed", s.rows[1].title)
        s = SessionsReducer.reduce(s, SessionsAction.Renamed(Reply.Unsupported))
        assertEquals(NEEDS_NEWER_OTTO, s.error)
        s = SessionsReducer.reduce(s, SessionsAction.ErrorShown)
        assertNull(s.error)
        s = SessionsReducer.reduce(SessionsState(rows = listOf(a)), SessionsAction.Loaded(Reply.Err("disconnected", "not connected")))
        assertEquals("not connected", s.error); assertEquals(listOf(a), s.rows)
    }

    @Test fun deletingTakesTwoTapsAndDropsTheRow() {
        var s = SessionsState(rows = listOf(a, b), currentId = "a", usage = SessionUsage())
        assertFalse(s.deleteArmed("a", 0))
        s = SessionsReducer.reduce(s, SessionsAction.ArmDelete("a", 0))
        assertTrue(s.deleteArmed("a", 3_000)); assertFalse(s.deleteArmed("b", 3_000)); assertFalse(s.deleteArmed("a", 3_600))
        s = SessionsReducer.reduce(s, SessionsAction.Deleted("a", Reply.Ok(DeletedSession("a", true))))
        assertEquals(listOf(b), s.rows); assertNull(s.armed); assertNull(s.usage)
        s = SessionsReducer.reduce(s, SessionsAction.Deleted("b", Reply.Err("busy", "that session is running a turn")))
        assertEquals(listOf(b), s.rows); assertEquals("that session is running a turn", s.error)
    }

    @Test fun anImportedSessionGoesToTheTopAndUsageLoads() {
        var s = SessionsReducer.reduce(SessionsState(rows = listOf(a)), SessionsAction.Imported(Reply.Ok(ImportedSession("0123456789", "brought back", 4))))
        assertEquals(listOf("0123456789", "a"), s.rows.map { it.id }); assertEquals("01234567", s.rows[0].shortId); assertEquals("just now", s.rows[0].age)
        s = SessionsReducer.reduce(s, SessionsAction.UsageLoaded(Reply.Ok(SessionUsage(UsageSnapshot(calls = 2), turnTokens = listOf(1, 2)))))
        assertEquals(2, s.usage!!.usage.calls)
        s = SessionsReducer.reduce(s, SessionsAction.Current("zz"))
        assertNull(s.usage)
    }

    // -- routing ------------------------------------------------------------------------------------

    private val reason = RouteRow(task = "reason", pin = null, default = "inception:mercury-2")

    @Test fun routingLoadsAndPins() {
        var s = RoutingReducer.reduce(RoutingState(), RoutingAction.Loaded(Reply.Ok(RoutingList(listOf(reason)))))
        assertTrue(s.editable)
        s = RoutingReducer.reduce(s, RoutingAction.OptionsLoaded("reason", Reply.Ok(RouteOptions("reason", listOf(JsonPrimitive("openai:gpt-5-mini"))))))
        assertEquals(listOf(RouteOption("openai:gpt-5-mini", "openai:gpt-5-mini")), s.options["reason"])
        s = RoutingReducer.reduce(s, RoutingAction.ModelsLoaded(Reply.Ok(ModelList(listOf(ModelInfo(spec = "openai:gpt-5-mini"))))))
        assertEquals(1, s.models.size)
        s = RoutingReducer.reduce(s, RoutingAction.ChangeRequested("reason"))
        assertEquals("reason", s.pending); assertFalse(s.editable)
        s = RoutingReducer.reduce(s, RoutingAction.Changed("reason", Reply.Ok(RouteChange("reason", "openai:gpt-5-mini"))))
        assertEquals("openai:gpt-5-mini", s.routes.single().pin); assertNull(s.pending)
        s = RoutingReducer.reduce(s, RoutingAction.Changed("reason", Reply.Ok(RouteChange("reason", ""))))
        assertNull(s.routes.single().pin)
    }

    @Test fun routingIsDisabledWithAReasonWhileATurnRuns() {
        var s = RoutingReducer.reduce(RoutingState(routes = listOf(reason)), RoutingAction.TurnRunning(true))
        assertEquals("routing can't change while a turn runs", s.disabledReason)
        assertNull(RoutingReducer.reduce(s, RoutingAction.ChangeRequested("reason")).pending)
        s = RoutingReducer.reduce(s, RoutingAction.TurnRunning(false))
        s = RoutingReducer.reduce(s, RoutingAction.ChangeRequested("reason"))
        s = RoutingReducer.reduce(s, RoutingAction.Changed("reason", Reply.Err("invalid_pin", "no model openai:nope")))
        assertEquals("no model openai:nope", s.error); assertNull(s.pending); assertNull(s.routes.single().pin)
        s = RoutingReducer.reduce(s, RoutingAction.Changed("reason", Reply.Err("busy", "a turn is running")))
        assertEquals("a turn is running", s.error)
    }

    @Test fun anOldOttoHasNoRouting() {
        val s = RoutingReducer.reduce(RoutingState(loading = true), RoutingAction.Loaded(Reply.Unsupported))
        assertTrue(s.unsupported); assertFalse(s.loading); assertEquals(NEEDS_NEWER_OTTO, s.disabledReason)
    }

    // -- lessons & notes ----------------------------------------------------------------------------

    private val l1 = LessonRow(id = "1".repeat(64), text = "tap Add to Cart, not Enter", kind = "phone_lesson")
    private val l2 = LessonRow(id = "2".repeat(64), text = "the cart icon is top right", kind = "phone_lesson")

    @Test fun lessonsLoadDeleteAndClear() {
        var s = LessonsReducer.reduce(LessonsState(), LessonsAction.TabChosen(LessonTab.PHONE_LESSONS))
        s = LessonsReducer.reduce(s, LessonsAction.Loading("phone_lesson"))
        assertTrue("phone_lesson" in s.loading); assertFalse(s.loaded("phone_lesson"))
        s = LessonsReducer.reduce(s, LessonsAction.Loaded("phone_lesson", Reply.Ok(LessonList("phone_lesson", listOf(l1, l2)))))
        assertTrue(s.loaded("phone_lesson")); assertTrue(s.loading.isEmpty())
        val key = LessonsState.deleteKey("phone_lesson", l1.id)
        s = LessonsReducer.reduce(s, LessonsAction.Arm(key, 100))
        assertTrue(s.armedFor(key, 3_000))
        s = LessonsReducer.reduce(s, LessonsAction.Deleted("phone_lesson", l1.id, Reply.Ok(LessonDeleted("phone_lesson", l1.id, true))))
        assertEquals(listOf(l2), s.lessons["phone_lesson"]); assertNull(s.armed)
        s = LessonsReducer.reduce(s, LessonsAction.Cleared("phone_lesson", Reply.Err("busy", "a turn is running")))
        assertEquals("a turn is running", s.error); assertEquals(listOf(l2), s.lessons["phone_lesson"])
        s = LessonsReducer.reduce(s, LessonsAction.Cleared("phone_lesson", Reply.Ok(LessonsCleared("phone_lesson", 1))))
        assertEquals(emptyList<LessonRow>(), s.lessons["phone_lesson"])
    }

    @Test fun appNotesKeepSeededTextAndCountLearnedOnes() {
        val pkg = "in.amazon.mShop.android.shopping"
        val kind = LessonsState.noteKind(pkg)
        val learned = listOf(l1.copy(kind = kind), l2.copy(kind = kind))
        var s = LessonsReducer.reduce(LessonsState(tab = LessonTab.APP_NOTES), LessonsAction.NotesLoaded(Reply.Ok(NoteList(listOf(NoteSummary(pkg, true, 2), NoteSummary("com.x", false, 1))))))
        assertTrue(s.notesLoaded)
        s = LessonsReducer.reduce(s, LessonsAction.NoteOpened(Reply.Ok(NoteDetail(pkg, "shipped text", learned, "shown text"))))
        assertEquals(learned, s.lessons[kind])
        s = LessonsReducer.reduce(s, LessonsAction.Deleted(kind, l1.id, Reply.Ok(LessonDeleted(kind, l1.id, true))))
        assertEquals(1, s.openNote!!.learned.size); assertEquals("shipped text", s.openNote!!.seeded)
        assertEquals(listOf(1, 1), s.notes.map { it.learned })
        s = LessonsReducer.reduce(s, LessonsAction.NoteClosed)
        assertNull(s.openNote)
    }

    @Test fun anOldOttoHasNoLessons() {
        val s = LessonsReducer.reduce(LessonsState(), LessonsAction.Loaded("lesson", Reply.Unsupported))
        assertTrue(s.unsupported); assertNull(s.error)
    }
}
