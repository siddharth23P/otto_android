package dev.otto.phone.ui.settings

import androidx.compose.runtime.remember
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.pm.PackageManager
import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otto.phone.state.Route
import dev.otto.phone.ui.Link
import dev.otto.phone.ui.Models
import dev.otto.phone.ui.components.ButtonKind
import dev.otto.phone.ui.components.ControlRow
import dev.otto.phone.ui.components.KvRow
import dev.otto.phone.ui.components.LinkRow
import dev.otto.phone.ui.components.OttoButton
import dev.otto.phone.ui.components.OttoSwitch
import dev.otto.phone.ui.components.Panel
import dev.otto.phone.ui.components.ScreenBar
import dev.otto.phone.ui.components.Segmented
import dev.otto.phone.ui.theme.OttoShapes
import dev.otto.phone.ui.theme.OttoTheme
import dev.otto.phone.ui.theme.ThemeChoice

/** The settings hub: where otto runs, how the app looks, whether Otto may act, what the guard
 *  refused, and the screens for keys, doctor, usage, routing and memory. */
@Composable
fun SettingsScreen(m: Models) {
    val app by m.app.state.collectAsStateWithLifecycle()
    val c = OttoTheme.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        ScreenBar("Settings", onBack = { m.app.back() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Panel("where otto runs") {
                KvRow("transport", SettingsText.transport(app.transportName, app.serveUrl), first = true)
                KvRow("version", SettingsText.version(app.ottoVersion, app.apiVersion, app.capabilities.protocol))
                val (status, color) = when (val link = app.link) {
                    Link.Connecting -> "connecting…" to c.dim
                    Link.Reconnecting -> "reconnecting…" to c.warn
                    Link.Ready -> "ready" to c.ok
                    Link.NeedsKey -> "needs a key" to c.warn
                    is Link.Failed -> link.message to c.bad
                }
                KvRow("status", status, valueColor = color)
                var pairing by rememberSaveable { mutableStateOf("") }
                MonoField(
                    value = pairing, onValueChange = { pairing = it }, placeholder = "ws://127.0.0.1:8765/#token",
                    label = "pairing line from otto serve", modifier = Modifier.padding(top = 8.dp).testTag("settings_pairing"),
                )
                app.pairingError?.let { Text(it, style = OttoTheme.type.meta.copy(color = c.bad), modifier = Modifier.padding(top = 5.dp)) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OttoButton("Pair", { m.app.pairServe(pairing) }, Modifier.testTag("settings_pair"), enabled = pairing.isNotBlank())
                    val context = LocalContext.current
                    OttoButton("Scan QR", { QrPairing.scan(context, onLine = { m.app.pairServe(it) }, onProblem = { m.app.pairingProblem(it) }) },
                        Modifier.testTag("settings_scan"), kind = ButtonKind.OUTLINED)
                    OttoButton("Use this phone", { m.app.useEmbedded() }, Modifier.testTag("settings_embedded"), kind = ButtonKind.OUTLINED)
                }
                Text(
                    "otto serve listens on 127.0.0.1. Over USB, run adb reverse tcp:8765 tcp:8765 and pair with the line it printed " +
                        "(otto serve --qr prints it as a code to scan). Plain ws:// is allowed only to localhost and .local/.lan/.home " +
                        "names; anything else, a Tailscale address included, needs wss://.",
                    style = OttoTheme.type.meta, modifier = Modifier.padding(top = 8.dp),
                )
            }
            Panel("appearance") {
                ControlRow("Theme") {
                    Segmented(
                        options = listOf(ThemeChoice.SYSTEM to "System", ThemeChoice.STUDIO to "Studio", ThemeChoice.PAPER to "Paper"),
                        selected = app.theme, onSelect = { m.app.setTheme(it) }, modifier = Modifier.testTag("settings_theme"),
                    )
                }
            }
            Panel("the phone") {
                ControlRow("Otto may act on the phone", note = if (app.allowedToAct) "taps, types and scrolls while a request runs" else "Otto answers but never touches the phone") {
                    OttoSwitch(app.allowedToAct, { m.app.setAllowedToAct(it) }, "Otto may act on the phone", Modifier.testTag("settings_act"))
                }
                LinkRow("Accessibility service", { m.app.openAccessibilitySettings() }, value = if (app.serviceEnabled) "on" else "off")
                // Phone actions that need a permission of their own (actions/PhoneActions.kt).
                val context = LocalContext.current
                var contacts by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
                val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    contacts = granted
                    if (!granted) m.app.openAppInfo()
                }
                LinkRow("Contact lookup", { if (!contacts) askContacts.launch(Manifest.permission.READ_CONTACTS) else m.app.openAppInfo() },
                    Modifier.testTag("settings_contacts"), value = if (contacts) "allowed" else "off")
                LinkRow("Do Not Disturb access", {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }, Modifier.testTag("settings_dnd"),
                    value = if (context.getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted) "allowed" else "off")
            }
            Panel("otto") {
                LinkRow("Keys", { m.app.push(Route.Keys) }, Modifier.testTag("settings_keys"), first = true)
                LinkRow("Doctor", { m.app.push(Route.Doctor) }, Modifier.testTag("settings_doctor"))
                LinkRow("Usage", { m.app.push(Route.Usage) }, Modifier.testTag("settings_usage"))
                LinkRow("Routing", { m.app.push(Route.Routing) }, Modifier.testTag("settings_routing"))
                LinkRow("Memory", { m.app.push(Route.Memory) }, Modifier.testTag("settings_memory"))
            }
            Panel("guard log") {
                if (app.guardLog.isEmpty()) Text("Nothing refused yet.", style = OttoTheme.type.ui.copy(fontSize = 14.sp, color = c.dim))
                app.guardLog.asReversed().forEach { line ->
                    Text(line, style = OttoTheme.type.trace.copy(color = c.dim, lineHeight = 18.sp), modifier = Modifier.padding(vertical = 3.dp))
                }
            }
        }
    }
}

/** A mono field with a 1 dp line and no fill, as the web's settings inputs are. */
@Composable
fun MonoField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    style: TextStyle = OttoTheme.type.composer,
) {
    val c = OttoTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(c.ink),
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().border(1.dp, c.line, OttoShapes.r1).background(Color.Transparent).padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = c.faint), maxLines = 1)
                inner()
            }
        },
    )
}
