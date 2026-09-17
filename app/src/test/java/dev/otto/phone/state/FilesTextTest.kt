package dev.otto.phone.state

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class FilesTextTest {
    private val utc = ZoneId.of("UTC")

    @Test fun theListingParsesAsPythonWritesIt() {
        val raw = """{"ok":true,"files":[{"id":"f1","name":"My CV.pdf","kind":"pdf","size":1572864,"mime":"application/pdf",
            "uri":"content://x/42","copy":"","copy_path":"","text":"f1-My_CV.pdf.txt","chars":4698,"truncated":false,
            "pages":2,"note":"","added_at":1789632000000,"readable":true}],
            "documents":[{"name":"tides","path":"otto_research/tides/document.md","size":2048,"modified_at":1}]}"""
        val list = Json { ignoreUnknownKeys = true }.decodeFromString(SessionFileList.serializer(), raw)
        val file = list.files.single()
        assertEquals("PDF · 2 pages · 1.5 MB · Sep 17, 08:00", FilesText.meta(file, utc))
        assertEquals("linked to the original · otto has its text", FilesText.source(file))
        assertEquals("research document · 2 KB", FilesText.document(list.documents.single()))
    }

    @Test fun whereTheOriginalIsAndWhatOttoHas() {
        val shared = SessionFile("f2", "shot.jpg", kind = "image", copy = "originals/f2-shot.jpg", note = "described by anthropic")
        assertEquals("copy kept · otto has its description (described by anthropic)", FilesText.source(shared))
        val cut = SessionFile("f3", "big.txt", truncated = true, chars = 40000)
        assertEquals("original not kept · otto has its text, cut to 40000 characters", FilesText.source(cut))
        assertEquals("original not kept · its text is gone", FilesText.source(cut.copy(readable = false)))
    }

    @Test fun aSavedFileIsNamedInItsBlock() {
        val a = Attachment("f1", "r.pdf", FileKind.PDF, 10, Attachment.State.Ready("body"))
        val message = Attachments.compose("go", listOf(a), mapOf("f1" to "attachments/f1-r.pdf.txt"))
        assertEquals(true, message.contains("Kept in this session's files as attachments/f1-r.pdf.txt: read it with read_file"))
        assertEquals(listOf(FileChip("r.pdf", FileKind.PDF)) to "go", Attachments.split(message))
    }
}
