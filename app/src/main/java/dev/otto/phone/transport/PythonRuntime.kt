package dev.otto.phone.transport

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** Starts Chaquopy's interpreter once. The embedded transport and the attachment reader both need it,
 *  often at the same moment (a file shared into Otto is read while the runtime comes up), and
 *  Python.start throws when a second caller gets past isStarted() first. */
object PythonRuntime {
    fun get(context: Context): Python = synchronized(this) {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        Python.getInstance()
    }
}
