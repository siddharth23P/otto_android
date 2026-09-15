package dev.otto.phone.ui.chat

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import dev.otto.phone.ui.theme.Motion
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.otto.phone.state.AskUi
import dev.otto.phone.state.Board
import dev.otto.phone.state.ChatBlock
import dev.otto.phone.state.Format
import dev.otto.phone.state.TurnUi
import dev.otto.phone.ui.components.ButtonKind
import dev.otto.phone.ui.components.OttoButton
import dev.otto.phone.ui.components.OttoIcons
import dev.otto.phone.ui.theme.MotionTokens
import dev.otto.phone.ui.theme.OttoFonts
import dev.otto.phone.ui.theme.OttoShapes
import dev.otto.phone.ui.theme.OttoTheme
import dev.otto.phone.ui.theme.PulseDot
import dev.otto.phone.ui.theme.TypingDots
import androidx.compose.animation.core.tween

/** What a long press on a message offers. */
class MessageActions(val onCopy: (String) -> Unit, val onShare: (String) -> Unit, val onQuote: (String) -> Unit)

fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** The mono row above every message: author in dim, then time, model and cost in faint. */
@Composable
fun MetaRow(parts: List<String>, modifier: Modifier = Modifier) {
    val t = OttoTheme.type
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, p ->
            Text(p, style = if (i == 0) t.meta.copy(color = OttoTheme.colors.dim) else t.meta, maxLines = 1)
        }
    }
}

/** Long press anywhere on a message for copy, share and quote. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Pressable(text: String, actions: MessageActions, content: @Composable () -> Unit) {
    var menu by rememberSaveable { mutableStateOf(false) }
    val c = OttoTheme.colors
    Box(Modifier.fillMaxWidth().combinedClickable(onClickLabel = null, onLongClickLabel = "message actions", onLongClick = { menu = true }, onClick = {})) {
        content()
        DropdownMenu(
            expanded = menu, onDismissRequest = { menu = false },
            containerColor = c.card, tonalElevation = 0.dp, shadowElevation = 0.dp, shape = OttoShapes.r2, border = BorderStroke(1.dp, c.line),
        ) {
            MenuItem("copy", OttoIcons.Copy, Modifier.testTag("message_copy")) { menu = false; actions.onCopy(text) }
            MenuItem("share", OttoIcons.Share, Modifier.testTag("message_share")) { menu = false; actions.onShare(text) }
            MenuItem("quote", OttoIcons.Reply, Modifier.testTag("message_quote")) { menu = false; actions.onQuote(text) }
        }
    }
}

@Composable
fun MenuItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = OttoTheme.type.ui.copy(fontSize = 14.5.sp)) },
        onClick = onClick,
        modifier = modifier,
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, tint = OttoTheme.colors.dim, modifier = Modifier.size(16.dp)) } },
    )
}

@Composable
fun UserMessage(block: ChatBlock.User, actions: MessageActions) {
    val c = OttoTheme.colors
    Pressable(block.text, actions) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            MetaRow(ChatText.meta(block))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
                Text(block.text, style = OttoTheme.type.user, modifier = Modifier.padding(start = 14.dp))
            }
        }
    }
}

@Composable
fun OttoMessage(block: ChatBlock.Otto, nowMs: Long, actions: MessageActions, after: @Composable () -> Unit = {}) {
    Pressable(block.text, actions) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            MetaRow(ChatText.meta(block))
            block.turn?.let { TraceFold(it, ChatText.traceLabel(it), nowMs) }
            AnswerMarkdown(block.text)
            after()
        }
    }
}

/** An error or a stop, in the server's own words. */
@Composable
fun SystemMessage(block: ChatBlock.System, nowMs: Long, actions: MessageActions) {
    val c = OttoTheme.colors
    val stopped = block.code == "cancelled"
    Pressable(block.text, actions) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            MetaRow(ChatText.meta(block))
            block.turn?.let { TraceFold(it, ChatText.traceLabel(it, block.text), nowMs) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(OttoIcons.Ban, contentDescription = null, tint = if (stopped) c.faint else c.bad, modifier = Modifier.size(13.dp))
                Text(block.text, style = OttoTheme.type.user.copy(fontSize = 13.sp, color = if (stopped) c.faint else c.bad))
            }
        }
    }
}

