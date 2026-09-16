package dev.otto.phone.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otto.phone.protocol.SessionRow
import dev.otto.phone.protocol.SessionUsage
import dev.otto.phone.state.Board
import dev.otto.phone.state.ChatBlock
import dev.otto.phone.state.ChatState
import dev.otto.phone.ui.Models
import dev.otto.phone.ui.components.ButtonKind
import dev.otto.phone.ui.components.Eyebrow
import dev.otto.phone.ui.components.Hairline
import dev.otto.phone.ui.components.IconAction
import dev.otto.phone.ui.components.OttoButton
import dev.otto.phone.ui.components.OttoIcons
import dev.otto.phone.ui.components.Wordmark
import dev.otto.phone.ui.theme.OttoShapes
import dev.otto.phone.ui.theme.OttoTheme
import dev.otto.phone.ui.theme.skeletonPulse
import kotlinx.coroutines.delay

/** The sessions drawer, styled as a DataNodes panel: new and import, the open session's spend, and
 *  every session with rename, export and a tap-twice delete. */
@Composable
fun SessionsDrawer(m: Models, onClose: () -> Unit) {
    val sessions by m.sessions.state.collectAsStateWithLifecycle()
    val chat by m.chat.state.collectAsStateWithLifecycle()
    val pending by m.sessions.pendingExport.collectAsStateWithLifecycle()
    val c = OttoTheme.colors
    var renaming by remember { mutableStateOf<SessionRow?>(null) }

    // Re-read the clock while a delete is armed, so "sure?" lapses back on its own.
    val now by produceState(System.currentTimeMillis(), sessions.armed) {
        value = System.currentTimeMillis()
        while (sessions.armed != null) { delay(500); value = System.currentTimeMillis() }
    }

    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> m.sessions.writeExport(uri) }
    LaunchedEffect(pending) { pending?.let { exportTo.launch(it.filename.ifBlank { "otto-session.json" }) } }
    val importFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { m.sessions.import(uri); onClose() }
    }

    LazyColumn(
        Modifier.fillMaxHeight().testTag("sessions_drawer"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Wordmark()
                Box(Modifier.weight(1f))
                IconAction(OttoIcons.Close, "close sessions", onClose)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OttoButton("new session", { m.chat.newSession(); onClose() }, Modifier.weight(1f).testTag("drawer_new"), icon = OttoIcons.Plus)
                OttoButton(
                    "import", { importFrom.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    Modifier.testTag("drawer_import"), kind = ButtonKind.OUTLINED, icon = OttoIcons.Upload,
                )
            }
        }
        item { Eyebrow("current session", Modifier.padding(top = 13.dp)) }
        item { CurrentSession(chat, sessions.usage?.takeIf { it.sessionId.isBlank() || it.sessionId == chat.sessionId }) }
        item { Eyebrow("sessions", Modifier.padding(top = 13.dp)) }
        when {
            sessions.loading && !sessions.loaded -> items(3) { Box(Modifier.fillMaxWidth().height(44.dp).skeletonPulse(c, OttoShapes.r1)) }
            sessions.loaded && sessions.rows.isEmpty() -> item { Text(DrawerText.NO_SESSIONS, style = OttoTheme.type.ui.copy(fontSize = 14.sp, color = c.dim)) }
            else -> items(sessions.rows, key = { it.id }) { row ->
                SessionItem(
                    row = row,
                    current = row.id == chat.sessionId,
                    armed = sessions.deleteArmed(row.id, now),
                    onOpen = { if (row.id != chat.sessionId) m.chat.openSession(row.id); onClose() },
                    onRename = { renaming = row },
                    onExport = { m.sessions.export(row.id) },
                    onDelete = { m.sessions.tapDelete(row.id) { gone -> if (gone == chat.sessionId) m.chat.openSession(null) } },
                )
            }
        }
    }

    renaming?.let { row ->
        RenameDialog(
            initial = row.title,
            onDismiss = { renaming = null },
            onRename = { title ->
                renaming = null
                m.sessions.rename(row.id, title) { newTitle -> m.chat.renamed(row.id, newTitle) }
            },
        )
    }
}

