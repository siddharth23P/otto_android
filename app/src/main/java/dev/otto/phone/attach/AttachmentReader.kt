package dev.otto.phone.attach

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import dev.otto.phone.BuildConfig
import dev.otto.phone.log.OttoLog
import dev.otto.phone.protocol.Protocol
import dev.otto.phone.protocol.Reply
import dev.otto.phone.state.Attachment
import dev.otto.phone.state.Attachments
import dev.otto.phone.state.FileKind
import dev.otto.phone.transport.PythonRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

/**
 * Turns a picked or shared file into text for a message. The file is copied into the app's cache
 * first -- a content URI's grant does not outlive the screen that received it -- and read there by
 * otto_app/attachments.py (PDF via pypdf, Word by its XML, images by otto's vision model). A build
 * without the embedded runtime reads text and JSON itself and says what it cannot. The copy is
 * deleted once read; nothing a person attaches stays on disk.
 */
class AttachmentReader(private val context: Context) {
    private val dir: File get() = File(context.cacheDir, "attachments").apply { mkdirs() }

    /** Name, size and kind, before anything is read; null when Otto cannot read the file. A file
     *  picked with the paperclip keeps a lasting read permission (`linked`): the session refers to the
     *  original instead of copying it. A shared one cannot keep it, and is copied when it is read. */
    fun peek(uri: Uri): Attachment? {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val mime = context.contentResolver.getType(uri).orEmpty()
        val kind = Attachments.kindOf(name, mime) ?: return null
        val linked = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        return Attachment(UUID.randomUUID().toString(), name, kind, size.coerceAtLeast(0),
            uri = uri.toString(), mime = mime, linked = linked)
    }

    /** Gives back the lasting permission to a file no session refers to any more. */
    fun release(uri: String) {
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** The original of a file that cannot be referenced, kept in the cache until the message is sent. */
    private fun keep(uri: Uri, a: Attachment): String {
        val kept = File(dir, "${a.id}.original")
        copyCapped(uri, kept, MAX_IMAGE_SOURCE_BYTES)
        return kept.absolutePath
    }

    fun discard(a: Attachment) {
        a.keptCopy?.let { File(it).delete() }
        if (a.linked) release(a.uri)
    }

    /** The file read to text, and, for one that cannot be referenced, where its original is kept. */
    suspend fun read(uri: Uri, a: Attachment): Pair<Attachment.State, String?> = withContext(Dispatchers.IO) {
        val state = readText(uri, a)
        val kept = if (!a.linked && state is Attachment.State.Ready) runCatching { keep(uri, a) }.getOrNull() else null
        state to kept
    }

    private suspend fun readText(uri: Uri, a: Attachment): Attachment.State = withContext(Dispatchers.IO) {
        val copy = File(dir, "${a.id}-${a.name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)}")
        try {
            val limit = if (a.kind == FileKind.IMAGE) MAX_IMAGE_SOURCE_BYTES else MAX_FILE_BYTES
            if (a.size > limit) return@withContext Attachment.State.Failed("${a.name} is ${Attachments.size(a.size)}; the limit is ${Attachments.size(limit)}")
            if (a.kind == FileKind.IMAGE) shrinkImage(uri, copy) else copyCapped(uri, copy, limit)
            val mime = if (a.kind == FileKind.IMAGE) "image/jpeg" else context.contentResolver.getType(uri).orEmpty()
            (if (BuildConfig.EMBEDDED_PYTHON) readWithPython(copy, a.name, mime) else readHere(copy, a)).also { state ->
                // The kind and the outcome only: never the name or the text.
                OttoLog.i(TAG, "read a ${a.kind.wire} (${Attachments.size(a.size)}): ${state.javaClass.simpleName}" +
                    ((state as? Attachment.State.Failed)?.let { " -- ${it.message.substringAfter(": ", it.message)}" } ?: ""))
            }
        } catch (e: TooLarge) {
            Attachment.State.Failed("${a.name} is over ${Attachments.size(e.limit)}")
        } catch (e: Exception) {
            OttoLog.w(TAG, "reading ${a.kind.wire} failed", e)
            Attachment.State.Failed("${a.name} could not be opened: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            copy.delete()
        }
    }

    private fun copyCapped(uri: Uri, to: File, limit: Long) {
        val input = context.contentResolver.openInputStream(uri) ?: error("no data")
        input.use { src ->
            to.outputStream().use { dst ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw TooLarge(limit)
                    dst.write(buf, 0, n)
                }
            }
        }
    }

    /** A photo is usually far larger than a vision model needs: at most 2048 px on the long edge, as
     *  JPEG, turned the way it was taken (ImageDecoder applies the EXIF orientation). */
    private fun shrinkImage(uri: Uri, to: File) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val long = maxOf(info.size.width, info.size.height)
            if (long > MAX_IMAGE_EDGE) {
                val scale = MAX_IMAGE_EDGE.toFloat() / long
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
        }
        to.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bitmap.recycle()
    }

    private fun readWithPython(file: File, name: String, mime: String): Attachment.State {
        val reply = PythonRuntime.get(context).getModule("otto_app.attachments").callAttr("read", file.absolutePath, name, mime).toString()
        val json = Protocol.parse(reply) ?: return Attachment.State.Failed("$name could not be read")
        (Protocol.errorOf(json) as? Reply.Err)?.let { return Attachment.State.Failed(it.message.ifBlank { "$name could not be read" }) }
        return Attachment.State.Ready(
            text = json["text"]?.jsonPrimitive?.content.orEmpty(),
            truncated = json["truncated"]?.jsonPrimitive?.booleanOrNull ?: false,
            note = json["note"]?.jsonPrimitive?.content.orEmpty(),
            pages = json["pages"]?.jsonPrimitive?.intOrNull,
        )
    }

    /** No Python in this build: text and JSON as they are, the rest refused with the reason. */
    private fun readHere(file: File, a: Attachment): Attachment.State {
        if (a.kind != FileKind.TEXT && a.kind != FileKind.JSON) {
            return Attachment.State.Failed("this build reads only text and JSON files; ${a.kind.label} files need Otto running on this phone")
        }
        val text = file.readText()
        val cut = text.length > MAX_FILE_CHARS
        return Attachment.State.Ready(if (cut) text.take(MAX_FILE_CHARS) else text, truncated = cut)
    }

    /** Deletes whatever a read left behind (a process killed mid-read). */
    fun clearCache() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    private class TooLarge(val limit: Long) : Exception()

    companion object {
        private const val TAG = "OttoAttach"
        /** Mirrors otto_app/attachments.py. */
        const val MAX_FILE_BYTES = 20L * 1024 * 1024
        const val MAX_FILE_CHARS = 40_000
        /** A photo straight from the camera may be large; it is shrunk before otto's 8 MB cap applies. */
        const val MAX_IMAGE_SOURCE_BYTES = 40L * 1024 * 1024
        const val MAX_IMAGE_EDGE = 2048
    }
}
