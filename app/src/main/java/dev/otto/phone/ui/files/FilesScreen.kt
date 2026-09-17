package dev.otto.phone.ui.files

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otto.phone.state.FileKind
import dev.otto.phone.state.FilesText
import dev.otto.phone.state.Load
import dev.otto.phone.state.SessionFile
import dev.otto.phone.ui.Models
import dev.otto.phone.ui.components.AsyncState
import dev.otto.phone.ui.components.ButtonKind
import dev.otto.phone.ui.components.Hairline
import dev.otto.phone.ui.components.OttoButton
import dev.otto.phone.ui.components.OttoIcons
import dev.otto.phone.ui.components.OttoToastHost
import dev.otto.phone.ui.components.Panel
import dev.otto.phone.ui.components.ScreenBar
import dev.otto.phone.ui.theme.OttoTheme

/** The conversation's files: each attached file, where its original is, what otto kept of it, and
 *  otto's own research documents. Open, view the text, or remove. */
@Composable
fun FilesScreen(m: Models) {
    val c = OttoTheme.colors
    val chat by m.chat.state.collectAsStateWithLifecycle()
    val app by m.app.state.collectAsStateWithLifecycle()
    val list by m.files.list.collectAsStateWithLifecycle()
    val viewing by m.files.text.collectAsStateWithLifecycle()
    val sid = chat.sessionId
    val toasts = remember { SnackbarHostState() }
    LaunchedEffect(sid) { m.files.load(sid) }
    LaunchedEffect(Unit) { m.files.toasts.collect { toasts.currentSnackbarData?.dismiss(); toasts.showSnackbar(it) } }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize()) {
            ScreenBar(FilesText.TITLE, onBack = { m.files.hideText(); m.app.back() })
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                when {
                    !m.files.available || app.transportName != "embedded" ->
                        Text(FilesText.NOT_HERE, style = OttoTheme.type.ui.copy(fontSize = 14.sp, color = c.dim))
                    sid.isBlank() -> Text(FilesText.NO_SESSION, style = OttoTheme.type.ui.copy(fontSize = 14.sp, color = c.dim))
                    else -> AsyncState(
                        list, onRetry = { m.files.load(sid) },
                        isEmpty = { it.files.isEmpty() && it.documents.isEmpty() },
                        emptyTitle = FilesText.EMPTY_TITLE, emptyBody = FilesText.EMPTY_BODY,
                    ) { listing ->
                        Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                            if (listing.files.isNotEmpty()) Panel("attached") {
                                listing.files.asReversed().forEachIndexed { i, file ->
                                    if (i > 0) Hairline(color = c.lineSoft)
                                    FileRow(m, sid, file, viewing?.takeIf { it.first == file.id }?.second)
                                }
                            }
                            if (listing.documents.isNotEmpty()) Panel("made by otto") {
                                listing.documents.asReversed().forEachIndexed { i, doc ->
                                    if (i > 0) Hairline(color = c.lineSoft)
                                    Column(Modifier.padding(vertical = 9.dp).testTag("document_row"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Column(Modifier.semantics(mergeDescendants = true) { }) {
                                            Text(doc.name, style = OttoTheme.type.ui.copy(fontSize = 15.sp))
                                            Text(FilesText.document(doc), style = OttoTheme.type.meta.copy(color = c.faint))
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OttoButton("Open", { m.files.openDocument(doc) }, Modifier.testTag("document_open"), kind = ButtonKind.OUTLINED)
                                            OttoButton("Share", { m.files.openDocument(doc, share = true) }, Modifier.testTag("document_share"), kind = ButtonKind.OUTLINED)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        OttoToastHost(toasts, Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp))
    }
}

@Composable
private fun FileRow(m: Models, sid: String, file: SessionFile, text: Load<String>?) {
    val c = OttoTheme.colors
    var armed by rememberSaveable(file.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp).testTag("file_row"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.semantics(mergeDescendants = true) { }) {
            Icon(if (file.kind == FileKind.IMAGE.wire) OttoIcons.Image else OttoIcons.FileText, contentDescription = null,
                tint = c.dim, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = OttoTheme.type.ui.copy(fontSize = 15.sp), maxLines = 2)
                Text(FilesText.meta(file), style = OttoTheme.type.meta.copy(color = c.faint))
                Text(FilesText.source(file), style = OttoTheme.type.meta.copy(color = if (file.readable) c.dim else c.bad))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (file.uri.isNotBlank() || file.copyPath.isNotBlank()) {
                OttoButton("Open", { m.files.open(file) }, Modifier.testTag("file_open"), kind = ButtonKind.OUTLINED)
            }
            if (file.readable) OttoButton(if (text != null) "Hide text" else "Text",
                { if (text != null) m.files.hideText() else m.files.showText(sid, file) },
                Modifier.testTag("file_text"), kind = ButtonKind.OUTLINED)
            OttoButton(if (armed) "Tap again to remove" else "Remove",
                { if (armed) { armed = false; m.files.remove(sid, file) } else armed = true },
                Modifier.testTag("file_remove"), kind = ButtonKind.DANGER, armed = armed)
        }
        when (text) {
            null -> Unit
            Load.Idle, Load.Loading -> Text("loading…", style = OttoTheme.type.meta)
            is Load.Ready -> SelectionContainer {
                Text(text.value.ifBlank { "(no text)" },
                    style = OttoTheme.type.trace.copy(color = c.ink, lineHeight = 18.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                        .testTag("file_text_body"))
            }
            is Load.Failed -> Text(text.message, style = OttoTheme.type.meta.copy(color = c.bad))
            Load.Unsupported -> Text(FilesText.NOT_HERE, style = OttoTheme.type.meta)
        }
    }
}
