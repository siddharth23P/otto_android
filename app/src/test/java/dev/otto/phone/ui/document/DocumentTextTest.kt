package dev.otto.phone.ui.document

import dev.otto.phone.protocol.DocumentInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentTextTest {
    private val doc = DocumentInfo(
        path = "/home/a/.otto/workspaces/abcd/otto_research/phone-use/document.md",
        markdown = "\n# How Otto decides: phone or not?\n\nIt asks once per turn.",
        files = listOf("document.md", "document.docx"),
    )

    @Test fun namesAndTitles() {
        assertEquals("document.md", DocumentText.fileName(doc))
        assertEquals("How Otto decides: phone or not?", DocumentText.title(doc))
        assertEquals("How Otto decides phone or not.md", DocumentText.saveName(doc))
        assertEquals("document.md", DocumentText.title(doc.copy(markdown = "no heading here")))
        assertEquals("document.md", DocumentText.fileName(DocumentInfo(path = "")))
        assertEquals("document.md", DocumentText.saveName(DocumentInfo(markdown = "# ???")))
    }

    @Test fun theRowSaysHowLongItIs() {
        assertEquals("document.md · 11 words", DocumentText.rowDetail(doc))
        assertEquals("document.md · 1.5k words · shortened", DocumentText.rowDetail(doc.copy(markdown = "word ".repeat(1500), truncated = true)))
        assertEquals("document.md", DocumentText.rowDetail(doc.copy(markdown = "")))
    }
}
