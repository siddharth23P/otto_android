package dev.otto.phone.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.otto.phone.attach.SessionFiles
import dev.otto.phone.protocol.Reply
import dev.otto.phone.state.Load
import dev.otto.phone.state.SessionDocument
import dev.otto.phone.state.SessionFile
import dev.otto.phone.state.SessionFileList
import dev.otto.phone.state.problem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** The Files screen: what the current conversation was given, and what otto kept of it. */
class FilesViewModel(app: Application) : AndroidViewModel(app) {
    private val files = SessionFiles(app)
    private val _list = MutableStateFlow<Load<SessionFileList>>(Load.Idle)
    val list: StateFlow<Load<SessionFileList>> = _list
    /** The text otto has for the file being viewed, by id. */
    private val _text = MutableStateFlow<Pair<String, Load<String>>?>(null)
    val text: StateFlow<Pair<String, Load<String>>?> = _text
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts

    val available: Boolean get() = files.available

    fun load(sessionId: String) = viewModelScope.launch {
        if (sessionId.isBlank()) { _list.value = Load.Ready(SessionFileList()); return@launch }
        _list.value = Load.Loading
        _list.value = Load.of(files.list(sessionId))
    }

    fun showText(sessionId: String, file: SessionFile) = viewModelScope.launch {
        _text.value = file.id to Load.Loading
        _text.value = file.id to Load.of(files.text(sessionId, file.id))
    }

    fun hideText() { _text.value = null }

    fun remove(sessionId: String, file: SessionFile) = viewModelScope.launch {
        when (val reply = files.remove(sessionId, file)) {
            is Reply.Ok -> { _toasts.tryEmit("removed ${file.name}"); load(sessionId) }
            else -> _toasts.tryEmit(reply.problem("couldn't remove ${file.name}") ?: "")
        }
    }

    /** A file otto made, opened in the app that reads its type, or offered to another app. */
    fun openDocument(doc: SessionDocument, share: Boolean = false) {
        val app = getApplication<Application>()
        if (doc.absPath.isBlank()) { _toasts.tryEmit("${doc.name} is not on this phone"); return }
        val uri = runCatching { FileProvider.getUriForFile(app, "${app.packageName}.files", File(doc.absPath)) }
            .getOrElse { _toasts.tryEmit("${doc.name} cannot be opened from here"); return }
        val mime = doc.mime.ifBlank { "*/*" }
        val intent = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(Intent.createChooser(intent, doc.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            _toasts.tryEmit("no app here opens ${doc.name}")
        }
    }

    /** The original in the app that opens its type: through the kept reference, or the kept copy. */
    fun open(file: SessionFile) {
        val app = getApplication<Application>()
        val uri: Uri = when {
            file.uri.isNotBlank() -> Uri.parse(file.uri)
            file.copyPath.isNotBlank() -> FileProvider.getUriForFile(app, "${app.packageName}.files", File(file.copyPath))
            else -> { _toasts.tryEmit("the original of ${file.name} was not kept"); return }
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            _toasts.tryEmit("no app here opens ${file.name}")
        } catch (e: SecurityException) {
            _toasts.tryEmit("${file.name} can no longer be opened: it was moved or deleted")
        }
    }
}
