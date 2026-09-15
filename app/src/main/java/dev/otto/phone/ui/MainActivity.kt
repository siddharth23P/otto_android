package dev.otto.phone.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otto.phone.OttoApp
import dev.otto.phone.ui.theme.OttoTheme
import dev.otto.phone.ui.theme.ThemeChoice

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge on every version, not only where Android 15+ enforces it, so the insets OttoUi
        // pads for -- the keyboard's among them -- arrive the same way everywhere.
        enableEdgeToEdge()
        val prefs = (application as OttoApp).prefs
        setContent {
            val theme by prefs.theme.collectAsStateWithLifecycle(initialValue = ThemeChoice.SYSTEM.pref)
            OttoTheme(ThemeChoice.fromPref(theme)) {
                Surface(color = MaterialTheme.colorScheme.background) { OttoUi(viewModel) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshService()
    }
}
