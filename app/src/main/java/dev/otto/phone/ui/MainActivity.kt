package dev.otto.phone.ui

import android.graphics.Color
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
            val models = remember { Models(app, chat, sessions, setup, routing, lessons) }
            OttoTheme(state.theme) { OttoRoot(models) }
        }
    }

    override fun onResume() {
        super.onResume()
        app.refreshService()
    }
}
