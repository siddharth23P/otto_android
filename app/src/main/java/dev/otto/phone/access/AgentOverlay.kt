package dev.otto.phone.access

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import dev.otto.phone.R
import dev.otto.phone.ui.MainActivity
import dev.otto.phone.ui.theme.MotionTokens
import dev.otto.phone.ui.theme.Palette
import dev.otto.phone.ui.theme.Palettes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject

/**
 * The sign that Otto has the phone, while it works in another app, and the way back when it is done.
 *
 * Two accessibility overlays (no SYSTEM_ALERT_WINDOW):
 *  - the edge: the whole screen, never touchable or focusable, drawing a dimmed vignette along the edges
 *    and a clay border whose light travels round the display's corners. It is up only while the agent
 *    acts ([OverlayState.agentHasPhone]); it fades in and out on the DataNodes ease and stands still
 *    when the person turned animations off. A deliberate exception to DataNodes' no-gradient rule,
 *    kept to this one signal.
 *  - the card: at the bottom, above the navigation bar, in the app's Studio or Paper palette -- a mono
 *    line (`OTTO · 0:12 · 4 calls`) by a pulsing dot, the step, and "on your phone". While the agent
 *    acts it takes no touch, so a tap the agent makes under it lands. When the turn ends it becomes the
 *    result -- DONE, STOPPED or FAILED, the answer's first line -- with Back to Otto and a close, for
 *    [OverlayState.DONE_SHOWN_MS]; it goes at once if the person opens Otto themselves.
 *
 * Kept out of what the agent reads: neither window can be the active window a walk reads, and
 * [hideForCapture] takes both off screen around a screenshot. Nothing shows while Otto's own app is in
 * front. Windows are only touched on the main thread; [hideForCapture] and [restoreAfterCapture] are
 * called from the actions thread and wait for it.
 */