@Composable
fun EarlierMessage(block: ChatBlock.Earlier) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        MetaRow(listOf("earlier"))
        Text(block.text, style = OttoTheme.type.trace.copy(fontSize = 12.5.sp, color = OttoTheme.colors.dim, lineHeight = 20.sp))
    }
}

/** The turn running now: its meta, its open trace, and the answer growing as plain text. */
@Composable
fun LiveTurn(turn: TurnUi, nowMs: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        MetaRow(listOfNotNull("otto", ChatText.clock(turn.startedAtMs), turn.model.ifBlank { null }?.let(ChatText::model)))
        TraceFold(turn, ChatText.traceLabel(turn), nowMs)
        val streaming = turn.streaming
        if (streaming != null) Text(streaming, style = OttoTheme.type.answer)
        else TypingDots(OttoTheme.colors.faint, Modifier.padding(vertical = 8.dp).semantics { contentDescription = "otto is working" })
    }
}

/** Otto's words as Markdown in the serif answer style; code in mono on bg2, links in clay. */
@Composable
fun AnswerMarkdown(text: String, modifier: Modifier = Modifier) {
    val c = OttoTheme.colors
    val answer = OttoTheme.type.answer
    val heading = answer.copy(fontWeight = FontWeight.SemiBold)
    Markdown(
        content = text,
        colors = markdownColor(text = c.ink, codeBackground = c.bg2, inlineCodeBackground = c.bg2, dividerColor = c.line, tableBackground = c.bg2),
        typography = markdownTypography(
            h1 = heading.copy(fontSize = 24.sp, lineHeight = 32.sp),
            h2 = heading.copy(fontSize = 21.sp, lineHeight = 29.sp),
            h3 = heading.copy(fontSize = 19.sp, lineHeight = 27.sp),
            h4 = heading, h5 = heading, h6 = heading,
            text = answer,
            code = OttoTheme.type.user.copy(fontSize = 13.sp, lineHeight = 20.sp, color = c.ink),
            inlineCode = answer.copy(fontFamily = OttoFonts.mono, fontSize = 14.5.sp),
            quote = answer.copy(color = c.dim, fontStyle = FontStyle.Italic),
            paragraph = answer, ordered = answer, bullet = answer, list = answer,
            textLink = TextLinkStyles(SpanStyle(color = c.accent, textDecoration = TextDecoration.Underline)),
            table = answer.copy(fontSize = 15.sp, lineHeight = 22.sp),
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** The trace fold: brain, label, and a 1 dp start rule over mono lines. Open while the turn runs,
 *  folded once it ends, with the TUI's "thought for" title beside the label. */
@Composable
fun TraceFold(turn: TurnUi, label: String, nowMs: Long) {
    val c = OttoTheme.colors
    val t = OttoTheme.type
    var open by rememberSaveable(turn.startedAtMs) { mutableStateOf(turn.running) }
    LaunchedEffect(turn.running) { open = turn.running }
    val turnDegrees by animateFloatAsState(if (open) 90f else 0f, tween(if (OttoTheme.reducedMotion) 0 else MotionTokens.FAST_MS), label = "chevron")
    val rule = c.line
    Column(Modifier.fillMaxWidth().drawBehind { drawLine(rule, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }.padding(start = 10.dp)) {
        val title = if (turn.running) label else "$label · ${turn.thoughtTitle(nowMs)}"
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("trace_fold")
                .clickable(role = Role.Button, onClickLabel = if (open) "fold the trace" else "show the trace") { open = !open }
                .semantics(mergeDescendants = true) {
                    contentDescription = ChatText.spoken(title)
                    stateDescription = if (open) "expanded" else "collapsed"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(OttoIcons.Brain, contentDescription = null, tint = c.faint, modifier = Modifier.size(12.dp))
            Text(title, style = t.trace, maxLines = if (open) 3 else 1, modifier = Modifier.weight(1f, fill = false))
            Icon(OttoIcons.ChevronRight, contentDescription = null, tint = c.faint, modifier = Modifier.size(11.dp).rotate(turnDegrees))
        }
        val reduced = OttoTheme.reducedMotion
        AnimatedVisibility(
            open,
            enter = if (reduced) EnterTransition.None else expandVertically(Motion.mid()) + fadeIn(Motion.mid()),
            exit = if (reduced) ExitTransition.None else shrinkVertically(Motion.fast()) + fadeOut(Motion.fast()),
        ) {
            Column(Modifier.padding(start = 2.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val lines = turn.lines
                val shown = lines.takeLast(TRACE_SHOWN)
                if (lines.size > shown.size) Text("… ${lines.size - shown.size} earlier lines", style = t.trace.copy(fontSize = 11.5.sp))
                shown.forEach { TraceLine(it) }
                if (turn.model.isNotBlank()) Text("→ ${ChatText.model(turn.model)}", style = t.trace.copy(fontSize = 11.5.sp))
                if (turn.tool.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (turn.running) PulseDot(c.accent) else Icon(OttoIcons.Check, contentDescription = null, tint = c.faint, modifier = Modifier.size(10.dp))
                    Text(ChatText.toolLine(turn.tool, turn.running), style = t.trace.copy(fontSize = 11.5.sp))
                }
                turn.guardNotes.forEach { note -> Text("guard · $note", style = t.trace.copy(fontSize = 11.5.sp, color = c.warn)) }
                turn.meter?.let { m ->
                    val color = when (m.level) { Board.Level.OK -> c.faint; Board.Level.WARN -> c.warn; Board.Level.BAD -> c.bad }
                    Text(
                        "${m.bar} ${turn.calls}/${turn.budgetMax}", style = t.trace.copy(fontSize = 11.5.sp, color = color),
                        modifier = Modifier.semantics { contentDescription = "budget: ${turn.calls} of ${turn.budgetMax} model calls" },
                    )
                }
            }
        }
    }
}

private const val TRACE_SHOWN = 80

@Composable
private fun TraceLine(line: String) {
    val c = OttoTheme.colors
    val d = Board.decorate(line)
    val range = d.outcomeRange
    val text = if (range == null) AnnotatedString(d.text) else buildAnnotatedString {
        append(d.text.substring(0, range.first))
        withStyle(SpanStyle(color = if (d.outcome == Board.Outcome.OK) c.ok else c.bad)) { append(d.text.substring(range)) }
        append(d.text.substring(range.last + 1))
    }
    Text(
        text, style = OttoTheme.type.trace.copy(fontSize = 11.5.sp, lineHeight = 18.sp),
        modifier = Modifier.semantics { contentDescription = ChatText.spoken(d.text) },
    )
}

/** Otto asks: an approval card with a 3 dp start edge, the question, and its choices (the first
 *  ink-filled, the rest outlined). A free answer goes through the composer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskCard(ask: AskUi, onChoice: (String) -> Unit) {
    val c = OttoTheme.colors
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(c.card, OttoShapes.r2).testTag("ask_card")) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(c.warn))
        Column(Modifier.weight(1f).padding(horizontal = 15.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("otto asks", style = OttoTheme.type.meta.copy(color = c.warn))
            Text(ask.question, style = OttoTheme.type.ui.copy(fontSize = 15.sp, lineHeight = 22.sp))
            if (ask.choices.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ask.choices.forEachIndexed { i, choice ->
                    OttoButton(choice, { onChoice(choice) }, Modifier.testTag("ask_choice_$i"), kind = if (i == 0) ButtonKind.PRIMARY else ButtonKind.OUTLINED)
                }
            }
            Text("or type an answer below", style = OttoTheme.type.meta)
        }
    }
}

/** Copy, via the clipboard, with a toast. */
@Composable
fun rememberMessageActions(onQuote: (String) -> Unit, toast: (String) -> Unit): MessageActions {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    return androidx.compose.runtime.remember(clipboard, context) {
        MessageActions(
            onCopy = { clipboard.setText(AnnotatedString(it)); toast("copied") },
            onShare = { shareText(context, it) },
            onQuote = onQuote,
        )
    }
}
