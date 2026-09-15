package dev.otto.phone.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otto.phone.ui.Models
import dev.otto.phone.ui.components.OttoButton
import dev.otto.phone.ui.components.Wordmark
import dev.otto.phone.ui.theme.OttoTheme

/** The sessions drawer: for now the list and a new session. */
@Composable
fun SessionsDrawer(m: Models, onClose: () -> Unit) {
    val sessions by m.sessions.state.collectAsStateWithLifecycle()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Wordmark()
        OttoButton("new session", { m.chat.newSession(); onClose() })
        LazyColumn {
            items(sessions.rows, key = { it.id }) { row ->
                Text(
                    row.title.ifBlank { "(untitled)" }, style = OttoTheme.type.ui,
                    modifier = Modifier.fillMaxWidth().clickable { m.chat.openSession(row.id); onClose() }.padding(vertical = 13.dp),
                )
            }
        }
    }
}
