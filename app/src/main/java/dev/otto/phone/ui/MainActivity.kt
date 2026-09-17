package dev.otto.phone.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otto.phone.ui.theme.OttoTheme
import dev.otto.phone.ui.theme.ThemeChoice

class MainActivity : ComponentActivity() {
    private val app: AppViewModel by viewModels()
    private val chat: ChatViewModel by viewModels()
    private val sessions: SessionsViewModel by viewModels()
    private val setup: SetupViewModel by viewModels()
    private val routing: RoutingViewModel by viewModels()
    private val lessons: LessonsViewModel by viewModels()
    private val files: FilesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge on every version, not only where Android 15+ enforces it, so the insets OttoRoot
        // pads for -- the keyboard's among them -- arrive the same way everywhere.
        enableEdgeToEdge()
        setContent {
            val state by app.state.collectAsStateWithLifecycle()
            val dark = when (state.theme) {
                ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                ThemeChoice.STUDIO -> true
                ThemeChoice.PAPER -> false
            }
            // Status and navigation bar icons follow Studio or Paper, not the phone's own dark mode.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            val models = remember { Models(app, chat, sessions, setup, routing, lessons, files) }
            OttoTheme(state.theme) { OttoRoot(models) }
        }
        takeShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShared(intent)
    }

    /** Files shared into Otto from another app become attachments of the next message. */
    private fun takeShared(intent: Intent?) {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelable(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelables(Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) chat.attach(uris)
        // Shared text with no file goes into nothing: the composer is the person's.
        intent?.action = Intent.ACTION_MAIN
    }

    override fun onResume() {
        super.onResume()
        app.refreshService()
    }
}

private fun Intent.parcelable(key: String): Uri? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Uri::class.java)
    else @Suppress("DEPRECATION") (getParcelableExtra(key) as? Uri)

private fun Intent.parcelables(key: String): List<Uri> =
    if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
    else @Suppress("DEPRECATION") getParcelableArrayListExtra<Uri>(key).orEmpty()
