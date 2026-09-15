package dev.otto.phone.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.otto.phone.ui.components.OttoIcons
import dev.otto.phone.ui.theme.OttoShapes
import dev.otto.phone.ui.theme.OttoTheme
import dev.otto.phone.ui.theme.pressScale

/** The composer: a top hairline only, a borderless mono field of one to five lines, a clay Send, an
 *  outlined Stop while a turn runs, and a row of chips saying where the turn runs and on what. */
@Composable
fun Composer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    running: Boolean,
    asking: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    chips: List<String>,
    modifier: Modifier = Modifier,
    /** Why Send is disabled, for TalkBack. */
    disabledReason: String = "type a message first",
) {
    val c = OttoTheme.colors
    val t = OttoTheme.type
    val line = c.line
    Column(
        modifier.fillMaxWidth().background(c.bg)
            .drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val placeholder = if (asking) "Answer otto…" else ChatText.PLACEHOLDER
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = t.composer.copy(lineHeight = t.composer.fontSize * 1.6f),
                cursorBrush = SolidColor(c.ink),
                minLines = 1,
                maxLines = 5,
                modifier = Modifier.weight(1f).heightIn(min = 36.dp).padding(vertical = 7.dp).testTag("chat_input")
                    .semantics { contentDescription = placeholder },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.text.isEmpty()) Text(placeholder, style = t.composer.copy(color = c.faint))
                        inner()
                    }
                },
            )
            if (running) StopButton(onStop)
            if (!running || asking) SendButton(
                enabled = canSend && value.text.isNotBlank(),
                reason = if (canSend) "type a message first" else disabledReason,
                onClick = onSend,
            )
        }
        if (chips.isNotEmpty()) Row(Modifier.padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            chips.forEach { Text(it, style = t.chip.copy(color = c.faint), maxLines = 1) }
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, reason: String, onClick: () -> Unit) {
    val c = OttoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.minimumInteractiveComponentSize().testTag("chat_send")
            .semantics { contentDescription = "send"; if (!enabled) stateDescription = reason }
            .pressScale(interaction)
            .size(36.dp).clip(OttoShapes.r2)
            .background(if (enabled) c.accent else c.accent.copy(alpha = 0.4f))
            .clickable(interaction, ripple(), enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(OttoIcons.ArrowUp, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    val c = OttoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.minimumInteractiveComponentSize().testTag("status_stop")
            .semantics { contentDescription = "stop" }
            .pressScale(interaction)
            .size(36.dp).clip(OttoShapes.r2).background(c.bg2).border(1.dp, c.line, OttoShapes.r2)
            .clickable(interaction, ripple(), role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(OttoIcons.Square, contentDescription = null, tint = c.ink, modifier = Modifier.size(14.dp))
    }
}
