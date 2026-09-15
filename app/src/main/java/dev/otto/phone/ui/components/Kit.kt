package dev.otto.phone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.otto.phone.ui.theme.OttoShapes
import dev.otto.phone.ui.theme.OttoTheme
import dev.otto.phone.ui.theme.OttoType
import dev.otto.phone.ui.theme.pressScale

/** A 1 dp rule, the only divider the app draws. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = OttoTheme.colors.line) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** A section label: mono uppercase, faint, followed by a hairline to the edge. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Text(OttoType.caps(text), style = OttoTheme.type.eyebrow.copy(fontWeight = FontWeight.Medium), maxLines = 1)
        Box(Modifier.weight(1f).height(1.dp).background(OttoTheme.colors.line))
        trailing?.invoke()
    }
}

/** An icon-only button: a 48 dp target around an 18 dp glyph, always labelled for TalkBack. */
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = OttoTheme.colors.dim,
    enabled: Boolean = true,
    disabledReason: String? = null,
    glyph: Dp = 18.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(48.dp)
            .clip(OttoShapes.r2)
            .pressScale(interaction)
            .clickable(interaction, ripple(bounded = true), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                if (!enabled && disabledReason != null) stateDescription = disabledReason
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else OttoTheme.colors.faint.copy(alpha = 0.45f), modifier = Modifier.size(glyph))
    }
}

enum class ButtonKind { PRIMARY, OUTLINED, SOFT, DANGER }

/** The web's buttons: ink-filled primary, inset-line outlined, acc-soft soft, and a danger button
 *  that fills faintly with bad when armed. Mono uppercase labels, a 48 dp touch target. */
@Composable
fun OttoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    enabled: Boolean = true,
    armed: Boolean = false,
    icon: ImageVector? = null,
    disabledReason: String? = null,
) {
    val c = OttoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val (fill, label, ring) = when (kind) {
        ButtonKind.PRIMARY -> Triple(c.ink, c.bg, null)
        ButtonKind.OUTLINED -> Triple(Color.Transparent, c.ink, c.line)
        ButtonKind.SOFT -> Triple(c.accSoft, c.ink, null)
        ButtonKind.DANGER -> Triple(if (armed) c.bad.copy(alpha = 0.14f) else Color.Transparent, c.bad, if (armed) c.bad else c.line)
    }
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 36.dp)
            .pressScale(interaction)
            .clip(OttoShapes.r1)
            .background(if (enabled) fill else fill.copy(alpha = fill.alpha * 0.45f))
            .then(if (ring != null) Modifier.border(1.dp, ring, OttoShapes.r1) else Modifier)
            .clickable(interaction, ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (!enabled && disabledReason != null) stateDescription = disabledReason }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        val tint = if (enabled) label else label.copy(alpha = 0.45f)
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(OttoType.caps(text), style = OttoTheme.type.button.copy(color = tint), maxLines = 1)
    }
}

/** A centred day separator: "today", "yesterday", "Sep 15, 2026". */
@Composable
fun DatePill(label: String, modifier: Modifier = Modifier) {
    val c = OttoTheme.colors
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = OttoTheme.type.meta.copy(letterSpacing = 0.4.sp),
            modifier = Modifier.clip(OttoShapes.pill).background(c.bg2).border(1.dp, c.lineSoft, OttoShapes.pill).padding(horizontal = 13.dp, vertical = 3.dp),
        )
    }
}

/** A notice across the top of a screen: flat bg2 with a 3 dp start edge, an action on the right. */
@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    edge: Color = OttoTheme.colors.warn,
    action: String? = null,
    onAction: () -> Unit = {},
    secondAction: String? = null,
    onSecondAction: () -> Unit = {},
) {
    val c = OttoTheme.colors
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).background(c.bg2)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(edge))
        Row(Modifier.weight(1f).padding(start = 13.dp, end = 5.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = OttoTheme.type.ui.copy(fontSize = 13.5.sp, color = c.dim), modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (secondAction != null) OttoButton(secondAction, onSecondAction, kind = ButtonKind.OUTLINED)
            if (action != null) OttoButton(action, onAction, kind = ButtonKind.OUTLINED)
        }
    }
}

/** A toast: a card with a 3 dp start edge in clay, sans 13.5. */
@Composable
fun Toast(message: String, modifier: Modifier = Modifier) {
    val c = OttoTheme.colors
    Row(
        modifier.padding(horizontal = 16.dp).height(IntrinsicSize.Min).clip(OttoShapes.r2).background(c.card).border(1.dp, c.lineSoft, OttoShapes.r2)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(c.accent))
        Text(message, style = OttoTheme.type.ui.copy(fontSize = 13.5.sp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
    }
}

/** Material's SnackbarHost, drawing each message as a Toast. */
@Composable
fun OttoToastHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data -> Toast(data.visuals.message) }
}

/** A pushed screen's top bar: back arrow, title, optional actions. */
@Composable
fun ScreenBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(OttoIcons.ArrowLeft, "back", onBack, Modifier.semantics { }, tint = OttoTheme.colors.ink)
            Text(
                title, style = OttoTheme.type.ui.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() }, maxLines = 1,
            )
            actions()
        }
        Hairline(color = OttoTheme.colors.lineSoft)
    }
}

/** Prose in the web's quiet voice, centred: for empty lists and first runs. */
@Composable
fun EmptyNote(title: String, body: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 34.dp, horizontal = 21.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = OttoTheme.type.answer.copy(fontSize = 19.sp), textAlign = TextAlign.Center)
        if (body != null) Text(body, style = OttoTheme.type.ui.copy(fontSize = 14.sp, color = OttoTheme.colors.dim), textAlign = TextAlign.Center)
    }
}
