package dev.otto.phone

import android.app.Application
import dev.otto.phone.data.Prefs
import dev.otto.phone.transport.Connection

class OttoApp : Application() {
    lateinit var prefs: Prefs
        private set

    /** The transport every screen shares. */
    lateinit var connection: Connection
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        connection = Connection(this, prefs)
    }
}
