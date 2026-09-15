package dev.otto.phone.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge on every version, not only where Android 15+ enforces it, so the insets OttoUi
        // pads for -- the keyboard's among them -- arrive the same way everywhere.
        enableEdgeToEdge()
        setContent { MaterialTheme { Surface { OttoUi(viewModel) } } }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshService()
    }
}
