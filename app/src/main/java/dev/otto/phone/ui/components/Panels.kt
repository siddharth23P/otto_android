package dev.otto.phone.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.otto.phone.state.Armed
import dev.otto.phone.state.Load
import dev.otto.phone.ui.theme.MotionTokens
import dev.otto.phone.ui.theme.Motion
import dev.otto.phone.ui.theme.OttoShapes
import dev.otto.phone.ui.theme.OttoTheme
import dev.otto.phone.ui.theme.OttoType
import dev.otto.phone.ui.theme.skeletonPulse
import kotlinx.coroutines.delay

/** A settings panel: a flat card (ink at 4 %) with an inset hairline, an eyebrow, and its rows. */
@Composable
fun Panel(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = OttoTheme.colors
    Column(
        modifier.fillMaxWidth().clip(OttoShapes.r3).background(c.ink.copy(alpha = 0.04f)).border(1.dp, c.line, OttoShapes.r3)
            .padding(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 13.dp),
    ) {
        if (title != null) Eyebrow(title, Modifier.padding(bottom = 8.dp))
        content()
    }
}

/** `label — control`: a sans label on the left, whatever control on the right. */
@Composable
fun ControlRow(label: String, modifier: Modifier = Modifier, note: String? = null, control: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 13.dp)) {
            Text(label, style = OttoTheme.type.ui.copy(fontSize = 15.5.sp))
            if (note != null) Text(note, style = OttoTheme.type.meta)
        }
        control()
    }
}

/** `label — mono value`: a dim label, the value in mono ink; consecutive rows share a line-soft rule. */
@Composable
fun KvRow(label: String, value: String, modifier: Modifier = Modifier, first: Boolean = false, valueColor: androidx.compose.ui.graphics.Color? = null) {
    val c = OttoTheme.colors
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {
        if (!first) Hairline(color = c.lineSoft)
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = OttoTheme.type.ui.copy(fontSize = 15.sp, color = c.dim), modifier = Modifier.weight(1f).padding(end = 13.dp))
            Text(value, style = OttoTheme.type.user.copy(fontSize = 13.5.sp, color = valueColor ?: c.ink, lineHeight = 18.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A segmented control: mono uppercase segments; the chosen one has its label in clay on a faint
 *  ink fill, never a filled pill. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = OttoTheme.colors
    Row(
        modifier.clip(OttoShapes.r2).background(c.ink.copy(alpha = 0.03f)).border(1.dp, c.line, OttoShapes.r2).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.heightIn(min = 48.dp).testTag("segment_" + label.lowercase().replace(' ', '_')).clip(OttoShapes.r1)
                    .background(if (on) c.ink.copy(alpha = 0.06f) else androidx.compose.ui.graphics.Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, c.lineSoft, OttoShapes.r1) else Modifier)
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { onSelect(value) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    OttoType.caps(label),
                    style = OttoTheme.type.meta.copy(letterSpacing = 0.11.sp * 11.5f, color = if (on) c.accent else c.faint),
                    maxLines = 1,
                )
            }
        }
    }
}

/** The web's switch: 44 × 24, an inset line, a knob that turns clay and moves over when on. */
@Composable
fun OttoSwitch(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = OttoTheme.colors
    val reduced = OttoTheme.reducedMotion
    val x by animateDpAsState(if (checked) 23.dp else 3.dp, tween(if (reduced) 0 else MotionTokens.MID_MS, easing = Motion.Ease), label = "knob")
    val knob by animateColorAsState(if (checked) c.accent else c.faint, tween(if (reduced) 0 else MotionTokens.MID_MS), label = "knob-color")
    Box(
        modifier.size(48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 44.dp, height = 24.dp).clip(OttoShapes.pill).border(1.dp, if (checked) c.accent.copy(alpha = 0.55f) else c.line, OttoShapes.pill)) {
            Box(Modifier.offset(x = x, y = 3.dp).size(18.dp).clip(OttoShapes.pill).background(if (enabled) knob else knob.copy(alpha = 0.45f)))
        }
    }
}

/** A destructive action: the first tap says "sure?", a second within 3.5 s does it, and it lapses
 *  back on its own. The armed state lives here, so any list can use it. */
@Composable
fun TapTwiceButton(label: String, onConfirmed: () -> Unit, modifier: Modifier = Modifier, sure: String = "sure?", enabled: Boolean = true) {
    var armedAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(armedAt) { if (armedAt != null) { delay(Armed.WINDOW_MS); armedAt = null } }
    val armed = armedAt != null
    OttoButton(
        if (armed) sure else label,
        onClick = {
            val now = System.currentTimeMillis()
            if (armedAt?.let { Armed("x", it).holds("x", now) } == true) { armedAt = null; onConfirmed() } else armedAt = now
        },
        modifier = modifier.semantics { if (armed) stateDescription = "tap again to confirm" },
        kind = ButtonKind.DANGER, armed = armed, enabled = enabled,
    )
}

/** A loaded value as a screen shows it: a pulse while loading, "couldn't load" with Retry when it
 *  failed, "needs a newer otto" when the other end has no such request, empty copy when there is
 *  nothing, and the content otherwise. */
@Composable
fun <T> AsyncState(
    load: Load<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    isEmpty: (T) -> Boolean = { false },
    emptyTitle: String = "Nothing here yet",
    emptyBody: String? = null,
    skeletonRows: Int = 4,
    content: @Composable (T) -> Unit,
) {
    val c = OttoTheme.colors
    when (load) {
        Load.Idle, Load.Loading -> Column(modifier.fillMaxWidth().semantics { stateDescription = "loading" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(skeletonRows) { Box(Modifier.fillMaxWidth().height(44.dp).skeletonPulse(c, OttoShapes.r2)) }
        }
        is Load.Failed -> Column(
            modifier.fillMaxWidth().clip(OttoShapes.r3).background(c.card).border(1.dp, c.lineSoft, OttoShapes.r3).padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("couldn't load", style = OttoTheme.type.ui)
            val detail = if (load.message == Load.COULD_NOT_LOAD) "check your connection, then try again." else load.message
            Text(detail, style = OttoTheme.type.ui.copy(fontSize = 13.5.sp, color = c.dim))
            OttoButton("Retry", onRetry, kind = ButtonKind.SOFT, modifier = Modifier.padding(top = 5.dp))
        }
        Load.Unsupported -> EmptyNote(dev.otto.phone.transport.NEEDS_NEWER_OTTO, "This otto doesn't know this request yet; update otto on the computer running it.", modifier)
        is Load.Ready -> if (isEmpty(load.value)) EmptyNote(emptyTitle, emptyBody, modifier) else content(load.value)
    }
}

/** A row that pushes another screen: label, optional mono value, chevron. */
@Composable
fun LinkRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, value: String? = null, first: Boolean = false) {
    val c = OttoTheme.colors
    Column(modifier.fillMaxWidth()) {
        if (!first) Hairline(color = c.lineSoft)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = OttoTheme.type.ui.copy(fontSize = 15.5.sp), modifier = Modifier.weight(1f))
            if (value != null) Text(value, style = OttoTheme.type.meta.copy(color = c.dim), modifier = Modifier.padding(end = 8.dp), maxLines = 1)
            androidx.compose.material3.Icon(OttoIcons.ChevronRight, contentDescription = null, tint = c.faint, modifier = Modifier.size(14.dp))
        }
    }
}
