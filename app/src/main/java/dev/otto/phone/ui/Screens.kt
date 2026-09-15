package dev.otto.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// The screens as they were, drawn from the new ViewModels until each is replaced.

@Composable
fun DisclosureScreen(onAccept: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Before Otto can help", style = MaterialTheme.typography.headlineSmall)
        Text("Otto uses Android's accessibility service to carry out what you ask for in this chat. While a request runs it:")
        Text("• reads the screen of the app in front (its text, buttons and fields);")
        Text("• taps, types and scrolls on your behalf;")
        Text("• sends what it reads to the AI provider you configured, only for the current request.")
        Text("It never acts inside payment or banking apps, never enters PINs, OTPs, CVVs or passwords, and stops at any payment step for you to complete. Nothing is read when no request is running.")
        Text("Accessibility data is used only to perform your requests and is not shared with anyone else.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) { Text("I understand, continue") }
    }
}

@Composable
fun SettingsScreen(m: Models) {
    val app by m.app.state.collectAsStateWithLifecycle()
    val sessions by m.sessions.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { m.sessions.load() }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("otto ${app.ottoVersion} · api ${app.apiVersion} · ${app.transportName}", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Otto may act on the phone", Modifier.weight(1f))
            Switch(checked = app.allowedToAct, onCheckedChange = { m.app.setAllowedToAct(it) })
        }
        OutlinedButton(onClick = { m.app.openAccessibilitySettings() }) { Text("Open accessibility settings") }
        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Where Otto runs", style = MaterialTheme.typography.titleMedium)
            Text(if (app.transportName == "embedded") "On this phone" else "otto serve at ${app.serveUrl}")
            var pairing by remember { mutableStateOf("") }
            OutlinedTextField(pairing, { pairing = it }, label = { Text("ws://host:8765/#token") }, modifier = Modifier.fillMaxWidth())
            app.pairingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { m.app.pairServe(pairing) }) { Text("Pair with otto serve") }
                OutlinedButton(onClick = { m.app.useEmbedded() }) { Text("Use this phone") }
            }
        } }
        app.status?.maskedKeys?.forEach { (name, shown) ->
            var value by remember(name) { mutableStateOf("") }
            Text("$name · $shown", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value, { value = it }, modifier = Modifier.weight(1f), singleLine = true, visualTransformation = PasswordVisualTransformation())
                Button(onClick = { m.setup.setKey(name, value) { m.app.refreshStatus() }; value = "" }) { Text("Save") }
            }
        }
        Card { Column(Modifier.padding(12.dp)) {
            Text("Sessions", style = MaterialTheme.typography.titleMedium)
            sessions.rows.forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${row.title.ifBlank { "(untitled)" }} · ${row.turns}", Modifier.weight(1f))
                TextButton(onClick = { m.chat.openSession(row.id); m.app.home() }) { Text("Resume") }
                TextButton(onClick = { m.sessions.tapDelete(row.id) }) { Text(if (sessions.deleteArmed(row.id, System.currentTimeMillis())) "sure?" else "Delete") }
            } }
        } }
        Card { Column(Modifier.padding(12.dp)) {
            Text("Guard log", style = MaterialTheme.typography.titleMedium)
            if (app.guardLog.isEmpty()) Text("Nothing refused yet.", style = MaterialTheme.typography.bodySmall)
            app.guardLog.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        } }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { m.app.back() }) { Text("Back") }
    }
}
