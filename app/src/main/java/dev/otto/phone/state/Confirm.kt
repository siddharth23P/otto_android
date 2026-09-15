package dev.otto.phone.state

import dev.otto.phone.protocol.Reply
import dev.otto.phone.transport.NEEDS_NEWER_OTTO

/** A destructive action armed by a first tap ("sure?"). A second tap on the same thing within
 *  `WINDOW_MS` confirms it; after that the arm has lapsed and the next tap arms again. */
data class Armed(val key: String, val atMs: Long) {
    fun holds(key: String, nowMs: Long): Boolean = this.key == key && nowMs - atMs in 0..WINDOW_MS

    companion object {
        const val WINDOW_MS = 3_500L
    }
}

/** What a failed reply says to a person: the server's own words, or that otto is too old. */
fun Reply<*>.problem(fallback: String = "failed"): String? = when (this) {
    is Reply.Ok -> null
    is Reply.Err -> message.ifBlank { fallback }
    Reply.Unsupported -> NEEDS_NEWER_OTTO
}
