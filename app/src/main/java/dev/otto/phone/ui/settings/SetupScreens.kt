package dev.otto.phone.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otto.phone.state.Board
import dev.otto.phone.state.FORBIDDEN_SETUP
import dev.otto.phone.state.Format
import dev.otto.phone.state.Load
import dev.otto.phone.transport.NEEDS_NEWER_OTTO
import dev.otto.phone.ui.Models
import dev.otto.phone.ui.chat.ChatText
import dev.otto.phone.ui.chat.DrawerText
import dev.otto.phone.ui.components.AsyncState
import dev.otto.phone.ui.components.ButtonKind
import dev.otto.phone.ui.components.KvRow
import dev.otto.phone.ui.components.OttoButton
import dev.otto.phone.ui.components.Panel
import dev.otto.phone.ui.components.ScreenBar
import dev.otto.phone.ui.theme.OttoShapes
import dev.otto.phone.ui.theme.OttoTheme
import dev.otto.phone.ui.theme.skeletonPulse

/** A pushed settings screen: back arrow, title, scrolling panels. */
@Composable
fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(OttoTheme.colors.bg)) {
        ScreenBar(title, onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
            content = content,
        )
    }
}

@Composable
private fun levelColor(level: Board.Level?) = when (level) {
    Board.Level.OK -> OttoTheme.colors.ok
    Board.Level.WARN -> OttoTheme.colors.warn
    Board.Level.BAD -> OttoTheme.colors.bad
    null -> OttoTheme.colors.faint
}

