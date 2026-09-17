package dev.otto.phone.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otto.phone.state.Route
import dev.otto.phone.ui.chat.ChatScreen
import dev.otto.phone.ui.document.DocumentScreen
import dev.otto.phone.ui.memory.MemoryScreen
import dev.otto.phone.ui.memory.NoteScreen
import dev.otto.phone.ui.settings.DoctorScreen
import dev.otto.phone.ui.settings.KeysScreen
import dev.otto.phone.ui.settings.RoutingScreen
import dev.otto.phone.ui.settings.SettingsScreen
import dev.otto.phone.ui.settings.UsageScreen
import dev.otto.phone.ui.theme.OttoTheme

/** The ViewModels every screen draws from, passed down as one handle. */
class Models(
    val app: AppViewModel,
    val chat: ChatViewModel,
    val sessions: SessionsViewModel,
    val setup: SetupViewModel,
    val routing: RoutingViewModel,
    val lessons: LessonsViewModel,
)

/** Every screen is laid out inside the safe area, keyboard included. The window draws edge to edge
 *  (Android 15+ enforces it for targetSdk 35), so adjustResize no longer shrinks it for the keyboard:
 *  the insets are the only thing that keeps a text field above the keyboard and off the system bars. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OttoRoot(m: Models) {
    val app by m.app.state.collectAsStateWithLifecycle()
    val chat by m.chat.state.collectAsStateWithLifecycle()

    // The glue between ViewModels: each owns its own state, the root tells them what the others did.
    LaunchedEffect(app.epoch) { if (app.epoch > 0) m.chat.onConnected() }
    LaunchedEffect(chat.sessionId) { m.sessions.setCurrent(chat.sessionId) }
    LaunchedEffect(chat.running) {
        m.routing.setTurnRunning(chat.running)
        m.app.refreshService()
    }
    LaunchedEffect(Unit) { m.sessions.imported.collect { m.chat.openSession(it) } }
    // Android 13+: the notification a running turn shows (with its Stop) needs the permission, asked once,
    // after the disclosure and before any turn.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { m.app.notificationsAsked() }
    LaunchedEffect(app.disclosureAccepted, app.askNotifications) {
        if (app.disclosureAccepted == true && app.askNotifications) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    BackHandler(enabled = app.stack.canPop) { m.app.back() }

    Box(
        Modifier.fillMaxSize()
            .background(OttoTheme.colors.bg)
            .semantics { testTagsAsResourceId = true }
            .safeDrawingPadding(),
    ) {
        when (app.disclosureAccepted) {
            null -> Unit
            false -> DisclosureScreen(onAccept = { m.app.acceptDisclosure() })
            true -> when (val top = app.stack.top) {
                Route.Chat -> ChatScreen(m)
                Route.Settings -> SettingsScreen(m)
                Route.Keys -> KeysScreen(m)
                Route.Doctor -> DoctorScreen(m)
                Route.Usage -> UsageScreen(m)
                Route.Routing -> RoutingScreen(m)
                is Route.Document -> DocumentScreen(top.document, onBack = { m.app.back() })
                Route.Memory -> MemoryScreen(m)
                is Route.Note -> NoteScreen(m, top.packageName)
            }
        }
    }
}
