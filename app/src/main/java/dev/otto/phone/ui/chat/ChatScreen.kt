package dev.otto.phone.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otto.phone.protocol.Op
import dev.otto.phone.state.ChatBlock
import dev.otto.phone.state.Format
import dev.otto.phone.state.Route
import dev.otto.phone.ui.Link
import dev.otto.phone.ui.Models
import dev.otto.phone.ui.components.Banner
import dev.otto.phone.ui.components.DatePill
import dev.otto.phone.ui.components.Hairline
import dev.otto.phone.ui.components.IconAction
import dev.otto.phone.ui.components.OttoIcons
import dev.otto.phone.ui.components.OttoToastHost
import dev.otto.phone.ui.components.Wordmark
import dev.otto.phone.ui.document.DocumentRow
import dev.otto.phone.ui.theme.OttoShapes
import dev.otto.phone.ui.theme.OttoTheme
import dev.otto.phone.ui.theme.PulseDot
import dev.otto.phone.transport.NEEDS_NEWER_OTTO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(m: Models) {
    val app by m.app.state.collectAsStateWithLifecycle()
    val chat by m.chat.state.collectAsStateWithLifecycle()
    val c = OttoTheme.colors
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val toasts = remember { SnackbarHostState() }
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) { merge(m.chat.toasts, m.sessions.toasts).collect { toasts.currentSnackbarData?.dismiss(); toasts.showSnackbar(it) } }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }

    val now by produceState(System.currentTimeMillis(), chat.running) {
        value = System.currentTimeMillis()
        while (chat.running) { delay(1_000); value = System.currentTimeMillis() }
    }
    val actions = rememberMessageActions(
        onQuote = { q -> val text = ChatText.quote(q) + input.text; input = TextFieldValue(text, TextRange(text.length)) },
        toast = m.chat::toast,
    )

    ModalNavigationDrawer(
        drawerState = drawer,
        // Opened by the menu button only: an edge swipe would fight the system's back gesture.
        gesturesEnabled = drawer.isOpen,
        scrimColor = c.scrim,
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = RectangleShape, drawerContainerColor = c.bg2, drawerContentColor = c.ink, drawerTonalElevation = 0.dp,
                modifier = Modifier.widthIn(max = 340.dp),
            ) {
                SessionsDrawer(m, onClose = { scope.launch { drawer.close() } })
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(c.bg)) {
            Column(Modifier.fillMaxSize()) {
                ChatTopBar(
                    spend = chat.usage?.let { Format.formatCost(it.cost, ChatText.UNKNOWN_COST) + if (!it.fullyPriced && it.cost != null) "+" else "" },
                    onMenu = { m.sessions.load(); m.sessions.loadUsage(); scope.launch { drawer.open() } },
                    onSettings = { m.app.push(Route.Settings) },
                    onCopyLast = {
                        val last = m.chat.lastAnswer
                        if (last == null) m.chat.toast("nothing to copy yet") else { clipboard.setText(AnnotatedString(last)); m.chat.toast("copied") }
                    },
                )
                chat.turn?.let { turn ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PulseDot(c.accent)
                        Text(ChatText.statusLine(turn, now), style = OttoTheme.type.meta, maxLines = 1)
                    }
                }
                ChatBanners(m, app)
                Transcript(m, Modifier.weight(1f), now, actions, onSuggestion = { s -> input = TextFieldValue(s, TextRange(s.length)) })
                val asking = chat.ask != null
                Composer(
                    value = input,
                    onValueChange = { input = it },
                    running = chat.running,
                    asking = asking,
                    canSend = app.link == Link.Ready && (asking || !chat.running),
                    onSend = {
                        val text = input.text
                        if (asking) { m.chat.answer(text); input = TextFieldValue("") }
                        else if (m.chat.send(text)) input = TextFieldValue("")
                    },
                    onStop = { m.chat.stop() },
                    chips = listOfNotNull(ChatText.whereChip(chat.turn?.where ?: lastWhere(chat.blocks)), ChatText.modelChip(chat.turn)),
                )
            }
            OttoToastHost(toasts, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        }
    }
}

private fun lastWhere(blocks: List<ChatBlock>) = (blocks.lastOrNull { it is ChatBlock.Otto && it.turn != null } as? ChatBlock.Otto)?.turn?.where

@Composable
private fun ChatTopBar(spend: String?, onMenu: () -> Unit, onSettings: () -> Unit, onCopyLast: () -> Unit) {
    val c = OttoTheme.colors
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(OttoIcons.Menu, "sessions", onMenu, Modifier.testTag("topbar_sessions"), tint = c.ink)
            Wordmark(Modifier.padding(start = 2.dp))
            Box(Modifier.weight(1f))
            if (spend != null) Text(spend, style = OttoTheme.type.meta.copy(fontSize = 12.sp, color = c.dim, fontFeatureSettings = "tnum"), maxLines = 1,
                modifier = Modifier.padding(end = 2.dp).semantics { contentDescription = "spent $spend" })
            Box {
                IconAction(OttoIcons.More, "more options", { menu = true }, Modifier.testTag("topbar_more"))
                DropdownMenu(
                    expanded = menu, onDismissRequest = { menu = false },
                    containerColor = c.card, tonalElevation = 0.dp, shadowElevation = 0.dp, shape = OttoShapes.r2, border = BorderStroke(1.dp, c.line),
                ) {
                    MenuItem("Settings") { menu = false; onSettings() }
                    MenuItem("Copy last answer", OttoIcons.Copy) { menu = false; onCopyLast() }
                }
            }
        }
        Hairline(color = c.lineSoft)
    }
}

