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

/** otto serve refuses key and routing changes from anywhere but its own computer. */
const val FORBIDDEN_SETUP = "Keys and routing can only be changed from the computer running otto serve (or over USB via adb reverse)"

/** `problem` for a request that writes otto's configuration (set_key, pin, clear): a `forbidden`
 *  reply is explained rather than repeated. */
fun Reply<*>.writeProblem(fallback: String = "failed"): String? =
    if (this is Reply.Err && code == "forbidden") FORBIDDEN_SETUP else problem(fallback)
