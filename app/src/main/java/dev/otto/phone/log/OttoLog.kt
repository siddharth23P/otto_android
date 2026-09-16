package dev.otto.phone.log

import android.util.Log

/**
 * Otto's own log lines, under tags that all start with "Otto", so one filter reads the app and the
 * embedded runtime together:
 *
 *     adb logcat -s OttoDevice OttoGuard OttoLink OttoTransport OttoEvent OttoOverlay python.stderr python.stdout
 *
 * Never a key's value or typed text. On the JVM (unit tests) android.util.Log is a stub that throws,
 * so a line goes to stdout there instead.
 */
object OttoLog {
    fun d(tag: String, message: String) = write(Log.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = write(Log.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = write(Log.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = write(Log.ERROR, tag, message, error)

    private fun write(priority: Int, tag: String, message: String, error: Throwable?) {
        try {
            Log.println(priority, tag, if (error == null) message else "$message\n${Log.getStackTraceString(error)}")
        } catch (e: RuntimeException) {
            println("$tag: $message${error?.let { " ($it)" } ?: ""}")
        }
    }
}
