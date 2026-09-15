package dev.otto.phone.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otto.phone.protocol.LessonRow
import dev.otto.phone.state.Board
import dev.otto.phone.state.LessonTab
import dev.otto.phone.state.LessonsState
import dev.otto.phone.state.Load
import dev.otto.phone.state.Route
import dev.otto.phone.ui.Models
import dev.otto.phone.ui.components.AsyncState
import dev.otto.phone.ui.components.ButtonKind
import dev.otto.phone.ui.components.Hairline
import dev.otto.phone.ui.components.OttoButton
import dev.otto.phone.ui.components.OttoIcons
import dev.otto.phone.ui.components.OttoToastHost
import dev.otto.phone.ui.components.Panel
import dev.otto.phone.ui.components.ScreenBar
import dev.otto.phone.ui.components.Segmented
import dev.otto.phone.ui.theme.OttoTheme
import kotlinx.coroutines.delay

/** The clock, re-read while a tap-twice is armed so "sure?" lapses on its own. */
@Composable
private fun armedClock(state: LessonsState): Long {
    val now by produceState(System.currentTimeMillis(), state.armed) {
        value = System.currentTimeMillis()
        while (state.armed != null) { delay(500); value = System.currentTimeMillis() }
    }
    return now
}

@Composable
private fun ToastsFrom(m: Models, host: SnackbarHostState) {
    LaunchedEffect(Unit) { m.lessons.toasts.collect { host.currentSnackbarData?.dismiss(); host.showSnackbar(it) } }
}

/** What Otto learned: lessons, phone lessons, and notes on how apps work. */
@Composable
fun MemoryScreen(m: Models) {
    val state by m.lessons.state.collectAsStateWithLifecycle()
    val c = OttoTheme.colors
    val toasts = remember { SnackbarHostState() }
    ToastsFrom(m, toasts)
    val now = armedClock(state)
    LaunchedEffect(state.tab) { m.lessons.refresh() }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize()) {
            ScreenBar("Memory", onBack = { m.app.back() })
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                Segmented(
                    options = LessonTab.entries.map { it to LessonsText.tab(it) },
                    selected = state.tab, onSelect = { m.lessons.choose(it) },
                    modifier = Modifier.testTag("memory_tabs"),
                )
                val kind = state.tab.kind
                if (kind != null) LessonList(m, state, kind, now) else NoteList(m, state)
            }
        }
        OttoToastHost(toasts, Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp))
    }
}

@Composable
private fun LessonList(m: Models, state: LessonsState, kind: String, now: Long) {
    val load: Load<List<LessonRow>> = when {
        state.unsupported -> Load.Unsupported
        state.loaded(kind) -> Load.Ready(state.lessons.getValue(kind))
        else -> Load.Loading
    }
    AsyncState(load, onRetry = { m.lessons.load(kind) }, isEmpty = { it.isEmpty() }, emptyTitle = LessonsText.NOTHING_LEARNED, emptyBody = LessonsText.NOTHING_LEARNED_BODY) { rows ->
        Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Panel(LessonsText.tab(state.tab)) {
                rows.forEachIndexed { i, row ->
                    if (i > 0) Hairline(color = OttoTheme.colors.lineSoft)
                    LessonItem(row, armed = state.armedFor(LessonsState.deleteKey(kind, row.id), now), onDelete = { m.lessons.tapDelete(kind, row.id) })
                }
            }
            val armed = state.armedFor(LessonsState.clearKey(kind), now)
            OttoButton(
                LessonsText.clearLabel(rows.size, armed), { m.lessons.tapClear(kind) },
                Modifier.testTag("memory_clear").semantics { if (armed) stateDescription = "tap again to clear all" },
                kind = ButtonKind.DANGER, armed = armed,
            )
        }
    }
}