class AgentOverlay(private val service: AccessibilityService, private val ottoInFront: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val ease = PathInterpolator(MotionTokens.EASE[0], MotionTokens.EASE[1], MotionTokens.EASE[2], MotionTokens.EASE[3])
    private var state: OverlayState = OverlayState.Hidden
    private var inFront = true
    private var capturing = false
    private var themePref = "system"
    private var edge: EdgeView? = null
    private var fading: View? = null
    private var card: Card? = null
    @Volatile private var toggledAt = Long.MIN_VALUE

    /** Whether any of it is on screen. Main thread. */
    val showing: Boolean get() = edge != null || card != null || fading != null

    /** Whether a window came or went within the last [OWN_WINDOWS_MS]: a windows-changed event then is most
     *  likely its own, not the app's. */
    fun justToggled(now: Long): Boolean = toggledAt != Long.MIN_VALUE && now - toggledAt < OWN_WINDOWS_MS

    fun onEvent(event: JsonObject) {
        val was = state
        state = state.after(event, now())
        // A turn starts from Otto's chat, and may end with the person already back in it: ask the screen.
        if ((state is OverlayState.Working && was !is OverlayState.Working) || state is OverlayState.Done) inFront = ottoInFront()
        if (state is OverlayState.Done && inFront) state = OverlayState.Hidden
        render()
    }

    /** A window came or went: shown only while Otto's own app is not the one in front, and the result is
     *  gone once the person has come back to Otto. */
    fun frontChanged() {
        if (state == OverlayState.Hidden) return
        inFront = ottoInFront()
        if (inFront && state is OverlayState.Done) state = OverlayState.Hidden
        render()
    }

    /** The Settings choice: "system", "studio" or "paper". */
    fun setTheme(pref: String) {
        if (pref == themePref) return
        themePref = pref
        card?.let { removeCard(it) }
        render()
    }

    /** Takes both windows off screen for a capture, so a vision model never reads Otto's own words, and
     *  waits until the compositor has dropped them. Always paired with [restoreAfterCapture]. */
    fun hideForCapture() {
        if (onMain { capturing = true; render(); true } == true) Thread.sleep(CAPTURE_GAP_MS)
    }

    fun restoreAfterCapture() { onMain { capturing = false; render() } }

    fun destroy() {
        state = OverlayState.Hidden
        render()
        main.removeCallbacksAndMessages(null)
    }

    private fun now() = SystemClock.uptimeMillis()

    private val tick = object : Runnable {
        override fun run() {
            val c = card ?: return
            c.bind(state, now())
            if (state is OverlayState.Working) main.postDelayed(this, TICK_MS)
        }
    }

    private val expire = Runnable {
        if (state.expired(now())) state = OverlayState.Hidden
        render()
    }

    /** Brings the windows in line with the state. */
    private fun render() {
        main.removeCallbacks(tick)
        main.removeCallbacks(expire)
        val s = state
        val visible = !inFront && s != OverlayState.Hidden
        if (visible && !capturing && s.agentHasPhone) showEdge() else hideEdge(immediately = capturing)
        if (visible && !capturing) showCard(s) else card?.let { removeCard(it) }
        if (card == null) return
        when (s) {
            is OverlayState.Working -> main.postDelayed(tick, TICK_MS)
            is OverlayState.Done -> main.postDelayed(expire, (s.at + OverlayState.DONE_SHOWN_MS - now()).coerceAtLeast(0))
            OverlayState.Hidden -> Unit
        }
    }

    // -- the edge -------------------------------------------------------------------------------

    private fun reducedMotion() = Settings.Global.getFloat(service.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun dark() = when (themePref) {
        "studio" -> true
        "paper" -> false
        else -> (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun showEdge() {
        if (edge != null) return
        fading?.let { removeWindow(it); fading = null }
        val palette = Palettes.of(dark())
        val reduced = reducedMotion()
        val view = EdgeView(service, palette, cornerRadius(), dp(3f), dp(34f), animate = !reduced)
        if (!addWindow(view, edgeParams())) return
        edge = view
        if (reduced) view.alpha = 1f else { view.alpha = 0f; view.animate().alpha(1f).setDuration(MotionTokens.MID_MS.toLong()).setInterpolator(ease).start() }
    }

    private fun hideEdge(immediately: Boolean) {
        val view = edge
        if (immediately) {
            view?.let { removeWindow(it) }
            fading?.let { removeWindow(it) }
            edge = null
            fading = null
            return
        }
        if (view == null) return
        edge = null
        if (reducedMotion()) { removeWindow(view); return }
        fading = view
        view.animate().alpha(0f).setDuration(MotionTokens.MID_MS.toLong()).setInterpolator(ease).withEndAction {
            if (fading === view) { removeWindow(view); fading = null }
        }.start()
    }

    private fun cornerRadius(): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val display = service.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            display?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.takeIf { it > 0 }?.let { return it.toFloat() }
        }
        return dp(24f)
    }

    private fun edgeParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        windowAnimations = 0
        title = "Otto edge"
    }

    /** The vignette and the travelling border. Draws only: no layout per frame, one animator. */
    private class EdgeView(context: Context, palette: Palette, private val radius: Float, private val stroke: Float,
                           private val depth: Float, private val animate: Boolean) : View(context) {
        private val accent = palette.accent.toInt()
        private val shade = if (palette.dark) 0x80000000.toInt() else withAlpha(palette.ink, 0x4D)
        private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = withAlpha(palette.accent, if (animate) 0x4D else 0xCC) }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
        private val vignette = Paint()
        private val frame = RectF()
        private val rotation = Matrix()
        private var sweep: SweepGradient? = null
        private var shades: List<Pair<Shader, RectF>> = emptyList()
        private var angle = 0f
        private val lap = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = LAP_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { angle = it.animatedValue as Float; invalidate() }
        }

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isFocusable = false
            isClickable = false
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val fw = w.toFloat()
            val fh = h.toFloat()
            frame.set(stroke / 2, stroke / 2, fw - stroke / 2, fh - stroke / 2)
            val clear = 0x00000000
            sweep = SweepGradient(fw / 2, fh / 2,
                intArrayOf(withAlpha(accent.toLong(), 0), withAlpha(accent.toLong(), 0), withAlpha(accent.toLong(), 0xFF), withAlpha(accent.toLong(), 0), withAlpha(accent.toLong(), 0)),
                floatArrayOf(0f, 0.6f, 0.78f, 0.96f, 1f))
            shades = listOf(
                LinearGradient(0f, 0f, 0f, depth, shade, clear, Shader.TileMode.CLAMP) to RectF(0f, 0f, fw, depth),
                LinearGradient(0f, fh, 0f, fh - depth, shade, clear, Shader.TileMode.CLAMP) to RectF(0f, fh - depth, fw, fh),
                LinearGradient(0f, 0f, depth, 0f, shade, clear, Shader.TileMode.CLAMP) to RectF(0f, 0f, depth, fh),
                LinearGradient(fw, 0f, fw - depth, 0f, shade, clear, Shader.TileMode.CLAMP) to RectF(fw - depth, 0f, fw, fh),
            )
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (animate) lap.start()
        }

        override fun onDetachedFromWindow() {
            lap.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            for ((shader, area) in shades) {
                vignette.shader = shader
                canvas.drawRect(area, vignette)
            }
            canvas.drawRoundRect(frame, radius, radius, base)
            val light = sweep ?: return
            if (!animate) return
            rotation.setRotate(angle, width / 2f, height / 2f)
            light.setLocalMatrix(rotation)
            glow.shader = light
            canvas.drawRoundRect(frame, radius, radius, glow)
        }
    }

    // -- the card -------------------------------------------------------------------------------

    private fun showCard(s: OverlayState) {
        val existing = card
        if (existing != null) {
            existing.bind(s, now())
            return
        }
        val made = Card(Palettes.of(dark()))
        made.bind(s, now())
        if (addWindow(made.root, made.params)) card = made
    }

    private fun removeCard(c: Card) {
        c.stop()
        removeWindow(c.root)
        card = null
    }

    private fun openOtto() {
        runCatching {
            service.startActivity(Intent(service, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        state = OverlayState.Hidden
        render()
    }

    private fun dismiss() {
        state = OverlayState.Hidden
        render()
    }

    private inner class Card(private val palette: Palette) {
        private val mono: Typeface = runCatching { service.resources.getFont(R.font.ibm_plex_mono_regular) }.getOrDefault(Typeface.MONOSPACE)
        private val monoBold: Typeface = runCatching { service.resources.getFont(R.font.ibm_plex_mono_semibold) }.getOrDefault(Typeface.MONOSPACE)
        private val reduced = reducedMotion()
        private var touchable: Boolean? = null
        private var pulsing = false

        private val dot = View(service).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(palette.accent.toInt()) }
        }
        private val pulse = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.3f).apply {
            duration = MotionTokens.PULSE_MS.toLong()
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = ease
        }
        private val meta = text(11f, palette.dim, monoBold).apply { letterSpacing = 0.06f; isSingleLine = true }
        private val chip = text(10f, palette.faint, mono).apply {
            text = "on your phone"
            isSingleLine = true
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply { cornerRadius = dp(999f); setStroke(dp(1), palette.line.toInt()) }
        }
        private val words = text(14f, palette.ink, Typeface.DEFAULT).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        private val back = text(14f, palette.onAccent, Typeface.DEFAULT_BOLD).apply {
            text = "Back to Otto"
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(18), 0, dp(18), 0)
            background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(palette.accent.toInt()) }
            isClickable = true
            setOnClickListener { openOtto() }
        }
        private val close = text(16f, palette.ink, Typeface.DEFAULT).apply {
            text = "✕"
            contentDescription = "Dismiss"
            gravity = Gravity.CENTER
            minWidth = dp(44)
            minHeight = dp(44)
            background = GradientDrawable().apply { cornerRadius = dp(10f); setStroke(dp(1), palette.line.toInt()) }
            isClickable = true
            setOnClickListener { dismiss() }
        }
        private val actions = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(close, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        }

        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = GradientDrawable().apply {
                cornerRadius = dp(13f)
                setColor(withAlpha(palette.card, 0xF0))
                setStroke(dp(1), palette.line.toInt())
            }
            val top = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) })
                addView(meta, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            addView(top)
            addView(words, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }

        val params = WindowManager.LayoutParams(
            (service.resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(560)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            fitInsetsTypes = 0
            y = windowManager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom + dp(12)
            windowAnimations = 0
            title = "Otto card"
        }

        fun bind(s: OverlayState, now: Long) {
            meta.text = s.meta(now)
            words.text = s.words
            val working = s is OverlayState.Working
            val done = s as? OverlayState.Done
            chip.visibility = if (s.agentHasPhone) View.VISIBLE else View.GONE
            actions.visibility = if (done != null) View.VISIBLE else View.GONE
            val color = when (done?.outcome) {
                null -> palette.accent
                OverlayState.Outcome.DONE -> palette.ok
                OverlayState.Outcome.STOPPED -> palette.warn
                OverlayState.Outcome.FAILED -> palette.bad
            }
            (dot.background as GradientDrawable).setColor(color.toInt())
            val shouldPulse = working && !reduced
            if (shouldPulse != pulsing) {
                pulsing = shouldPulse
                if (shouldPulse) pulse.start() else { pulse.cancel(); dot.alpha = 1f }
            }
            // Touchable only when the agent is not acting: at the end of the turn, or while it waits on a question.
            val waiting = done != null || (s as? OverlayState.Working)?.asking == true
            root.isClickable = waiting && done == null
            root.setOnClickListener(if (waiting && done == null) View.OnClickListener { openOtto() } else null)
            root.importantForAccessibility = if (waiting) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (touchable != waiting) {
                touchable = waiting
                params.flags = BASE_FLAGS or (if (waiting) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                if (root.isAttachedToWindow) runCatching { windowManager.updateViewLayout(root, params) }
            }
        }

        fun stop() {
            pulse.cancel()
        }

        private fun text(sp: Float, color: Long, face: Typeface) = TextView(service).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color.toInt())
            typeface = face
            autoLinkMask = 0
            linksClickable = false
            setTextIsSelectable(false)
            includeFontPadding = false
        }
    }

    // -- windows --------------------------------------------------------------------------------

    private fun addWindow(view: View, params: WindowManager.LayoutParams): Boolean =
        runCatching { windowManager.addView(view, params) }.onSuccess { toggledAt = now() }.isSuccess

    private fun removeWindow(view: View) {
        runCatching { windowManager.removeViewImmediate(view) }
        toggledAt = now()
    }

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Float = v * service.resources.displayMetrics.density

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
        /** How soon after a window comes or goes the windows-changed event it causes arrives. */
        const val OWN_WINDOWS_MS = 300L
        /** One lap of the border's light. */
        const val LAP_MS = 2400L
        /** How often the card's clock moves. */
        const val TICK_MS = 1000L
        private const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        private fun withAlpha(argb: Long, alpha: Int): Int = (alpha shl 24) or (argb.toInt() and 0xFFFFFF)
    }
}
