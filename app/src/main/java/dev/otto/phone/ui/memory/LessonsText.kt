package dev.otto.phone.ui.memory

import dev.otto.phone.protocol.LessonRow
import dev.otto.phone.protocol.NoteSummary
import dev.otto.phone.state.Board
import dev.otto.phone.state.LessonTab

/** The memory screens' words, pure so the tests pin them. */
object LessonsText {
    const val NOTHING_LEARNED = "Nothing learned yet"
    const val NOTHING_LEARNED_BODY = "Otto keeps a lesson when something it tried worked or didn't, and uses it next time."
    const val NO_NOTES = "No app notes yet"
    const val NO_NOTES_BODY = "Notes on how an app's screens work appear here once Otto has used the app."

    fun tab(tab: LessonTab): String = when (tab) {
        LessonTab.LESSONS -> "lessons"
        LessonTab.PHONE_LESSONS -> "phone lessons"
        LessonTab.APP_NOTES -> "app notes"
    }

    /** The lesson's first line: its cue, or its whole text when otto couldn't split it. */
    fun cue(row: LessonRow): String = row.cue?.ifBlank { null } ?: row.text

    /** "→ tap Add to Cart", or null for a row without parts. */
    fun action(row: LessonRow): String? = row.action?.ifBlank { null }?.let { "→ $it" }

    private val GOOD = Regex("""(?i)\b(worked|works|ok|success|succeeded|done|passed|approved)\b""")
    private val BAD = Regex("""(?i)\b(failed|fails|didn't work|did not work|error|rejected|refused|timed out|wrong)\b""")

    fun outcomeLevel(outcome: String?): Board.Level? = when {
        outcome.isNullOrBlank() -> null
        BAD.containsMatchIn(outcome) -> Board.Level.BAD
        GOOD.containsMatchIn(outcome) -> Board.Level.OK
        else -> null
    }

    fun noteMeta(note: NoteSummary): String = listOfNotNull(
        if (note.seeded) "shipped note" else null,
        if (note.learned > 0) "${note.learned} learned" else null,
    ).joinToString(" · ").ifBlank { "empty" }

    fun clearLabel(count: Int, armed: Boolean): String = if (armed) "sure? clear $count" else "clear all ($count)"

    /** What TalkBack says for a lesson row. */
    fun describe(row: LessonRow): String =
        listOfNotNull(cue(row), row.action?.ifBlank { null }?.let { "then $it" }, row.outcome?.ifBlank { null }).joinToString(", ")
}
