package dev.otto.phone.access

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** What an accessibility event says happened, as far as waiting for a screen is concerned. */
enum class Kind { CONTENT, SCROLL, STATE, WINDOWS }

/**
 * When the screen last said it changed. Written from the main thread as events arrive, read from the
 * actions thread while it waits, so every field is atomic. Times are the caller's clock (uptime).
 */
class EventLog {
    private val anyAt = AtomicLong(NEVER)
    private val stateAt = AtomicLong(NEVER)
    private val seq = AtomicLong(0)
    private val statePkg = AtomicReference(Stamp("", NEVER))

    private val pages = ConcurrentHashMap<String, Page>()

    private data class Stamp(val pkg: String, val at: Long)

    /** A package's own window changes so far, and the class it last named for its window. */
    data class Page(val seq: Long, val activity: String)

    val lastAnyAt: Long get() = anyAt.get()
    /** The last window change (a new activity, dialog or window), from any package. */
    val lastStateAt: Long get() = stateAt.get()
    val stateSeq: Long get() = seq.get()
    /** The package of the last window-state change that named one. */
    val lastStatePkg: String get() = statePkg.get().pkg

    /** `className` is the class a window-state change names: the activity, dialog or pane that arrived. */
    fun record(kind: Kind, pkg: String, at: Long, className: String = "") {
        // The status bar's clock and icons redraw all the time and say nothing about the app; a
        // notification shade or a system dialog opening does.
        if ((kind == Kind.CONTENT || kind == Kind.SCROLL) && pkg == SYSTEM_UI) return
        anyAt.accumulateAndGet(at, ::maxOf)
        if (kind == Kind.STATE || kind == Kind.WINDOWS) {
            stateAt.accumulateAndGet(at, ::maxOf)
            seq.incrementAndGet()
            // A windows-changed event names no package: only a state change says who is in front.
            if (kind == Kind.STATE && pkg.isNotEmpty()) {
                statePkg.set(Stamp(pkg, at))
                // Counted per package: the keyboard or the status bar opening is not the app's page changing.
                pages.compute(pkg) { _, was ->
                    Page((was?.seq ?: 0) + 1, className.take(MAX_ACTIVITY).ifEmpty { was?.activity ?: "" })
                }
            }
        }
    }

    /** The page `pkg` is on: how many window changes it has made, and what it named the last one. */
    fun pageOf(pkg: String): Page = pages[pkg] ?: Page(0, "")

    /** Whether `pkg` changed its window at or after `since`. */
    fun stateSeenFor(pkg: String, since: Long): Boolean = statePkg.get().let { it.pkg == pkg && it.at >= since }

    /** Whether a package other than `pkg` changed its window at or after `since`: something else came to the front. */
    fun stateSeenOtherThan(pkg: String, since: Long): Boolean = statePkg.get().let { it.pkg.isNotEmpty() && it.pkg != pkg && it.at >= since }

    companion object {
        const val NEVER = Long.MIN_VALUE
        const val SYSTEM_UI = "com.android.systemui"
        const val MAX_ACTIVITY = 120
    }
}

/**
 * How long a screen may take to settle after an action. `minMs`: never read before this, the app has
 * not drawn yet. `quietMs`: no event for this long. `stateMinMs`: after a new window, at least this long
 * past it, because the window's content is filled in after the window says it arrived. `capMs`: from
 * before the action, read whatever is there.
 */
data class SettlePolicy(val minMs: Long, val quietMs: Long, val stateMinMs: Long, val capMs: Long) {
    companion object {
        val ACTION = SettlePolicy(150, 200, 450, 1500)
        val SCROLL = SettlePolicy(150, 200, 450, 1200)
        val LAUNCH = SettlePolicy(300, 300, 600, 3000)
        val SETTINGS = SettlePolicy(300, 300, 600, 2500)
    }
}

data class Settle(val settled: Boolean, val waitedMs: Long, val reason: String)

/**
 * Waits for the screen to stop changing, instead of for a fixed time: a tap that changes nothing is
 * read back in 200 ms, a new screen as soon as it has drawn, and a screen that never stops (a video, a
 * carousel) at the cap, marked unsettled. Pure: the clock, the sleep and the events are given.
 */
class Settler(private val clock: () -> Long, private val sleep: (Long) -> Unit, private val log: EventLog) {
    /** `since` is taken before the action, `actedAt` once it returned; events before `since` belong to
     *  an earlier screen. `until`, when given, must also hold (the app asked for is in front). */
    fun await(since: Long, actedAt: Long, policy: SettlePolicy, until: (() -> Boolean)? = null): Settle {
        val start = clock()
        val cap = since + policy.capMs
        while (true) {
            val now = clock()
            val stateAt = log.lastStateAt.takeIf { it >= since }
            val anyAt = log.lastAnyAt.takeIf { it >= since } ?: EventLog.NEVER
            val quiet = now >= actedAt + policy.minMs &&
                (stateAt == null || now >= stateAt + policy.stateMinMs) &&
                now - maxOf(anyAt, actedAt) >= policy.quietMs
            val there = quiet && (until == null || until())
            if (there) return Settle(true, now - start, "settled")
            if (now >= cap) return Settle(false, now - start, if (quiet) "target-not-in-front" else "events-still-arriving")
            sleep(minOf(STEP_MS, cap - now))
        }
    }

    companion object {
        const val STEP_MS = 25L
    }
}