@Composable
private fun CurrentSession(chat: ChatState, usage: SessionUsage?) {
    val c = OttoTheme.colors
    val t = OttoTheme.type
    val lastTurn = chat.turn ?: chat.blocks.asReversed().firstNotNullOfOrNull { (it as? ChatBlock.Otto)?.turn ?: (it as? ChatBlock.System)?.turn }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(chat.title.ifBlank { "(untitled)" }, style = t.ui.copy(fontSize = 15.5.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(DrawerText.currentMeta(if (usage != null && usage.turns != 0) usage.turns else chat.turns, lastTurn?.mode, lastTurn?.model), style = t.meta)
        val snapshot = usage?.usage
        if (snapshot == null || snapshot.calls == 0) {
            Text(DrawerText.NO_USAGE, style = t.meta.copy(color = c.dim), modifier = Modifier.padding(top = 3.dp))
            return@Column
        }
        Column(Modifier.padding(top = 5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            snapshot.models.forEach { row ->
                Column(Modifier.semantics(mergeDescendants = true) { }) {
                    Text(ChatText.model(row.model), style = t.user.copy(fontSize = 12.5.sp, color = c.ink, lineHeight = 18.sp), maxLines = 1)
                    Text(DrawerText.usageLine(row), style = t.meta)
                }
            }
            Hairline(Modifier.padding(vertical = 3.dp), color = c.lineSoft)
            Row(Modifier.semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
                Text("total", style = t.meta.copy(color = c.dim), modifier = Modifier.weight(1f))
                Text(DrawerText.totalsLine(snapshot), style = t.meta.copy(color = c.ink, fontFeatureSettings = "tnum"))
            }
            if (usage.turnTokens.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("per turn", style = t.meta.copy(color = c.dim), modifier = Modifier.weight(1f))
                Text(
                    Board.sparkline(usage.turnTokens), style = t.meta.copy(fontSize = 14.sp, color = c.acc3),
                    modifier = Modifier.clearAndSetSemantics { contentDescription = DrawerText.sparkDescription(usage.turnTokens) },
                )
            }
            if (snapshot.cost != null) Text(DrawerText.ESTIMATE, style = t.meta.copy(fontSize = 11.sp))
        }
    }
}

@Composable
private fun SessionItem(
    row: SessionRow, current: Boolean, armed: Boolean,
    onOpen: () -> Unit, onRename: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit,
) {
    val c = OttoTheme.colors
    val t = OttoTheme.type
    var menu by remember { mutableStateOf(false) }
    val title = row.title.ifBlank { "(untitled)" }
    Row(
        Modifier.fillMaxWidth().testTag("session_row").clip(OttoShapes.r1).background(if (current) c.accSoft else c.bg2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = if (current) "close the drawer" else "resume this session", onClick = onOpen)
                .semantics { if (current) stateDescription = "open now" }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = t.ui.copy(fontSize = 14.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(DrawerText.sessionMeta(row, current), style = t.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            IconAction(OttoIcons.More, "options for $title", { menu = true })
            DropdownMenu(
                expanded = menu, onDismissRequest = { menu = false },
                containerColor = c.card, tonalElevation = 0.dp, shadowElevation = 0.dp, shape = OttoShapes.r2, border = BorderStroke(1.dp, c.line),
            ) {
                MenuItem("rename", OttoIcons.Pencil, Modifier.testTag("session_rename")) { menu = false; onRename() }
                MenuItem("export", OttoIcons.Download, Modifier.testTag("session_export")) { menu = false; onExport() }
                // Tap twice: the first tap arms and keeps the menu open, the second deletes.
                DropdownMenuItem(
                    text = { Text(if (armed) "sure? delete" else "delete", style = t.ui.copy(fontSize = 14.5.sp, color = c.bad)) },
                    leadingIcon = { Icon(OttoIcons.Trash, contentDescription = null, tint = c.bad, modifier = Modifier.height(16.dp)) },
                    onClick = { if (armed) menu = false; onDelete() },
                    modifier = Modifier.testTag("session_delete").semantics { if (armed) stateDescription = "tap again to delete" },
                )
            }
        }
    }
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    val c = OttoTheme.colors
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card, tonalElevation = 0.dp, shape = OttoShapes.r3,
        title = { Text("Rename session", style = OttoTheme.type.answer.copy(fontSize = 19.sp)) },
        text = {
            BasicTextField(
                value = value, onValueChange = { value = it }, singleLine = true,
                textStyle = OttoTheme.type.composer, cursorBrush = SolidColor(c.ink),
                modifier = Modifier.fillMaxWidth().testTag("rename_field").semantics { contentDescription = "session title" }
                    .border(1.dp, c.line, OttoShapes.r1).padding(horizontal = 12.dp, vertical = 11.dp),
            )
        },
        confirmButton = { OttoButton("rename", { onRename(value) }, enabled = value.isNotBlank()) },
        dismissButton = { OttoButton("cancel", onDismiss, kind = ButtonKind.OUTLINED) },
    )
}
