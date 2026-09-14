package dev.otto.phone

import android.app.Application
import dev.otto.phone.data.Prefs

class OttoApp : Application() {
    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }
}
