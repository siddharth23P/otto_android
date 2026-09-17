package dev.otto.phone.access

/**
 * Whether the page in front is a protected (FLAG_SECURE) window.
 *
 * Accessibility does not expose FLAG_SECURE; only a refused screenshot does. Waiting for the agent to
 * ask for a look left a turn that only read the tree -- the usual turn -- blind to it inside a banking or
 * payment app that sets the flag (#18). So each page is probed once, the first time it is read: one
 * capture whose picture is dropped unread, and whose only product is this verdict. A verdict holds for
 * its page (package and window-state count) until the app opens another.
 *
 * Captures are rate-limited by the system (one per ~333 ms), and the probe shares that budget with the
 * agent's own looks: [waitMs] says how long to hold off before capturing, and a probe the system
 * refused as too soon leaves the page unknown, to be probed on its next read.
 */
class SecureProbe(private val clock: () -> Long, private val minGapMs: Long = MIN_GAP_MS) {
    private var page: Pair<String, Long>? = null
    private var secure = false
    private var lastCaptureAt = Long.MIN_VALUE / 2

    /** The verdict for this page, or null when it has not been probed. */
    @Synchronized fun known(packageName: String, seq: Long): Boolean? =
        if (page == (packageName to seq)) secure else null

    @Synchronized fun record(packageName: String, seq: Long, isSecure: Boolean) {
        page = packageName to seq
        secure = isSecure
    }

    /** A capture was just asked for, the probe's or the agent's. */
    @Synchronized fun captured() {
        lastCaptureAt = clock()
    }

    /** How long to wait before a capture the system would not refuse as too soon. */
    @Synchronized fun waitMs(): Long = maxOf(0L, lastCaptureAt + minGapMs - clock())

    /** Whether a page needs no probe at all: nothing to read, or Otto's own window. */
    fun skip(packageName: String, ownPackage: String): Boolean = packageName.isEmpty() || packageName == ownPackage

    companion object {
        /** A little over the system's 333 ms between captures. */
        const val MIN_GAP_MS = 350L
        /** The longest a read waits for a probe's answer; past it the page stays unknown. */
        const val ANSWER_MS = 2_000L
    }
}
