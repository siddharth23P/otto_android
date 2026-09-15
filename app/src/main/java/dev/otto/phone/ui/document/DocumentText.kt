package dev.otto.phone.ui.document

import dev.otto.phone.protocol.DocumentInfo

/** How a research document is named and titled, pure so the tests pin it. */
object DocumentText {
    const val TRUNCATED = "This document is longer than otto sends to the phone; what's here is the start of it."

    /** The file's own name: the last part of its path, or "document.md". */
    fun fileName(doc: DocumentInfo): String =
        doc.path.substringAfterLast('/').substringAfterLast('\\').ifBlank { "document.${doc.format.ifBlank { "md" }}" }

    /** The first Markdown heading, or the file name. */
    fun title(doc: DocumentInfo): String =
        doc.markdown.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("#") }
            ?.trimStart('#')?.trim()?.ifBlank { null }
            ?: fileName(doc)

    /** What Save suggests: the title as a file name, keeping letters, digits, spaces and dashes. */
    fun saveName(doc: DocumentInfo): String {
        val stem = title(doc).replace(Regex("[^\\p{L}\\p{N} _-]+"), " ").trim().replace(Regex("\\s+"), " ").take(60).trim()
        return (stem.ifBlank { "document" }) + ".md"
    }

    /** The row under an answer: "document.md · 1.2k words" (words only when there is text). */
    fun rowDetail(doc: DocumentInfo): String {
        val words = doc.markdown.split(Regex("\\s+")).count { w -> w.any(Char::isLetterOrDigit) }
        val count = when {
            words >= 1000 -> "${"%.1f".format(java.util.Locale.US, words / 1000.0)}k words"
            words > 0 -> "$words words"
            else -> null
        }
        return listOfNotNull(fileName(doc), count, if (doc.truncated) "shortened" else null).joinToString(" · ")
    }
}
