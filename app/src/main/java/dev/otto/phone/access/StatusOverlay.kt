package dev.otto.phone.access

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What the strip says, folded from the agent's events. `step` is the latest thing the agent said it is
 * doing; [text] is null when nothing should be shown. Pure, so which events move it is tested on the JVM.
 */
data class StatusLine(val running: Boolean = false, val step: String = "") {
    val text: String? get() = if (running) "Otto: ${step.ifBlank { "working" }}" else null

    fun after(event: JsonObject): StatusLine = when (event.str("type")) {
        "started" -> StatusLine(running = true, step = "thinking")
        // A partial is the answer being written, not a step: the strip keeps saying what was last done.
        "progress" -> if (event.str("kind") == "partial") this else clean(event.str("text"))?.let { StatusLine(true, it) } ?: this
        "board" -> (event["lines"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.lastOrNull { it.isNotBlank() }?.let(::clean)?.let { StatusLine(true, it) } ?: this
        "ask" -> if (running) copy(step = "has a question for you") else this
        "final", "error" -> StatusLine()
        else -> this
    }

    companion object {
        /** About two lines of the strip. The view also stops at two lines; this keeps a runaway board line
         *  from being laid out at all. */
        const val MAX_STEP = 100
        private val WHITESPACE = Regex("\\s+")

        /** One run of plain text: whitespace and newlines collapsed, cut at [MAX_STEP] with an ellipsis. Null when blank. */
        fun clean(raw: String?): String? {
            val flat = raw?.trim()?.replace(WHITESPACE, " ")?.takeIf { it.isNotEmpty() } ?: return null
            return if (flat.length <= MAX_STEP) flat else flat.take(MAX_STEP - 1).trimEnd() + "…"
        }
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * A strip under the status bar saying what Otto is doing while it works in another app, so a person
 * watching the phone move knows what is moving it. Display only: an accessibility overlay (no
 * SYSTEM_ALERT_WINDOW) that never takes a touch or focus, so the agent's own taps and swipes go through
 * it. Kept out of what the agent reads: it can never be the active window a walk reads, since it cannot
 * be focused or touched, and [hideForCapture] takes it off screen around a screenshot.
 *
 * The window is only touched on the main thread: [onEvent], [frontChanged] and [destroy] are called
 * there; [hideForCapture] and [restoreAfterCapture] from the actions thread, and wait for it.
 */
class StatusOverlay(private val service: AccessibilityService, private val ottoInFront: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private var line = StatusLine()
    private var inFront = true
    private var capturing = false
    private var view: TextView? = null
    @Volatile private var toggledAt = Long.MIN_VALUE

    /** Whether the strip is on screen. Main thread. */
    val showing: Boolean get() = view != null

    /** Whether the strip came or went within the last [OWN_WINDOWS_MS]: a windows-changed event then is
     *  most likely its own, not the app's. */
    fun justToggled(now: Long): Boolean = toggledAt != Long.MIN_VALUE && now - toggledAt < OWN_WINDOWS_MS

    fun onEvent(event: JsonObject) {
        val was = line
        line = line.after(event)
        // A turn starts from Otto's chat, but ask the screen rather than assume.
        if (line.running && !was.running) inFront = ottoInFront()
        render()
    }

    /** A window came or went: shown only while Otto's own app is not the one in front. */
    fun frontChanged() {
        if (!line.running) return
        inFront = ottoInFront()
        render()
    }

    /** Takes the strip off screen for a capture, so a vision model never reads Otto's own words, and waits
     *  until the compositor has dropped it. Always paired with [restoreAfterCapture]. */
    fun hideForCapture() {
        if (onMain { capturing = true; render() } == true) Thread.sleep(CAPTURE_GAP_MS)
    }

    fun restoreAfterCapture() { onMain { capturing = false; render() } }

    fun destroy() {
        line = StatusLine()
        render()
    }

    /** Puts the strip on screen, updates it or takes it off, to match what it should show. True when it came off. */
    private fun render(): Boolean {
        val text = line.text?.takeIf { !inFront && !capturing }
        val current = view
        if (text == null) {
            if (current == null) return false
            runCatching { windowManager.removeViewImmediate(current) }
            view = null
            toggledAt = SystemClock.uptimeMillis()
            return true
        }
        if (current != null) {
            if (current.text.toString() != text) current.text = text
            return false
        }
        val strip = strip(text)
        runCatching { windowManager.addView(strip, params()) }.onSuccess {
            view = strip
            toggledAt = SystemClock.uptimeMillis()
        }
        return false
    }

    private fun strip(text: String) = TextView(service).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = service.resources.displayMetrics.widthPixels - dp(32)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xCC202124.toInt()) }
        // Inert: plain text, no links or selection, nothing for accessibility to read or announce.
        autoLinkMask = 0
        linksClickable = false
        setTextIsSelectable(false)
        isFocusable = false
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        // Placed by hand below the status bar and any cutout, not fitted by the window manager.
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        y = windowManager.currentWindowMetrics.windowInsets
            .getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top + dp(8)
        windowAnimations = 0
        title = "Otto status"
    }

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()

    /** Runs `block` on the main thread and waits for it, a second at most (then null). */
    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val done = CountDownLatch(1)
        var result: T? = null
        main.post { try { result = block() } finally { done.countDown() } }
        done.await(1, TimeUnit.SECONDS)
        return result
    }

    companion object {
        /** How long a removed window takes to leave the screen a capture sees: a couple of frames, and slack. */
        const val CAPTURE_GAP_MS = 100L
        /** How soon after the strip comes or goes the windows-changed event it causes arrives. */
        const val OWN_WINDOWS_MS = 300L
    }
}
