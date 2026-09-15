package dev.otto.phone.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Motion from the web: two durations, eased out, never bouncy; a pulsing dot for a running tool,
 *  three staggered dots for typing, a tone pulse for a skeleton. All of it stops when the system
 *  animator scale is 0 (Settings → Accessibility → Remove animations). */
object Motion {
    val Ease = CubicBezierEasing(MotionTokens.EASE[0], MotionTokens.EASE[1], MotionTokens.EASE[2], MotionTokens.EASE[3])

    fun <T> fast(delayMillis: Int = 0): FiniteAnimationSpec<T> = tween(MotionTokens.FAST_MS, delayMillis, Ease)
    fun <T> mid(delayMillis: Int = 0): FiniteAnimationSpec<T> = tween(MotionTokens.MID_MS, delayMillis, Ease)
}

/** True when the person turned animations off. */
val LocalReducedMotion = compositionLocalOf { false }

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = read() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

/** Enter: fade up 22 dp, staggered 90 ms per index. None at all with reduced motion. */
@Composable
fun rememberFadeUp(index: Int = 0): EnterTransition {
    val reduced = LocalReducedMotion.current
    val offset = with(LocalDensity.current) { MotionTokens.ENTER_OFFSET_DP.dp.roundToPx() }
    return remember(index, reduced, offset) {
        if (reduced) EnterTransition.None
        else {
            val delay = index * MotionTokens.STAGGER_MS
            fadeIn(Motion.mid(delay)) + slideInVertically(Motion.mid(delay)) { offset }
        }
    }
}

/** Everything clickable compresses slightly while pressed. */
@Composable
fun Modifier.pressScale(interaction: InteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(if (pressed && !reduced) MotionTokens.PRESS_SCALE else 1f, tween(MotionTokens.PRESS_MS), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A tool is running: a 6 dp dot that pulses, not a spinner. */
@Composable
fun PulseDot(color: Color, modifier: Modifier = Modifier, size: Dp = Spacing.pulseDot) {
    val reduced = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha = transition.animateFloat(1f, 0.3f, infiniteRepeatable(tween(MotionTokens.PULSE_MS, easing = Motion.Ease), RepeatMode.Reverse), label = "pulse-alpha")
    Box(modifier.size(size).graphicsLayer { this.alpha = if (reduced) 1f else alpha.value }.background(color, CircleShape))
}

/** Otto is typing: three 4 dp dots, staggered. */
@Composable
fun TypingDots(color: Color, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.typingDot)) {
        repeat(3) { i ->
            val alpha = transition.animateFloat(
                1f, 0.25f,
                infiniteRepeatable(tween(MotionTokens.PULSE_MS, easing = Motion.Ease), RepeatMode.Reverse, StartOffset(i * MotionTokens.TYPING_STAGGER_MS)),
                label = "typing-$i",
            )
            Box(Modifier.size(Spacing.typingDot).graphicsLayer { this.alpha = if (reduced) 1f else alpha.value }.background(color, CircleShape))
        }
    }
}

/** A skeleton: the shape's tone pulses between bg2 and card2 over 1.4 s. No shimmer. */
@Composable
fun Modifier.skeletonPulse(colors: OttoColors, shape: Shape = OttoShapes.r1): Modifier {
    val reduced = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "skeleton")
    val t = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(MotionTokens.SKELETON_MS / 2, easing = Motion.Ease), RepeatMode.Reverse), label = "skeleton-t")
    return drawBehind {
        val fill = if (reduced) colors.bg2 else lerp(colors.bg2, colors.card2, t.value)
        val outline: Outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(outline, fill)
    }
}
