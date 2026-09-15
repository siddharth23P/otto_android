package dev.otto.phone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.otto.phone.OttoApp
import dev.otto.phone.state.LessonTab
import dev.otto.phone.state.LessonsAction
import dev.otto.phone.state.LessonsReducer
import dev.otto.phone.state.LessonsState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What Otto learned: lessons, phone lessons, and notes on how apps work. */
class LessonsViewModel(app: Application) : AndroidViewModel(app) {
    private val connection = (app as OttoApp).connection
    private val _state = MutableStateFlow(LessonsState())
    val state: StateFlow<LessonsState> = _state
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts

    private fun dispatch(action: LessonsAction) {
        _state.update { LessonsReducer.reduce(it, action) }
        _state.value.error?.let { _toasts.tryEmit(it); _state.update { s -> LessonsReducer.reduce(s, LessonsAction.ErrorShown) } }
    }

    fun choose(tab: LessonTab) {
        dispatch(LessonsAction.TabChosen(tab))
        refresh()
    }

    fun refresh() {
        val tab = _state.value.tab
        val kind = tab.kind
        if (kind != null) load(kind) else loadNotes()
    }

    fun load(kind: String) = viewModelScope.launch {
        val t = connection.current ?: return@launch
        dispatch(LessonsAction.Loading(kind))
        dispatch(LessonsAction.Loaded(kind, t.lessons(kind)))
    }

    fun loadNotes() = viewModelScope.launch {
        val t = connection.current ?: return@launch
        dispatch(LessonsAction.NotesLoaded(t.notes()))
    }

    fun openNote(packageName: String) = viewModelScope.launch {
        val t = connection.current ?: return@launch
        if (_state.value.openNote?.packageName != packageName) dispatch(LessonsAction.NoteClosed)
        dispatch(LessonsAction.NoteOpened(t.note(packageName)))
    }

    fun closeNote() = dispatch(LessonsAction.NoteClosed)

    /** Tap twice: the first arms, the second (within the window) deletes. */
    fun tapDelete(kind: String, lessonId: String) {
        val key = LessonsState.deleteKey(kind, lessonId)
        val now = System.currentTimeMillis()
        if (!_state.value.armedFor(key, now)) { dispatch(LessonsAction.Arm(key, now)); return }
        viewModelScope.launch {
            val t = connection.current ?: return@launch
            val pkg = kind.removePrefix("app_note:").takeIf { kind.startsWith("app_note:") }
            val reply = if (pkg != null) t.deleteNote(pkg, lessonId) else t.deleteLesson(kind, lessonId)
            dispatch(LessonsAction.Deleted(kind, lessonId, reply))
        }
    }

    fun tapClear(kind: String) {
        val key = LessonsState.clearKey(kind)
        val now = System.currentTimeMillis()
        if (!_state.value.armedFor(key, now)) { dispatch(LessonsAction.Arm(key, now)); return }
        viewModelScope.launch {
            val t = connection.current ?: return@launch
            dispatch(LessonsAction.Cleared(kind, t.clearLessons(kind)))
        }
    }
}
