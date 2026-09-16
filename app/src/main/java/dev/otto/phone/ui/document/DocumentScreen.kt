package dev.otto.phone.ui.document

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.otto.phone.protocol.DocumentInfo
import dev.otto.phone.ui.chat.AnswerMarkdown
import dev.otto.phone.ui.chat.shareText
import dev.otto.phone.ui.components.IconAction
import dev.otto.phone.ui.components.OttoIcons
import dev.otto.phone.ui.components.OttoToastHost
import dev.otto.phone.ui.components.ScreenBar
import dev.otto.phone.ui.theme.OttoShapes
import dev.otto.phone.ui.theme.OttoTheme
import dev.otto.phone.ui.theme.pressScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A research document a turn wrote, as Markdown, with copy, share and save. */
@Composable
fun DocumentScreen(doc: DocumentInfo, onBack: () -> Unit) {
    val c = OttoTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val toasts = remember { SnackbarHostState() }
    fun toast(message: String) = scope.launch { toasts.currentSnackbarData?.dismiss(); toasts.showSnackbar(message) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(doc.markdown.toByteArray()) } != null }.getOrDefault(false)
            }
            toast(if (ok) "saved" else "couldn't write that file")
        }
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize()) {
            ScreenBar(DocumentText.fileName(doc), onBack) {
                IconAction(OttoIcons.Copy, "copy the document", { clipboard.setText(AnnotatedString(doc.markdown)); toast("copied") }, Modifier.testTag("document_copy"))
                IconAction(OttoIcons.Share, "share the document", { shareText(context, doc.markdown) }, Modifier.testTag("document_share"))
                IconAction(OttoIcons.Download, "save the document", { save.launch(DocumentText.saveName(doc)) }, Modifier.testTag("document_save"))
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                if (doc.truncated) Text(DocumentText.TRUNCATED, style = OttoTheme.type.meta.copy(color = c.warn))
                AnswerMarkdown(doc.markdown, Modifier.widthIn(max = 720.dp))
                if (doc.files.size > 1) Text(
                    "also written on the computer: " + doc.files.filter { !it.endsWith(".md") }.joinToString(", "),
                    style = OttoTheme.type.meta,
                )
            }
        }
        OttoToastHost(toasts, Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp))
    }
}

/** The row under an answer that wrote a document: a flat bg2 card, file icon, name, "open ↗". */
@Composable
fun DocumentRow(doc: DocumentInfo, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = OttoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).pressScale(interaction)
            .background(c.bg2, OttoShapes.r2).border(1.dp, c.line, OttoShapes.r2)
            .clickable(interaction, ripple(), role = Role.Button, onClickLabel = "open the document", onClick = onOpen)
            .semantics(mergeDescendants = true) { }
            .testTag("document_row")
            .padding(horizontal = 15.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(OttoIcons.FileText, contentDescription = null, tint = c.dim, modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(DocumentText.title(doc), style = OttoTheme.type.ui.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 2)
            Text(DocumentText.rowDetail(doc), style = OttoTheme.type.meta, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("open", style = OttoTheme.type.meta)
            Icon(OttoIcons.ArrowUpRight, contentDescription = null, tint = c.faint, modifier = Modifier.size(12.dp))
        }
    }
}