@Composable
fun KeysScreen(m: Models) {
    val setup by m.setup.state.collectAsStateWithLifecycle()
    val app by m.app.state.collectAsStateWithLifecycle()
    val c = OttoTheme.colors
    LaunchedEffect(Unit) { m.setup.loadStatus() }
    SettingsPage("Keys", onBack = { m.app.back() }) {
        AsyncState(setup.status, onRetry = { m.setup.loadStatus() }) { status ->
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                if (app.transportName == "serve" && !status.setupWrite) Text(FORBIDDEN_SETUP + ".", style = OttoTheme.type.ui.copy(fontSize = 14.sp, color = c.dim))
                SettingsText.vendors(status.vendorRows, status.maskedKeys).forEach { v ->
                    Panel(v.label.ifBlank { v.name }) {
                        val shown = SettingsText.keyValue(v, status.maskedKeys)
                        SettingsText.vendorNote(v.name)?.let { Text(it, style = OttoTheme.type.meta.copy(color = c.dim), modifier = Modifier.padding(bottom = 5.dp)) }
                        KvRow(v.keyVar, shown, first = true, valueColor = if (shown == SettingsText.NOT_SET) c.faint else c.ink)
                        var value by rememberSaveable(v.keyVar) { mutableStateOf("") }
                        MonoField(
                            value = value, onValueChange = { value = it }, placeholder = "paste a new key", label = "new ${v.keyVar}", secret = true,
                            modifier = Modifier.padding(top = 8.dp).testTag("key_field_${v.keyVar}"),
                        )
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OttoButton(
                                if (setup.saving == v.keyVar) "saving…" else "Save",
                                { m.setup.setKey(v.keyVar, value) { m.app.refreshStatus() }; value = "" },
                                Modifier.testTag("key_save_${v.keyVar}"),
                                enabled = value.isNotBlank() && setup.saving == null,
                                disabledReason = if (setup.saving != null) "saving a key" else "paste a key first",
                            )
                            if (v.name.isNotBlank()) OttoButton("Probe", { m.setup.probe(v.name) }, Modifier.testTag("key_probe_${v.name}"), kind = ButtonKind.OUTLINED,
                                enabled = setup.probes[v.name] != Load.Loading, disabledReason = "probing")
                        }
                        setup.keyMessage?.takeIf { it.first == v.keyVar }?.let { (_, message) ->
                            Text(message, style = OttoTheme.type.meta.copy(color = if (message.startsWith("saved")) c.ok else c.bad), modifier = Modifier.padding(top = 8.dp))
                        }
                        when (val probe = setup.probes[v.name]) {
                            null, Load.Idle -> Unit
                            Load.Loading -> Text("probing…", style = OttoTheme.type.meta, modifier = Modifier.padding(top = 8.dp))
                            is Load.Ready -> Text(SettingsText.probe(probe.value), style = OttoTheme.type.meta.copy(color = if (probe.value.ok) c.ok else c.bad), modifier = Modifier.padding(top = 8.dp))
                            is Load.Failed -> Text(probe.message, style = OttoTheme.type.meta.copy(color = c.bad), modifier = Modifier.padding(top = 8.dp))
                            Load.Unsupported -> Text(NEEDS_NEWER_OTTO, style = OttoTheme.type.meta, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DoctorScreen(m: Models) {
    val setup by m.setup.state.collectAsStateWithLifecycle()
    val c = OttoTheme.colors
    LaunchedEffect(Unit) { m.setup.loadDoctor() }
    SettingsPage("Doctor", onBack = { m.app.back() }) {
        AsyncState(setup.doctor, onRetry = { m.setup.loadDoctor() }, isEmpty = { it.providers.isEmpty() }, emptyTitle = "No providers configured", emptyBody = "Add a key under Keys, then run the doctor again.") { report ->
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Panel("providers") {
                    report.providers.forEachIndexed { i, p ->
                        val level = SettingsText.level(p.status)
                        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {
                            if (i > 0) dev.otto.phone.ui.components.Hairline(color = c.lineSoft)
                            Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(8.dp).clip(OttoShapes.pill).background(levelColor(level)))
                                Column(Modifier.weight(1f)) {
                                    Text(p.provider, style = OttoTheme.type.ui.copy(fontSize = 15.sp))
                                    Text(SettingsText.provider(p), style = OttoTheme.type.meta.copy(color = if (level == null) c.faint else levelColor(level)))
                                }
                            }
                        }
                    }
                }
                Panel("summary") {
                    KvRow("ready", if (report.ready) "yes" else "no", first = true, valueColor = if (report.ready) c.ok else c.bad)
                    report.requiredText.ifBlank { null }?.let { KvRow("needs", it) }
                    if (report.alsoConfigured.isNotEmpty()) KvRow("also configured", report.alsoConfigured.joinToString(", "))
                }
            }
        }
        OttoButton("run again", { m.setup.loadDoctor() }, Modifier.testTag("doctor_again"), kind = ButtonKind.OUTLINED, enabled = setup.doctor != Load.Loading, disabledReason = "running")
    }
}

@Composable
fun UsageScreen(m: Models) {
    val setup by m.setup.state.collectAsStateWithLifecycle()
    val chat by m.chat.state.collectAsStateWithLifecycle()
    val c = OttoTheme.colors
    LaunchedEffect(chat.sessionId) { m.setup.loadUsage(chat.sessionId) }
    SettingsPage("Usage", onBack = { m.app.back() }) {
        AsyncState(
            setup.usage, onRetry = { m.setup.loadUsage(chat.sessionId) },
            isEmpty = { it.usage.calls == 0 },
            emptyTitle = DrawerText.NO_USAGE,
            emptyBody = "Once you start a conversation, spend, calls, and per-model costs show up here.",
        ) { u ->
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Panel("this session") {
                    Text(Format.formatCost(u.usage.cost, DrawerText.DASH), style = OttoTheme.type.stat, modifier = Modifier.semantics { contentDescription = "spent ${Format.formatCost(u.usage.cost, "an unknown amount")}" })
                    Text(DrawerText.totalsLine(u.usage), style = OttoTheme.type.meta.copy(color = c.dim))
                    if (u.turnTokens.isNotEmpty()) Text(
                        Board.sparkline(u.turnTokens), style = OttoTheme.type.meta.copy(fontSize = 18.sp, color = c.acc3),
                        modifier = Modifier.padding(top = 8.dp).clearAndSetSemantics { contentDescription = DrawerText.sparkDescription(u.turnTokens) },
                    )
                    if (u.usage.cost != null) Text(DrawerText.ESTIMATE, style = OttoTheme.type.meta.copy(fontSize = 11.sp), modifier = Modifier.padding(top = 5.dp))
                }
                Panel("per model") {
                    u.usage.models.forEachIndexed { i, row -> KvRow(ChatText.model(row.model), DrawerText.usageLine(row), first = i == 0) }
                }
                u.turn?.let { turn ->
                    Panel("last turn") {
                        KvRow("tokens", Format.thousands(turn.tokens), first = true)
                        KvRow("model calls", turn.calls.toString())
                        KvRow("cost", Format.formatCost(turn.cost, DrawerText.DASH))
                    }
                }
            }
        }
    }
}

/** A few skeleton lines, for sheets that wait on a reply. */
@Composable
fun SkeletonLines(count: Int = 3) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(count) { Box(Modifier.fillMaxWidth().height(44.dp).skeletonPulse(OttoTheme.colors, OttoShapes.r2)) }
    }
}

