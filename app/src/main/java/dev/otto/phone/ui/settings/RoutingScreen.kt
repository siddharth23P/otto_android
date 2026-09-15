package dev.otto.phone.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otto.phone.protocol.RouteRow
import dev.otto.phone.state.Load
import dev.otto.phone.ui.Models
import dev.otto.phone.ui.components.AsyncState
import dev.otto.phone.ui.components.Eyebrow
import dev.otto.phone.ui.components.Hairline
import dev.otto.phone.ui.components.IconAction
import dev.otto.phone.ui.components.KvRow
import dev.otto.phone.ui.components.OttoIcons
import dev.otto.phone.ui.components.OttoToastHost
import dev.otto.phone.ui.components.Panel
import dev.otto.phone.ui.theme.OttoShapes
import dev.otto.phone.ui.theme.OttoTheme

/** Which model does each task: pins in clay, defaults muted; tap a task for its choices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingScreen(m: Models) {
    val routing by m.routing.state.collectAsStateWithLifecycle()
    val c = OttoTheme.colors
    var sheetTask by rememberSaveable { mutableStateOf<String?>(null) }
    val toasts = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(Unit) { m.routing.load() }
    LaunchedEffect(Unit) { m.routing.toasts.collect { toasts.currentSnackbarData?.dismiss(); toasts.showSnackbar(it) } }

    val load: Load<List<RouteRow>> = when {
        routing.unsupported -> Load.Unsupported
        !routing.loaded && routing.error != null -> Load.Failed(routing.error ?: Load.COULD_NOT_LOAD)
        !routing.loaded -> Load.Loading
        else -> Load.Ready(routing.routes)
    }
    androidx.compose.foundation.layout.Box {
        SettingsPage("Routing", onBack = { m.app.back() }) {
            routing.disabledReason?.takeIf { routing.loaded && !routing.unsupported }?.let {
                Text(it, style = OttoTheme.type.meta.copy(color = c.warn), modifier = Modifier.semantics { stateDescription = it })
            }
            AsyncState(load, onRetry = { m.routing.load() }, isEmpty = { it.isEmpty() }, emptyTitle = "No tasks to route") { routes ->
                Panel("tasks") {
                    routes.forEachIndexed { i, row ->
                        if (i > 0) Hairline(color = c.lineSoft)
                        RouteItem(
                            row, editable = routing.editable, reason = routing.disabledReason,
                            onOpen = { sheetTask = row.task; m.routing.loadOptions(row.task) },
                            onClear = { m.routing.clear(row.task) },
                        )
                    }
                }
            }
            if (routing.models.isNotEmpty()) Panel("models") {
                routing.models.forEachIndexed { i, model ->
                    val detail = listOfNotNull(model.provider.ifBlank { null }, model.contextWindow?.let { "${dev.otto.phone.state.Format.thousands(it.toLong())} ctx" }).joinToString(" · ")
                    KvRow(model.displayName?.ifBlank { null } ?: model.id.ifBlank { model.spec }, detail.ifBlank { model.spec }, first = i == 0)
                }
            }
        }
        OttoToastHost(toasts, Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp))
    }

    sheetTask?.let { task ->
        val row = routing.routes.firstOrNull { it.task == task }
        ModalBottomSheet(
            onDismissRequest = { sheetTask = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.bg2, contentColor = c.ink, tonalElevation = 0.dp, scrimColor = c.scrim, shape = OttoShapes.sheet,
        ) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 21.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow(task)
                row?.let { SettingsText.routeNote(it)?.let { note -> Text(note, style = OttoTheme.type.meta) } }
                val options = routing.options[task]
                if (options == null) SkeletonLines()
                else LazyColumn(Modifier.testTag("routing_options")) {
                    items(options, key = { it.spec }) { option ->
                        val chosen = (option.spec.isBlank() && row?.pin.isNullOrBlank()) || option.spec == row?.pin
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = chosen, enabled = routing.editable, role = Role.RadioButton) {
                                    m.routing.pin(task, option.spec); sheetTask = null
                                }
                                .semantics { if (!routing.editable) routing.disabledReason?.let { stateDescription = it } }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(option.label.ifBlank { option.spec }, style = OttoTheme.type.ui.copy(fontSize = 15.sp, color = if (chosen) c.accent else c.ink))
                                if (option.spec.isNotBlank() && option.spec != option.label) Text(option.spec, style = OttoTheme.type.meta)
                            }
                            if (chosen) androidx.compose.material3.Icon(OttoIcons.Check, contentDescription = "chosen", tint = c.accent, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteItem(row: RouteRow, editable: Boolean, reason: String?, onOpen: () -> Unit, onClear: () -> Unit) {
    val c = OttoTheme.colors
    val pinned = !row.pin.isNullOrBlank()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).heightIn(min = 48.dp)
                .clickable(enabled = editable, role = Role.Button, onClickLabel = "choose the model for ${row.task}", onClick = onOpen)
                .semantics { if (!editable && reason != null) stateDescription = reason }
                .padding(vertical = 9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.task, style = OttoTheme.type.ui.copy(fontSize = 15.sp))
                row.boundProvider?.let { p ->
                    Text(
                        "$p only", style = OttoTheme.type.meta.copy(fontSize = 11.sp, color = c.warn),
                        modifier = Modifier.border(1.dp, c.warn.copy(alpha = 0.4f), OttoShapes.r1).padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            }
            Text(SettingsText.routeModel(row), style = OttoTheme.type.user.copy(fontSize = 13.sp, lineHeight = 18.sp, color = if (pinned) c.accent else c.dim))
            SettingsText.routeNote(row)?.let { Text(it, style = OttoTheme.type.meta) }
        }
        if (pinned) IconAction(OttoIcons.Close, "clear the pin for ${row.task}", onClear, enabled = editable, disabledReason = reason)
    }
}