@Composable
private fun ChatBanners(m: Models, app: dev.otto.phone.ui.AppState) {
    val c = OttoTheme.colors
    var newerDismissed by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        when (val link = app.link) {
            is Link.Failed -> Banner(link.message, edge = c.bad, action = "Retry", onAction = { m.app.connect() }, secondAction = "Settings", onSecondAction = { m.app.push(Route.Settings) })
            Link.NeedsKey -> Banner("otto needs a key before it can answer.", action = "Keys", onAction = { m.app.push(Route.Keys) })
            Link.Connecting -> Unit
            Link.Ready -> if (!newerDismissed && !app.capabilities.supports(Op.SESSIONS_USAGE)) {
                Banner("$NEEDS_NEWER_OTTO — sessions, settings and memory need otto serve 0.3 or later.", edge = c.faint, action = "OK", onAction = { newerDismissed = true })
            }
        }
        if (app.handedOver) Banner(
            "Otto stopped: this step is yours (a payment, a PIN or a protected screen).",
            edge = c.accent, action = "Resume", onAction = { m.app.resumeAfterHandover() },
        )
        if (!app.serviceEnabled && app.link == Link.Ready) Banner(
            "The accessibility service is off; Otto can answer but not act.",
            action = "Turn on", onAction = { m.app.openAccessibilitySettings() },
        )
    }
}

@Composable
private fun Transcript(m: Models, modifier: Modifier, now: Long, actions: MessageActions, onSuggestion: (String) -> Unit) {
    val chat by m.chat.state.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val rows = remember(chat.blocks) { ChatText.rows(chat.blocks, System.currentTimeMillis()) }
    val tail = (if (chat.turn != null) 1 else 0) + (if (chat.ask != null) 1 else 0)
    val count = rows.size + tail
    // The latest stays in sight: when something arrives, while an answer grows, and when the keyboard opens.
    LaunchedEffect(count, chat.turn?.streaming?.length, chat.turn?.lines?.size) { if (count > 0) list.scrollToItem(count - 1, SCROLL_TO_END) }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        snapshotFlow { ime.getBottom(density) > 0 }.distinctUntilChanged().filter { it }.collect {
            val last = list.layoutInfo.totalItemsCount - 1
            if (last >= 0) list.scrollToItem(last, SCROLL_TO_END)
        }
    }
    if (count == 0) {
        EmptyChat(modifier, onSuggestion)
        return
    }
    LazyColumn(
        modifier.fillMaxWidth(), state = list,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(21.dp),
    ) {
        items(rows, key = { r -> if (r is ChatText.Row.Message) "m${r.index}" else "d${(r as ChatText.Row.Day).label}" }) { row ->
            when (row) {
                is ChatText.Row.Day -> DatePill(row.label)
                is ChatText.Row.Message -> when (val b = row.block) {
                    is ChatBlock.User -> UserMessage(b, actions)
                    is ChatBlock.Otto -> OttoMessage(b, now, actions) {
                        b.turn?.document?.let { doc -> DocumentRow(doc, onOpen = { m.app.push(Route.Document(doc, chat.sessionId)) }) }
                    }
                    is ChatBlock.System -> SystemMessage(b, now, actions)
                    is ChatBlock.Earlier -> EarlierMessage(b)
                }
            }
        }
        chat.turn?.let { turn -> item(key = "live") { LiveTurn(turn, now) } }
        chat.ask?.let { ask -> item(key = "ask:${ask.threadId}") { AskCard(ask) { choice -> m.chat.answer(choice) } } }
    }
}

private const val SCROLL_TO_END = 100_000

@Composable
private fun EmptyChat(modifier: Modifier, onSuggestion: (String) -> Unit) {
    val c = OttoTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 21.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(ChatText.EMPTY_TITLE, style = OttoTheme.type.answer.copy(fontSize = 21.sp))
        Text(ChatText.EMPTY_BODY, style = OttoTheme.type.ui.copy(fontSize = 14.sp, color = c.dim), modifier = Modifier.padding(bottom = 5.dp))
        ChatText.EMPTY_SUGGESTIONS.forEach { s ->
            Text(
                s, style = OttoTheme.type.ui.copy(fontSize = 14.5.sp),
                modifier = Modifier.fillMaxWidth().background(c.card, OttoShapes.r2).border(1.dp, c.line, OttoShapes.r2)
                    .clickable(onClickLabel = "put this in the message") { onSuggestion(s) }.padding(horizontal = 16.dp, vertical = 13.dp),
            )
        }
    }
}
