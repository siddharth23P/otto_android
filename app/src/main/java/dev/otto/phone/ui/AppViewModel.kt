package dev.otto.phone.ui

import android.app.Application
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.otto.phone.OttoApp
import dev.otto.phone.access.OttoAccessibilityService
import dev.otto.phone.protocol.Capabilities
import dev.otto.phone.protocol.Hello
import dev.otto.phone.protocol.Reply
import dev.otto.phone.protocol.SetupStatus
import dev.otto.phone.state.BackStack
import dev.otto.phone.state.RestrictedSettings
import dev.otto.phone.state.Route
import dev.otto.phone.state.problem
import dev.otto.phone.log.OttoLog
import dev.otto.phone.transport.EventBus
import dev.otto.phone.transport.ServeProtocol
import dev.otto.phone.ui.theme.ThemeChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the app stands with otto: still reaching it, reached and ready, reached but missing a key,
 *  or not reached at all (in the transport's own words). */
sealed interface Link {
    data object Connecting : Link
    data object Ready : Link
    /** Reached, but otto can't answer yet: no usable key. */
    data object NeedsKey : Link
    data class Failed(val message: String) : Link
}

data class AppState(
    /** Null until Prefs has answered, so the disclosure never flashes for someone who accepted it. */
    val disclosureAccepted: Boolean? = null,
    val stack: BackStack = BackStack(),
    val link: Link = Link.Connecting,
    /** Bumped on every successful connect, so the chat opens a session on the new transport. */
    val epoch: Int = 0,
    val transportName: String = "embedded",
    val capabilities: Capabilities = Capabilities.V1,
    val ottoVersion: String = "",
    val apiVersion: Int = 0,
    val serveUrl: String = "",
    val status: SetupStatus? = null,
    val pairingError: String? = null,
    val serviceEnabled: Boolean = false,
    val handedOver: Boolean = false,
    val allowedToAct: Boolean = true,
    val guardLog: List<String> = emptyList(),
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    /** Back from Accessibility settings with the service still off, on an app no store installed. */
    val restrictedHint: Boolean = false,
    /** Android 13+: ask once for the notification a running turn shows. */
    val askNotifications: Boolean = false,
)

/** The app around the screens: the disclosure, the route stack, the one connection, the phone's
 *  accessibility service and its guard, and the preferences a person sets in Settings. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = (app as OttoApp).prefs
    private val connection = (app as OttoApp).connection
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state
    /** Whether the person has been sent to Accessibility settings from here. */
    private var triedAccessibility = false

    init {
        viewModelScope.launch {
            val accepted = prefs.disclosureAccepted()
            val allowed = prefs.allowedToAct()
            _state.update { it.copy(disclosureAccepted = accepted, transportName = prefs.transport(), serveUrl = prefs.serveUrl(), allowedToAct = allowed,
                askNotifications = Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications() && !notificationsGranted()) }
            refreshService()
            if (accepted) connect()
        }
        viewModelScope.launch { prefs.theme.collect { pref -> _state.update { it.copy(theme = ThemeChoice.fromPref(pref)) } } }
        // After every turn event the guard may have handed over or noted something.
        viewModelScope.launch { EventBus.events.collect { refreshService() } }
    }

    // -- navigation ---------------------------------------------------------------------------------

    fun push(route: Route) = _state.update { it.copy(stack = it.stack.push(route)) }
    fun back() = _state.update { it.copy(stack = it.stack.pop()) }
    fun home() = _state.update { it.copy(stack = it.stack.home()) }

    // -- disclosure, service, guard -----------------------------------------------------------------

    fun acceptDisclosure() = viewModelScope.launch {
        prefs.setDisclosureAccepted(true)
        _state.update { it.copy(disclosureAccepted = true) }
        connect()
    }

    fun refreshService() {
        val service = OttoAccessibilityService.instance
        val guard = service?.guard
        guard?.allowedToAct = _state.value.allowedToAct
        val hint = RestrictedSettings.likely(Build.VERSION.SDK_INT, installer(), triedAccessibility, service != null)
        _state.update {
            it.copy(serviceEnabled = service != null, handedOver = guard?.handedOver ?: false, restrictedHint = hint,
                guardLog = guard?.log?.toList()?.takeLast(GUARD_LOG_SHOWN) ?: it.guardLog)
        }
    }

    fun openAccessibilitySettings() {
        triedAccessibility = true
        getApplication<Application>().startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Otto's App info page, where Android 13+ keeps "Allow restricted settings". */
    fun openAppInfo() {
        val app = getApplication<Application>()
        app.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun installer(): String? = runCatching {
        val app = getApplication<Application>()
        app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
    }.getOrNull()

    private fun notificationsGranted(): Boolean =
        getApplication<Application>().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The notification permission was asked for (whatever the answer): not again. */
    fun notificationsAsked() = viewModelScope.launch {
        prefs.setAskedNotifications()
        _state.update { it.copy(askNotifications = false) }
    }

    fun resumeAfterHandover() {
        OttoAccessibilityService.instance?.guard?.handedOver = false
        _state.update { it.copy(handedOver = false) }
    }

    fun setAllowedToAct(value: Boolean) = viewModelScope.launch {
        prefs.setAllowedToAct(value)
        OttoAccessibilityService.instance?.guard?.allowedToAct = value
        _state.update { it.copy(allowedToAct = value) }
    }

    fun setTheme(choice: ThemeChoice) = viewModelScope.launch { prefs.setTheme(choice.pref) }

    // -- connection ---------------------------------------------------------------------------------

    fun connect() = viewModelScope.launch {
        _state.update { it.copy(link = Link.Connecting) }
        OttoLog.i(LINK, "connecting")
        val (chosen, hello) = connection.connect()
        val name = chosen.name
        if (hello !is Reply.Ok) {
            val message = if (name == "embedded" && hello is Reply.Err && hello.code == "unavailable")
                "the embedded runtime is not in this build — pair with otto serve in Settings."
            else hello.problem("couldn't reach otto") ?: ""
            OttoLog.w(LINK, "$name failed: $message")
            _state.update { it.copy(transportName = name, link = Link.Failed(message), status = null, capabilities = chosen.capabilities) }
            return@launch
        }
        OttoLog.i(LINK, "$name connected: otto ${hello.value.ottoVersion}, protocol ${hello.value.protocolVersion}")
        applyHello(name, hello.value, chosen.capabilities)
        refreshStatus()
    }

    /** Asks otto whether it is ready again (after a key was set, say). */
    fun refreshStatus() = viewModelScope.launch {
        val t = connection.current ?: return@launch
        when (val status = t.setupStatus()) {
            is Reply.Ok -> _state.update {
                val ready = status.value.ready
                OttoLog.i(LINK, if (ready) "ready" else "needs a key")
                it.copy(status = status.value, link = if (ready) Link.Ready else Link.NeedsKey,
                    epoch = if (ready && it.link != Link.Ready) it.epoch + 1 else it.epoch,
                    ottoVersion = status.value.version.otto.ifBlank { it.ottoVersion },
                    apiVersion = if (status.value.version.api != 0) status.value.version.api else it.apiVersion)
            }
            // An otto serve from before the setup op: keys live on that machine, and a hello is ready enough.
            Reply.Unsupported -> _state.update { it.copy(status = null, link = Link.Ready, epoch = if (it.link != Link.Ready) it.epoch + 1 else it.epoch) }
            is Reply.Err -> _state.update {
                OttoLog.w(LINK, "status failed: ${status.code}: ${status.message}")
                it.copy(link = Link.Failed(status.message.ifBlank { "otto couldn't say whether it is ready" }))
            }
        }
    }

    private fun applyHello(name: String, hello: Hello, capabilities: Capabilities) = _state.update {
        it.copy(transportName = name, capabilities = capabilities, ottoVersion = hello.ottoVersion, apiVersion = hello.apiVersion, pairingError = null)
    }

    fun pairServe(pairing: String) = viewModelScope.launch {
        val parsed = ServeProtocol.parsePairing(pairing.trim())
        if (parsed == null) {
            _state.update { it.copy(pairingError = PAIRING_HINT) }
            return@launch
        }
        prefs.setServeUrl(parsed.first); prefs.setServeToken(parsed.second); prefs.setTransport("serve")
        _state.update { it.copy(serveUrl = parsed.first, transportName = "serve", pairingError = null) }
        connect()
    }

    fun useEmbedded() = viewModelScope.launch {
        prefs.setTransport("embedded")
        _state.update { it.copy(transportName = "embedded", pairingError = null) }
        connect()
    }

    companion object {
        const val GUARD_LOG_SHOWN = 20
        const val PAIRING_HINT = "paste the ws://host:port/#token line otto serve printed"
    }
}

private const val LINK = "OttoLink"