@Composable
fun LessonItem(row: LessonRow, armed: Boolean, onDelete: () -> Unit) {
    val c = OttoTheme.colors
    val t = OttoTheme.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).padding(vertical = 9.dp).clearAndSetSemantics { contentDescription = LessonsText.describe(row) },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(LessonsText.cue(row), style = t.ui.copy(fontSize = 14.5.sp, lineHeight = 21.sp))
            LessonsText.action(row)?.let { Text(it, style = t.user.copy(fontSize = 13.sp, lineHeight = 19.sp)) }
            row.outcome?.ifBlank { null }?.let { outcome ->
                val color = when (LessonsText.outcomeLevel(outcome)) { Board.Level.OK -> c.ok; Board.Level.BAD -> c.bad; else -> c.faint }
                Text(outcome, style = t.meta.copy(color = color))
            }
        }
        OttoButton(
            if (armed) "sure?" else "✕", onDelete,
            Modifier.semantics { contentDescription = if (armed) "tap again to delete" else "delete this lesson" },
            kind = ButtonKind.DANGER, armed = armed,
        )
    }
}

@Composable
private fun NoteList(m: Models, state: LessonsState) {
    val c = OttoTheme.colors
    val load: Load<List<dev.otto.phone.protocol.NoteSummary>> = when {
        state.unsupported -> Load.Unsupported
        state.notesLoaded -> Load.Ready(state.notes)
        else -> Load.Loading
    }
    AsyncState(load, onRetry = { m.lessons.loadNotes() }, isEmpty = { it.isEmpty() }, emptyTitle = LessonsText.NO_NOTES, emptyBody = LessonsText.NO_NOTES_BODY) { notes ->
        Panel("app notes") {
            notes.forEachIndexed { i, note ->
                if (i > 0) Hairline(color = c.lineSoft)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClickLabel = "open the notes for ${note.packageName}") { m.app.push(Route.Note(note.packageName)) }
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(note.packageName, style = OttoTheme.type.user.copy(fontSize = 13.5.sp, color = c.ink, lineHeight = 19.sp))
                        Text(LessonsText.noteMeta(note), style = OttoTheme.type.meta)
                    }
                    Icon(OttoIcons.ChevronRight, contentDescription = null, tint = c.faint, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** One app's notes: the shipped note read-only, what Otto learned (deletable), and the lines the
 *  model is actually shown. */
@Composable
fun NoteScreen(m: Models, packageName: String) {
    val state by m.lessons.state.collectAsStateWithLifecycle()
    val c = OttoTheme.colors
    val t = OttoTheme.type
    val toasts = remember { SnackbarHostState() }
    ToastsFrom(m, toasts)
    val now = armedClock(state)
    LaunchedEffect(packageName) { m.lessons.openNote(packageName) }
    val kind = LessonsState.noteKind(packageName)
    val note = state.openNote?.takeIf { it.packageName == packageName }
    val load = when {
        state.unsupported -> Load.Unsupported
        note != null -> Load.Ready(note)
        else -> Load.Loading
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize()) {
            ScreenBar(packageName, onBack = { m.lessons.closeNote(); m.app.back() })
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                AsyncState(load, onRetry = { m.lessons.openNote(packageName) }) { detail ->
                    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        Panel("shipped with otto") {
                            val seeded = detail.seeded?.ifBlank { null }
                            Text(
                                seeded ?: "No shipped note for this app.",
                                style = if (seeded != null) t.user.copy(fontSize = 13.sp, lineHeight = 20.sp) else t.ui.copy(fontSize = 14.sp, color = c.dim),
                                modifier = Modifier.semantics { stateDescription = "read only" },
                            )
                        }
                        Panel("learned") {
                            val learned = state.lessons[kind] ?: detail.learned
                            if (learned.isEmpty()) Text(LessonsText.NOTHING_LEARNED, style = t.ui.copy(fontSize = 14.sp, color = c.dim))
                            learned.forEachIndexed { i, row ->
                                if (i > 0) Hairline(color = c.lineSoft)
                                LessonItem(row, armed = state.armedFor(LessonsState.deleteKey(kind, row.id), now), onDelete = { m.lessons.tapDelete(kind, row.id) })
                            }
                        }
                        Panel("what Otto sees") {
                            if (detail.shown.isEmpty()) Text("Nothing — Otto is shown no notes for this app.", style = t.ui.copy(fontSize = 14.sp, color = c.dim))
                            detail.shown.forEach { line -> Text(line, style = t.trace.copy(fontSize = 12.5.sp, lineHeight = 19.sp, color = c.dim), modifier = Modifier.padding(vertical = 2.dp)) }
                        }
                    }
                }
            }
        }
        OttoToastHost(toasts, Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp))
    }
}
