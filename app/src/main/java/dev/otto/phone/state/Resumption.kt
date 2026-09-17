package dev.otto.phone.state

/** A turn the process did not live to finish (#17). otto records a turn only when it completes, so a
 *  process death mid-turn would lose it silently; the app notes the turn when it starts instead, and
 *  says so on the next start if the note is still there. */
object Resumption {
    const val INTERRUPTED = "The last request was interrupted before it finished — Otto was closed while it ran. Send it again to retry."
    /** The note for a turn started before its session had an id. */
    const val NEW_SESSION = "-"

    /** The session to reopen with [INTERRUPTED]: the noted one ("" for a fresh session), or null when
     *  there is no note or the chat already shows a session (the process did not die). */
    fun interrupted(noted: String?, current: String?): String? {
        if (noted.isNullOrBlank() || !current.isNullOrBlank()) return null
        return if (noted == NEW_SESSION) "" else noted
    }
}

/**
 * Android 13+ greys out the accessibility switch of an app installed outside a session-based installer
 * (a browser download, a file manager) until App info → ⋮ → Allow restricted settings is tapped (#17).
 * Nothing says the switch was greyed, so the hint shows when it may have been: the person went to
 * Accessibility settings and came back with the service still off, on an app no store installed.
 */
object RestrictedSettings {
    const val MIN_SDK = 33
    const val TEXT = "If Otto's switch was greyed out, Android is blocking it because Otto was installed outside a store: " +
        "open App info, tap ⋮ → Allow restricted settings, then turn Otto on."
    /** Installers that install through a session, which Android does not restrict. */
    val STORES = setOf(
        "com.android.vending", "com.sec.android.app.samsungapps", "com.amazon.venezia", "com.huawei.appmarket",
        "org.fdroid.fdroid", "org.fdroid.basic", "com.looker.droidify", "com.machiav3lli.fdroid",
        "com.aurora.store", "dev.imranr.obtainium", "com.google.android.apps.nbu.files",
    )

    fun likely(sdk: Int, installer: String?, triedSettings: Boolean, serviceOn: Boolean): Boolean =
        sdk >= MIN_SDK && triedSettings && !serviceOn && installer !in STORES
}
