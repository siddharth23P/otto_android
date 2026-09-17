package dev.otto.phone.state

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One attached file, as otto_app/session_files.py records it. */
@Serializable
data class SessionFile(
    val id: String,
    val name: String,
    val kind: String = "text",
    val size: Long = 0,
    val mime: String = "",
    /** A lasting reference to the original (a picked file), or "". */
    val uri: String = "",
    /** The copy of the original, relative to the session's files (a shared file), or "". */
    val copy: String = "",
    @SerialName("copy_path") val copyPath: String = "",
    /** Where its text is, relative to the session's files. */
    val text: String = "",
    val chars: Int = 0,
    val truncated: Boolean = false,
    val pages: Int? = null,
    val note: String = "",
    @SerialName("added_at") val addedAt: Long = 0,
    /** Whether the text otto reads is still there. */
    val readable: Boolean = true,
)

/** A research document a turn in the session wrote. */
@Serializable
data class SessionDocument(
    val name: String,
    val path: String,
    @SerialName("abs_path") val absPath: String = "",
    val mime: String = "",
    val size: Long = 0,
    @SerialName("modified_at") val modifiedAt: Long = 0,
)

@Serializable
data class SessionFileList(
    val files: List<SessionFile> = emptyList(),
    val documents: List<SessionDocument> = emptyList(),
)

/** The Files screen's words, pure so the tests pin them. */
object FilesText {
    const val TITLE = "Files"
    const val EMPTY_TITLE = "No files in this conversation"
    const val EMPTY_BODY = "Attach a PDF, Word document, text, JSON or image with the paperclip, or share one into Otto. " +
        "Otto keeps what it read from each, so it can read it again later in this conversation."
    const val NOT_HERE = "Files are kept when Otto runs on this phone. With otto serve, they live on that computer."
    const val NO_SESSION = "Start a conversation first."

    private val WHEN = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US)

    /** "PDF · 3 pages · 1.2 MB · Sep 17, 13:34" */
    fun meta(file: SessionFile, zone: ZoneId = ZoneId.systemDefault()): String {
        val kind = FileKind.of(file.kind)
        val parts = mutableListOf(kind.label)
        file.pages?.let { parts += "$it page${if (it == 1) "" else "s"}" }
        parts += Attachments.size(file.size)
        if (file.addedAt > 0) parts += WHEN.format(Instant.ofEpochMilli(file.addedAt).atZone(zone))
        return parts.joinToString(" · ")
    }

    /** Where the original is, and what otto can read. */
    fun source(file: SessionFile): String {
        val original = when {
            file.uri.isNotBlank() -> "linked to the original"
            file.copy.isNotBlank() -> "copy kept"
            else -> "original not kept"
        }
        val read = when {
            !file.readable -> "its text is gone"
            file.kind == FileKind.IMAGE.wire -> "otto has its description" + if (file.note.isNotBlank()) " (${file.note})" else ""
            file.truncated -> "otto has its text, cut to ${file.chars} characters"
            else -> "otto has its text"
        }
        return "$original · $read"
    }

    fun document(doc: SessionDocument): String {
        val kind = when (doc.name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "PDF"; "docx" -> "Word"; "xlsx" -> "Excel"; "pptx" -> "PowerPoint"; "md" -> "Markdown"
            else -> "file"
        }
        val what = if (doc.path.startsWith("otto_research/")) "research document" else "made by otto"
        return "$kind · $what · ${Attachments.size(doc.size)}"
    }
}
