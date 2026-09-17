package dev.otto.phone

import java.io.ByteArrayOutputStream

/** A small valid PDF with one line of Helvetica text per page (as tests/test_attachments.py builds). */
object Pdf {
    fun bytes(pages: List<String>): ByteArray {
        val objects = mutableListOf<ByteArray>()
        val kids = pages.indices.joinToString(" ") { "${3 + 2 * it} 0 R" }
        objects += "<< /Type /Catalog /Pages 2 0 R >>".toByteArray()
        objects += "<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>".toByteArray()
        val font = 3 + 2 * pages.size
        pages.forEachIndexed { i, text ->
            val content = "BT /F1 12 Tf 72 720 Td ($text) Tj ET".toByteArray()
            objects += ("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] " +
                "/Resources << /Font << /F1 $font 0 R >> >> /Contents ${4 + 2 * i} 0 R >>").toByteArray()
            objects += "<< /Length ${content.size} >>\nstream\n".toByteArray() + content + "\nendstream".toByteArray()
        }
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".toByteArray()
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        out.write("%PDF-1.4\n".toByteArray())
        objects.forEachIndexed { i, body ->
            offsets += out.size()
            out.write("${i + 1} 0 obj\n".toByteArray()); out.write(body); out.write("\nendobj\n".toByteArray())
        }
        val xref = out.size()
        out.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray())
        offsets.forEach { out.write(String.format("%010d 00000 n \n", it).toByteArray()) }
        out.write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray())
        return out.toByteArray()
    }
}
