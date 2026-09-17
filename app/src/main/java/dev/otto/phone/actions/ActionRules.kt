package dev.otto.phone.actions

import dev.otto.phone.bridge.DeviceException
import java.io.File
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** The checks and conversions behind the actions, pure so the JVM tests hold them. */
object ActionRules {
    private fun invalid(message: String) = DeviceException(message, "invalid")

    /** An event's start and end in epoch millis, and whether it is all day. A bare date is an
     *  all-day event; an end defaults to an hour after a timed start. */
    fun eventTimes(start: String, end: String, zone: ZoneId = ZoneId.systemDefault()): Triple<Long, Long, Boolean> {
        fun parse(s: String): Pair<Long, Boolean> = try {
            LocalDateTime.parse(s.trim()).atZone(zone).toInstant().toEpochMilli() to false
        } catch (e: DateTimeParseException) {
            try {
                LocalDate.parse(s.trim()).atStartOfDay(zone).toInstant().toEpochMilli() to true
            } catch (e2: DateTimeParseException) {
                throw invalid("'$s' is not a date (2026-09-18) or date-time (2026-09-18T15:00)")
            }
        }
        val (begin, allDay) = parse(start)
        val finish = if (end.isBlank()) begin + if (allDay) DAY_MS else HOUR_MS else parse(end).first
        if (finish < begin) throw invalid("the event ends before it starts")
        return Triple(begin, finish, allDay)
    }

    /** A phone number as dialers take it: digits and a leading +, nothing else. */
    fun phoneNumber(raw: String): String {
        val cleaned = raw.trim().let { (if (it.startsWith("+")) "+" else "") + it.filter(Char::isDigit) }
        if (cleaned.count(Char::isDigit) !in 3..15) throw invalid("'$raw' is not a phone number")
        return cleaned
    }

    /** wa.me wants the number with its country code, digits only. */
    fun whatsappUrl(phone: String, text: String): String {
        val digits = phoneNumber(phone).filter(Char::isDigit)
        if (digits.length < 8) throw invalid("a WhatsApp number needs its country code")
        return "https://wa.me/$digits?text=" + URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    }

    /** Only ordinary web pages: no intent:, javascript:, file:, content: or payment schemes. */
    fun webUrl(raw: String): String {
        val url = raw.trim()
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme !in setOf("http", "https") || !url.contains("://")) throw invalid("only http and https pages open, not '$url'")
        return url
    }

    fun mapsUri(query: String, navigate: Boolean): String {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return if (navigate) "google.navigation:q=$q" else "geo:0,0?q=$q"
    }

    fun emails(raw: String): Array<String> {
        val list = raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty() || list.any { !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(it) }) throw invalid("'$raw' is not an email address")
        return list.toTypedArray()
    }

    /** A file in the conversation's workspace: relative to it, or absolute inside `workspaces`. Never
     *  anything outside -- a share must not hand another app Otto's keys or sessions. */
    fun conversationFile(path: String, workspaces: File, session: String): File {
        val root = workspaces.canonicalFile
        val raw = File(path.trim())
        val candidate = if (raw.isAbsolute) raw else File(File(root, session), path.trim())
        val file = candidate.canonicalFile
        if (session.isBlank() && !raw.isAbsolute) throw invalid("no conversation is open to find '$path' in")
        if (!file.path.startsWith(root.path + File.separator)) throw invalid("'$path' is not one of this conversation's files")
        if (!file.isFile) throw invalid("no file at '$path'")
        return file
    }

    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "md" -> "text/markdown"
        "txt" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "application/octet-stream"
    }

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L
}
