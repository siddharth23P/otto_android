package dev.otto.phone.transport

/** How long to wait before each attempt to reach `otto serve` again after the socket dropped (#16):
 *  1 s, doubling, at most 30 s, for as long as the transport is the live one. */
class Backoff(private val firstMs: Long = 1_000, private val maxMs: Long = 30_000) {
    private var attempt = 0

    fun next(): Long {
        val wait = (firstMs shl minOf(attempt, 20)).coerceAtMost(maxMs)
        attempt += 1
        return wait
    }
}

/** Whether an `otto serve` is older than this app was built against (#16): its hello's version against
 *  otto_app/compat.py's MIN_OTTO. Unparseable versions are not called old. */
object ServeVersion {
    const val MIN_OTTO = "0.1.2"
    const val UPGRADE = "pipx upgrade otto-cli-agent"

    fun behind(version: String, minimum: String = MIN_OTTO): Boolean {
        val have = parts(version) ?: return false
        val need = parts(minimum) ?: return false
        for (i in 0 until maxOf(have.size, need.size)) {
            val a = have.getOrElse(i) { 0 }
            val b = need.getOrElse(i) { 0 }
            if (a != b) return a < b
        }
        return false
    }

    fun hint(version: String): String? =
        if (behind(version)) "otto serve is $version; this app needs $MIN_OTTO or later — run `$UPGRADE` on that computer." else null

    private fun parts(version: String): List<Int>? {
        val release = Regex("^\\s*v?(\\d+(?:\\.\\d+)*)").find(version)?.groupValues?.get(1) ?: return null
        return release.split('.').map { it.toInt() }
    }
}
