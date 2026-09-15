package dev.otto.phone.state

import dev.otto.phone.protocol.LessonDeleted
import dev.otto.phone.protocol.LessonList
import dev.otto.phone.protocol.LessonRow
import dev.otto.phone.protocol.LessonsCleared
import dev.otto.phone.protocol.NoteDetail
import dev.otto.phone.protocol.NoteList
import dev.otto.phone.protocol.NoteSummary
import dev.otto.phone.protocol.Reply

enum class LessonTab(val kind: String?) { LESSONS("lesson"), PHONE_LESSONS("phone_lesson"), APP_NOTES(null) }

data class LessonsState(
    val tab: LessonTab = LessonTab.LESSONS,
    /** Rows by kind: "lesson", "phone_lesson", "app_note:<package>". */
    val lessons: Map<String, List<LessonRow>> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val notes: List<NoteSummary> = emptyList(),
    val notesLoaded: Boolean = false,
    val openNote: NoteDetail? = null,
    val armed: Armed? = null,
    val error: String? = null,
    val unsupported: Boolean = false,
) {
    fun loaded(kind: String): Boolean = kind in lessons
    fun armedFor(key: String, nowMs: Long): Boolean = armed?.holds(key, nowMs) == true

    companion object {
        fun noteKind(packageName: String) = "app_note:$packageName"
        fun deleteKey(kind: String, lessonId: String) = "delete:$kind:$lessonId"
        fun clearKey(kind: String) = "clear:$kind"
    }
}

sealed interface LessonsAction {
    data class TabChosen(val tab: LessonTab) : LessonsAction
    data class Loading(val kind: String) : LessonsAction
    data class Loaded(val kind: String, val reply: Reply<LessonList>) : LessonsAction
    data class Arm(val key: String, val nowMs: Long) : LessonsAction
    data class Deleted(val kind: String, val lessonId: String, val reply: Reply<LessonDeleted>) : LessonsAction
    data class Cleared(val kind: String, val reply: Reply<LessonsCleared>) : LessonsAction
    data class NotesLoaded(val reply: Reply<NoteList>) : LessonsAction
    data class NoteOpened(val reply: Reply<NoteDetail>) : LessonsAction
    data object NoteClosed : LessonsAction
    data object ErrorShown : LessonsAction
}

object LessonsReducer {
    fun reduce(state: LessonsState, action: LessonsAction): LessonsState = when (action) {
        is LessonsAction.TabChosen -> state.copy(tab = action.tab, armed = null)
        is LessonsAction.Loading -> state.copy(loading = state.loading + action.kind, error = null)
        is LessonsAction.Loaded -> when (val r = action.reply) {
            is Reply.Ok -> state.copy(lessons = state.lessons + (action.kind to r.value.lessons), loading = state.loading - action.kind)
            else -> failed(state.copy(loading = state.loading - action.kind), r, "couldn't load what Otto learned")
        }
        is LessonsAction.Arm -> state.copy(armed = Armed(action.key, action.nowMs))
        is LessonsAction.Deleted -> when (val r = action.reply) {
            is Reply.Ok -> removed(state.copy(armed = null, error = null), action.kind) { rows -> rows.filterNot { it.id == action.lessonId } }
            else -> failed(state.copy(armed = null), r, "couldn't delete that")
        }
        is LessonsAction.Cleared -> when (val r = action.reply) {
            is Reply.Ok -> removed(state.copy(armed = null, error = null), action.kind) { emptyList() }
            else -> failed(state.copy(armed = null), r, "couldn't clear those")
        }
        is LessonsAction.NotesLoaded -> when (val r = action.reply) {
            is Reply.Ok -> state.copy(notes = r.value.notes, notesLoaded = true)
            else -> failed(state, r, "couldn't load app notes")
        }
        is LessonsAction.NoteOpened -> when (val r = action.reply) {
            is Reply.Ok -> state.copy(openNote = r.value, lessons = state.lessons + (LessonsState.noteKind(r.value.packageName) to r.value.learned))
            else -> failed(state, r, "couldn't open that note")
        }
        LessonsAction.NoteClosed -> state.copy(openNote = null, armed = null)
        LessonsAction.ErrorShown -> state.copy(error = null)
    }

    private fun failed(state: LessonsState, reply: Reply<*>, fallback: String): LessonsState =
        if (reply is Reply.Unsupported) state.copy(unsupported = true) else state.copy(error = reply.problem(fallback))

    /** Apply a removal to a kind's rows, and to the app note it belongs to when it is one. */
    private fun removed(state: LessonsState, kind: String, change: (List<LessonRow>) -> List<LessonRow>): LessonsState {
        val rows = change(state.lessons[kind] ?: emptyList())
        val pkg = kind.removePrefix("app_note:").takeIf { kind.startsWith("app_note:") }
        return state.copy(
            lessons = state.lessons + (kind to rows),
            openNote = state.openNote?.let { note -> if (note.packageName == pkg) note.copy(learned = change(note.learned)) else note },
            notes = if (pkg == null) state.notes else state.notes.map { n ->
                if (n.packageName == pkg) n.copy(learned = state.openNote?.takeIf { it.packageName == pkg }?.let { change(it.learned).size } ?: rows.size) else n
            },
        )
    }
}
