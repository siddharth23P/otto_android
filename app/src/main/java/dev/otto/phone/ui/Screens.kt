package dev.otto.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun OttoUi(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (state.screen) {
        Screen.DISCLOSURE -> DisclosureScreen(onAccept = vm::acceptDisclosure)
        Screen.SETUP -> SetupScreen(state, vm)
        Screen.CHAT -> ChatScreen(state, vm)
        Screen.SESSIONS -> SessionsScreen(state, vm)
        Screen.SETTINGS -> SettingsScreen(state, vm)
    }
}

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
fun SetupScreen(state: UiState, vm: ChatViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Set up Otto", style = MaterialTheme.typography.headlineSmall)
        Text(state.versionLine, style = MaterialTheme.typography.bodySmall)
        if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1. The hands", style = MaterialTheme.typography.titleMedium)
            Text(if (state.serviceEnabled) "Otto's accessibility service is on." else "Turn on \"Otto phone helper\" under Accessibility. On Android 13+, a sideloaded app first needs App info → ⋮ → Allow restricted settings.")
            OutlinedButton(onClick = vm::openAccessibilitySettings) { Text("Open accessibility settings") }
        } }
        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("2. The brain", style = MaterialTheme.typography.titleMedium)
            if (state.transportName == "embedded" && state.runtimeAvailable) {
                Text("Otto runs on this phone. INCEPTION_API_KEY is required; a GEMINI_API_KEY adds screenshots and better memory.")
                for (name in listOf("INCEPTION_API_KEY", "GEMINI_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY")) {
                    KeyRow(name, state.keys[name] ?: "not set") { vm.setKey(name, it) }
                }
            } else {
                Text("Pair with otto serve: on a computer run `uv run otto serve --host 0.0.0.0` and paste the ws://…#token line it prints.")
                var pairing by remember { mutableStateOf(state.serveUrl) }
                OutlinedTextField(pairing, { pairing = it }, label = { Text("ws://host:8765/#token") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.pairServe(pairing) }) { Text("Pair") }
            }
        } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.show(Screen.CHAT) }, enabled = state.ready) { Text("Start chatting") }
            TextButton(onClick = { vm.show(Screen.SETTINGS) }) { Text("Settings") }
        }
    }
}

@Composable
private fun KeyRow(name: String, shown: String, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    Column {
        Text("$name · $shown", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value, { value = it }, modifier = Modifier.weight(1f), singleLine = true,
                visualTransformation = PasswordVisualTransformation(), label = { Text("paste key") })
            Button(onClick = { onSave(value); value = "" }) { Text("Save") }
        }
    }
}

@Composable
fun ChatScreen(state: UiState, vm: ChatViewModel) {
    var input by remember { mutableStateOf("") }
    Scaffold(topBar = {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(state.sessionTitle.ifBlank { "Otto" }, style = MaterialTheme.typography.titleMedium)
            Row { TextButton(onClick = { vm.show(Screen.SESSIONS) }) { Text("Sessions") }; TextButton(onClick = { vm.show(Screen.SETTINGS) }) { Text("Settings") } }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            if (!state.serviceEnabled) Text("The accessibility service is off; Otto can answer but not act.", color = MaterialTheme.colorScheme.error)
            if (state.handedOver) Card { Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Otto stopped: this step is yours (a payment, a PIN or a protected screen).", Modifier.weight(1f))
                TextButton(onClick = vm::resumeAfterHandover) { Text("Resume") }
            } }
            if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.messages) { m -> Card { Column(Modifier.padding(12.dp)) { Text(m.role, style = MaterialTheme.typography.labelSmall); Text(m.text) } } }
                if (state.board.isNotEmpty()) item { Card { Column(Modifier.padding(12.dp)) { state.board.takeLast(6).forEach { Text(it, style = MaterialTheme.typography.bodySmall) } } } }
            }
            if (state.running) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(state.status.ifBlank { "working" }, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = vm::stop) { Text("Stop") }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f), label = { Text("Ask Otto") }, enabled = !state.running)
                Button(onClick = { vm.send(input); input = "" }, enabled = !state.running && input.isNotBlank()) { Text("Send") }
            }
        }
    }
    state.ask?.let { ask ->
        var free by remember(ask.threadId) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { }, title = { Text("Otto is asking") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ask.question)
                ask.choices.forEach { choice -> OutlinedButton(onClick = { vm.answer(choice) }, modifier = Modifier.fillMaxWidth()) { Text(choice) } }
                OutlinedTextField(free, { free = it }, label = { Text("or type an answer") }, modifier = Modifier.fillMaxWidth())
            } },
            confirmButton = { Button(onClick = { if (free.isNotBlank()) vm.answer(free) }) { Text("Answer") } },
            dismissButton = { TextButton(onClick = vm::stop) { Text("Stop") } })
    }
}

@Composable
fun SessionsScreen(state: UiState, vm: ChatViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.openSession(null) }) { Text("New session") }
            TextButton(onClick = { vm.show(Screen.CHAT) }) { Text("Back") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.sessions) { row -> Card { Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text(row.title); Text("${row.turns} turns · ${row.age}", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { vm.openSession(row.id) }) { Text("Resume") }
                TextButton(onClick = { vm.deleteSession(row.id) }) { Text("Delete") }
            } } }
        }
    }
}

@Composable
fun SettingsScreen(state: UiState, vm: ChatViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text(state.versionLine, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Otto may act on the phone", Modifier.weight(1f))
            Switch(checked = state.allowedToAct, onCheckedChange = vm::setAllowedToAct)
        }
        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Where Otto runs", style = MaterialTheme.typography.titleMedium)
            Text(if (state.transportName == "embedded") "On this phone" else "otto serve at ${state.serveUrl}")
            var pairing by remember { mutableStateOf("") }
            OutlinedTextField(pairing, { pairing = it }, label = { Text("ws://host:8765/#token") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.pairServe(pairing) }) { Text("Pair with otto serve") }
                OutlinedButton(onClick = vm::useEmbedded) { Text("Use this phone") }
            }
        } }
        Card { Column(Modifier.padding(12.dp)) {
            Text("Guard log", style = MaterialTheme.typography.titleMedium)
            if (state.guardLog.isEmpty()) Text("Nothing refused yet.", style = MaterialTheme.typography.bodySmall)
            state.guardLog.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        } }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { vm.show(Screen.CHAT) }) { Text("Back") }
    }
}
