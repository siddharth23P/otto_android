package dev.otto.phone.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Taps and swipes as gestures, dispatched one at a time: a new gesture
 *  cancels one in flight, so every dispatch waits for its callback. */
object Gestures {
    fun tap(x: Int, y: Int, long: Boolean = false): GestureDescription {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val duration = if (long) 700L else 60L
        return GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300L): GestureDescription {
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        return GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
    }

    /** True when the gesture completed, false when cancelled or never acknowledged. */
    fun dispatch(service: AccessibilityService, gesture: GestureDescription, timeoutMs: Long = 5000L): Boolean {
        val latch = CountDownLatch(1)
        var completed = false
        val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { completed = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { completed = false; latch.countDown() }
        }, null)
        if (!accepted) return false
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return completed
    }
}
