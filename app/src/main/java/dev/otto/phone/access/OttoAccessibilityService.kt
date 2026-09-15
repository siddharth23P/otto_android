package dev.otto.phone.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.otto.phone.bridge.DeviceException
import dev.otto.phone.bridge.DeviceOps
import dev.otto.phone.bridge.PyBridge
import dev.otto.phone.bridge.doneWith
import dev.otto.phone.device.AppCatalog
import dev.otto.phone.device.PlayStore
import dev.otto.phone.device.SettingsPages
import dev.otto.phone.guard.GuardRules
import dev.otto.phone.guard.PolicyGuard
import dev.otto.phone.transport.EventBus
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The hands. Reads the tree in front, taps, types, scrolls and captures --
 * every action behind PolicyGuard, serialised on one executor so gestures
 * never cancel each other. The bridge's DeviceOps is bound to the running
 * instance and unbound when the service stops.
 */
class OttoAccessibilityService : AccessibilityService(), DeviceOps {
    private val actions = Executors.newSingleThreadExecutor { r -> Thread(r, "otto-actions") }
    /** Where takeScreenshot delivers its result. Not [actions]: screenshot() runs there and waits for
     *  the callback, which on the same thread could only run after the wait gave up. */
    private val capture = Executors.newSingleThreadExecutor { r -> Thread(r, "otto-capture") }
    private val counter = AtomicInteger()
    private var lastSnapshot: Snapshot? = null
    private var lastNodes: Map<Int, AccessibilityNodeInfo> = emptyMap()
    //: The last screenshot, with the capture it was taken on: the cache may only
    //: answer for that same capture, and a blind tap may only follow a look at it.
    private var lastCapture: Triple<Long, String, JsonObject>? = null
    private var lookedId: String? = null
    /** When the screen last said it changed, and the wait that reads it: an action is read back once the
     *  screen goes quiet, not after a fixed sleep. */
    private val log = EventLog()
    private val settler = Settler(SystemClock::uptimeMillis, Thread::sleep, log)
    lateinit var guard: PolicyGuard
    private lateinit var catalog: AppCatalog
    /** Lives as long as the service: follows the agent's events for the status strip. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: StatusOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        guard = PolicyGuard(GuardRules.parse(assets.open("guard_rules.json").bufferedReader().readText()))
        catalog = AppCatalog(this)
        instance = this
        PyBridge.ops = this
        val strip = StatusOverlay(this) { rootInActiveWindow?.packageName?.toString() == packageName }
        overlay = strip
        scope.launch { EventBus.events.collect(strip::onEvent) }
    }

    override fun onDestroy() {
        if (instance === this) { instance = null; PyBridge.ops = DeviceOps.Unavailable }
        scope.cancel()
        overlay?.destroy()
        overlay = null
        actions.shutdownNow()
        capture.shutdownNow()
        super.onDestroy()
    }

    /** Only noted, never read: the node an event carries is not needed, and fetching it costs a call. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val kind = when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> Kind.CONTENT
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> Kind.SCROLL
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> Kind.STATE
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> Kind.WINDOWS
            else -> return
        }
        val pkg = event.packageName?.toString() ?: ""
        val at = SystemClock.uptimeMillis()
        overlay?.let { strip ->
            val stripMoved = strip.justToggled(at)
            if (kind == Kind.STATE || kind == Kind.WINDOWS) strip.frontChanged()
            // The strip is not the screen changing: while it shows, this package's events are its redraws
            // (Otto's own app is not in front), and a windows change just after it came or went is its own.
            if (strip.showing && pkg == packageName && kind != Kind.WINDOWS) return
            if (kind == Kind.WINDOWS && stripMoved) return
        }
        log.record(kind, pkg, at)
    }

    override fun onInterrupt() = Unit

    // -- run on the actions thread, waited for by the bridge's caller ------

    private fun <T> serial(block: () -> T): T = try {
        actions.submit(block).get(60, TimeUnit.SECONDS)
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    } catch (e: java.util.concurrent.TimeoutException) {
        throw DeviceException("the phone did not finish that within 60s", "timeout")
    }

    // -- reading -------------------------------------------------------------

    /** Walks the window in front. `settled` says whether the screen had stopped changing. A walk that only
     *  checks a judgement again (`keep` false) leaves the screen actions quote, and its nodes, alone. */
    private fun snapshotNow(settled: Boolean = true, keep: Boolean = true): Snapshot {
        val takenAt = SystemClock.uptimeMillis()
        val root = rootInActiveWindow ?: throw DeviceException("no window is in front to read", "failed")
        val nodes = mutableMapOf<Int, AccessibilityNodeInfo>()
        val walked = TreeWalker.walk(AndroidWalkNode(root)) { index, node -> nodes[index] = (node as AndroidWalkNode).info }
        val metrics = resources.displayMetrics
        val pkg = root.packageName?.toString() ?: ""
        // FLAG_SECURE is not visible to accessibility: the screenshot path reports a secure window, so the
        // walk no longer fetches every app window's root to learn nothing.
        val secure = false
        val keyboard = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val snapshot = Snapshot(
            snapshotId = "s${counter.incrementAndGet()}", packageName = pkg, label = catalog.label(pkg),
            width = metrics.widthPixels, height = metrics.heightPixels, keyboard = keyboard, secure = secure, nodes = walked,
            takenAt = takenAt, settled = settled,
        )
        if (keep) { lastSnapshot = snapshot; lastNodes = nodes }
        return snapshot
    }

    override fun tree(): JsonObject = serial {
        val snapshot = snapshotNow()
        if (guard.packageVerdict(snapshot.packageName, snapshot.label).isNotEmpty()) {
            // Not described: the digest of a payment app is itself a leak.
            throw DeviceException(guard.packageVerdict(snapshot.packageName, snapshot.label), "guard", handover = true)
        }
        snapshot.toJson()
    }

    override fun foreground(): JsonObject = serial {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: ""
        buildJsonObject { put("package", pkg); put("label", catalog.label(pkg)) }
    }

    /** A snapshot as a reply may carry it: not at all when a payment app is in front, whose digest is
     *  itself a leak. Every reply with an `after` goes through here. */
    private fun shown(snapshot: Snapshot?): JsonObject? =
        snapshot?.takeIf { guard.packageVerdict(it.packageName, it.label).isEmpty() }?.toJson()

    /** The screen once it stopped changing after an action taken between `since` and `actedAt`, or at the
     *  policy's cap, marked unsettled. One walk. */
    private fun settledSnapshot(since: Long, actedAt: Long, policy: SettlePolicy, until: (() -> Boolean)? = null): Snapshot? {
        val settle = settler.await(since, actedAt, policy, until)
        return runCatching { snapshotNow(settled = settle.settled) }.getOrNull()
    }

    /** An action's reply: what was done, and the screen after it -- `reuse` when the action already read it. */
    private fun after(done: String, since: Long, actedAt: Long, policy: SettlePolicy = SettlePolicy.ACTION, reuse: Snapshot? = null): JsonObject =
        doneWith(done, shown(reuse ?: settledSnapshot(since, actedAt, policy)))

    private fun now(): Long = SystemClock.uptimeMillis()

    /** A check-only walk, when the screen a judgement was made on may not be the one in front any more:
     *  it was read while still changing, or the screen has changed since. Null when it is still that one. */
    private fun recheck(judged: Snapshot): Snapshot? =
        if (guard.needsRecheck(judged, log.lastAnyAt)) snapshotNow(keep = false) else null

    private fun requireNode(snapshotId: String, index: Int): Pair<UiNode, AccessibilityNodeInfo> {
        val snapshot = lastSnapshot ?: throw DeviceException("read the screen first", "stale")
        if (snapshot.snapshotId != snapshotId) throw DeviceException("the screen changed since $snapshotId; read it again", "stale")
        val node = snapshot.node(index) ?: throw DeviceException("no element [$index] on this screen", "stale")
        val info = lastNodes[index] ?: throw DeviceException("element [$index] is gone", "stale")
        return node to info
    }

    // -- acting --------------------------------------------------------------

    override fun tap(x: Int, y: Int): JsonObject = serial {
        val current = lastSnapshot ?: snapshotNow()
        guard.requireActionable(current)
        // A tap by coordinates is a tap on whatever is drawn there; a point with nothing under it is
        // refused on a screen that has elements, because what is drawn there is unknown.
        val under = guard.nodeAt(current, x, y)
        if (under == null) guard.requireBlindTap(current, lookedId)
        else { guard.requireTappable(under, commit = false, texts = current.nodes.map { it.label }); guard.requireTypeable(under.takeIf { it.password }) }
        recheck(current)?.let { fresh -> guard.requireStillAtPoint(current, fresh, x, y) }
        val since = now()
        if (!Gestures.dispatch(this, Gestures.tap(x, y))) throw DeviceException("the tap was not delivered", "failed")
        after("tapped $x,$y", since, now())
    }

    override fun tapNode(snapshotId: String, node: Int, long: Boolean, commit: Boolean): JsonObject = serial {
        val current = lastSnapshot
        guard.requireActionable(current)
        val (ui, info) = requireNode(snapshotId, node)
        guard.requireTappable(ui, commit, texts = current?.nodes?.map { it.label } ?: emptyList())
        // Judged again on what is in front now when the screen moved since it was read. The click still
        // goes to the node object kept from that read, so it must still show what was judged.
        val target = current?.let { recheck(it) }?.let { fresh ->
            guard.requireStillTappable(current, fresh, node, commit).also {
                if (!info.refresh() || info.shownLabel() != ui.label) throw DeviceException("[$node] '${ui.label}' changed; read the screen again", "stale")
            }
        } ?: ui
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        val since = now()
        val done = info.performAction(action) || Gestures.dispatch(this, Gestures.tap(target.centreX, target.centreY, long))
        if (!done) throw DeviceException("could not tap [$node] '${ui.label}'", "failed")
        after("${if (long) "long-pressed" else "tapped"} [$node] '${ui.label}'", since, now())
    }

    override fun typeText(text: String, node: Int): JsonObject = serial {
        guard.requireActionable(lastSnapshot)
        val since = now()
        val target: AccessibilityNodeInfo? = if (node >= 0) {
            val (ui, info) = requireNode(lastSnapshot?.snapshotId ?: "", node)
            guard.requireTypeable(ui)
            info.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            info
        } else findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target != null && target.isPassword) guard.requireTypeable(lastSnapshot?.nodes?.firstOrNull { it.password }
            ?: UiNode(0, "", "", "edit-field", 0, 0, 0, 0, false, true, false, true, true, null))
        if (node < 0 && target == null && lastSnapshot?.nodes?.any { it.password } == true) {
            throw DeviceException("a password field is on this screen and nothing has focus -- say which field", "guard", handover = true)
        }
        var ok = false
        if (target != null) {
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        if (!ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // AccessibilityInputConnection.commitText returns nothing (unlike
            // the IME InputConnection); having a connection is the success.
            val connection = inputMethod?.currentInputConnection
            if (connection != null) {
                connection.commitText(text, 1, null)
                ok = true
            }
        }
        if (!ok && target != null) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("otto", text))
            ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        if (!ok) throw DeviceException("nothing accepted the text -- tap the field first", "failed")
        after("typed ${text.take(60)}", since, now())
    }

    override fun press(key: String): JsonObject = serial {
        // Back, home and recents are the way out and always allowed; enter is the keyboard's
        // submit for the focused field and is judged like a tap on this screen.
        if (key == "enter") {
            val current = lastSnapshot ?: snapshotNow()
            guard.requireActionable(current)
            guard.requireSubmit(current)
            recheck(current)?.let { fresh -> guard.requireActionable(fresh); guard.requireSubmit(fresh) }
        }
        val since = now()
        val ok = when (key) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "enter" -> {
                val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                focused?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) ?: false
            }
            else -> throw DeviceException("not a key this knows: $key", "unsupported")
        }
        if (!ok) throw DeviceException("$key was not accepted", "failed")
        after("pressed $key", since, now())
    }

    private fun swipeGesture(direction: String, w: Int, h: Int, x: Int = -1, y: Int = -1): GestureDescription {
        if (x < 0 || y < 0) return when (direction) {
            "up" -> Gestures.swipe(w / 2, (h * 0.7).toInt(), w / 2, (h * 0.3).toInt())
            "down" -> Gestures.swipe(w / 2, (h * 0.3).toInt(), w / 2, (h * 0.7).toInt())
            "left" -> Gestures.swipe((w * 0.8).toInt(), h / 2, (w * 0.2).toInt(), h / 2)
            "right" -> Gestures.swipe((w * 0.2).toInt(), h / 2, (w * 0.8).toInt(), h / 2)
            else -> throw DeviceException("not a direction: $direction", "unsupported")
        }
        // From the point asked for: half the screen across or 40% of it down, kept off the edges,
        // where a swipe is the system's back gesture. Too short a path would land as a tap, so refused.
        fun cx(v: Int) = v.coerceIn(w * 3 / 100, w * 97 / 100)
        fun cy(v: Int) = v.coerceIn(h * 3 / 100, h * 97 / 100)
        val (x2, y2) = when (direction) {
            "up" -> cx(x) to cy(y - h * 2 / 5)
            "down" -> cx(x) to cy(y + h * 2 / 5)
            "left" -> cx(x - w / 2) to cy(y)
            "right" -> cx(x + w / 2) to cy(y)
            else -> throw DeviceException("not a direction: $direction", "unsupported")
        }
        if (maxOf(kotlin.math.abs(x2 - cx(x)), kotlin.math.abs(y2 - cy(y))) < minOf(w, h) / 10) {
            throw DeviceException("no room to swipe $direction from $x,$y", "failed")
        }
        return Gestures.swipe(cx(x), cy(y), x2, y2)
    }

    /** A drag across the middle of one element (a list, a web page), for a scroll the element
     *  itself did not perform. Null when the element is too small to drag inside. */
    private fun gestureWithin(finger: String, n: UiNode, w: Int, h: Int): GestureDescription? {
        val left = maxOf(n.left, w * 3 / 100)
        val right = minOf(n.right, w * 97 / 100)
        val top = maxOf(n.top, 0)
        val bottom = minOf(n.bottom, h)
        if (right - left < 100 || bottom - top < 100) return null
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        val dx = (right - left) * 3 / 10
        val dy = (bottom - top) * 3 / 10
        return when (finger) {
            "up" -> Gestures.swipe(cx, cy + dy, cx, cy - dy, 400)
            "down" -> Gestures.swipe(cx, cy - dy, cx, cy + dy, 400)
            "left" -> Gestures.swipe(cx + dx, cy, cx - dx, cy, 400)
            else -> Gestures.swipe(cx - dx, cy, cx + dx, cy, 400)
        }
    }

    override fun swipe(direction: String, x: Int, y: Int): JsonObject = serial {
        val current = lastSnapshot ?: snapshotNow()
        guard.requireActionable(current)
        val from = x >= 0 && y >= 0
        // A swipe that starts on an element acts on it ("Slide to pay"): judged like a tap on it.
        if (from) {
            guard.nodeAt(current, x, y)?.let { guard.requireTappable(it, commit = false, texts = current.nodes.map { n -> n.label }) }
            recheck(current)?.let { fresh -> guard.requireStillSwipeable(current, fresh, x, y) }
        }
        val m = resources.displayMetrics
        val gesture = swipeGesture(direction, m.widthPixels, m.heightPixels, x, y)
        val since = now()
        if (!Gestures.dispatch(this, gesture)) throw DeviceException("the swipe was not delivered", "failed")
        after(if (from) "swiped $direction from $x,$y" else "swiped $direction", since, now())
    }

    private fun scrollsSideways(info: AccessibilityNodeInfo): Boolean {
        val cls = info.className?.toString() ?: ""
        if ("HorizontalScrollView" in cls || "ViewPager" in cls) return true
        val grid = info.collectionInfo ?: return false
        return grid.rowCount in 0..1 && grid.columnCount > 1
    }

    /** Whether a scroll taken between `since` and `actedAt` moved what lies in `region`, with the last walk.
     *  One walk once the screen settles; a second only when the screen was still saying it changed as it
     *  was read -- a WebView reports moved nodes late -- after one more quiet window. */
    private fun moved(was: Int, region: UiNode?, since: Long, actedAt: Long): Pair<Boolean, Snapshot?> {
        val first = settledSnapshot(since, actedAt, SettlePolicy.SCROLL) ?: return false to null
        if (ScreenDiff.signature(first, region) != was) return true to first
        if (log.lastAnyAt < maxOf(since, first.takenAt - SettlePolicy.SCROLL.quietMs)) return false to first
        val again = settledSnapshot(first.takenAt, first.takenAt, LATE_SCROLL) ?: return false to first
        return (ScreenDiff.signature(again, region) != was) to again
    }

    override fun scroll(direction: String, node: Int): JsonObject = serial {
        val before = lastSnapshot ?: snapshotNow()
        guard.requireActionable(before)
        // Scrolling down means content moves up: the finger swipes up.
        val finger = when (direction) {
            "down" -> "up"; "up" -> "down"; "left" -> "right"; "right" -> "left"
            else -> throw DeviceException("not a direction: $direction", "unsupported")
        }
        val sideways = direction == "left" || direction == "right"
        // The list to move: the one named, else the largest on screen that scrolls this way -- a web
        // page's WebView, not the carousel inside it.
        val target: Pair<UiNode, AccessibilityNodeInfo>? = if (node >= 0) requireNode(before.snapshotId, node)
            else before.nodes.filter { it.scrollable }
                .mapNotNull { ui -> lastNodes[ui.index]?.let { ui to it } }
                .filter { (_, info) -> scrollsSideways(info) == sideways }
                .maxByOrNull { (ui, _) -> (ui.right - ui.left).toLong() * (ui.bottom - ui.top) }
        val region = target?.first
        val was = ScreenDiff.signature(before, region)
        val forward = direction == "down" || direction == "right"
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        // A view can accept the scroll action and not move (a WebView often does): what counts is the
        // screen changing, and when it does not, the finger does it instead.
        var since = now()
        var (shifted, last) = if (target?.second?.performAction(action) == true) moved(was, region, since, now()) else false to null
        if (!shifted) {
            val m = resources.displayMetrics
            val gesture = region?.let { gestureWithin(finger, it, m.widthPixels, m.heightPixels) }
                ?: swipeGesture(finger, m.widthPixels, m.heightPixels)
            since = now()
            if (!Gestures.dispatch(this, gesture)) throw DeviceException("could not scroll", "failed")
            moved(was, region, since, now()).let { (byFinger, walk) -> shifted = byFinger; last = walk }
        }
        after(if (shifted) "scrolled $direction" else "scrolled $direction, but nothing on screen moved -- the end of the list, or a view that does not scroll that way",
            since, now(), SettlePolicy.SCROLL, reuse = last)
    }

    // -- looking -------------------------------------------------------------

    override fun screenshot(): JsonObject = serial {
        val current = lastSnapshot ?: snapshotNow()
        if (guard.packageVerdict(current.packageName, current.label).isNotEmpty()) {
            throw DeviceException(guard.packageVerdict(current.packageName, current.label), "guard", handover = true)
        }
        lastCapture?.let { (at, id, json) ->
            // Only for the capture it was taken on: the screen may have moved on
            // inside the second, and an old picture of a new screen is a lie.
            if (id == current.snapshotId && System.currentTimeMillis() - at < 1000) {
                lookedId = current.snapshotId
                return@serial json
            }
        }
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var error = 0
        // Otto's status strip is not part of the app's screen: off for the capture, back once it is taken.
        overlay?.hideForCapture()
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, capture, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    bitmap = screenshot.hardwareBuffer?.let { buf ->
                        Bitmap.wrapHardwareBuffer(buf, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                            .also { buf.close() }
                    }
                    latch.countDown()
                }
                override fun onFailure(errorCode: Int) { error = errorCode; latch.countDown() }
            })
            latch.await(5, TimeUnit.SECONDS)
        } finally {
            overlay?.restoreAfterCapture()
        }
        val shot = bitmap ?: when (error) {
            ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> {
                guard.handedOver = true
                throw DeviceException("this window is protected (secure content) -- the person takes over", "guard", handover = true)
            }
            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> throw DeviceException("screenshots are rate-limited; wait a moment", "timeout")
            else -> throw DeviceException("could not capture the screen (error $error)", "failed")
        }
        val scale = 1280f / maxOf(shot.width, shot.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(shot, (shot.width * scale).toInt(), (shot.height * scale).toInt(), true) else shot
        val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 90, it) }.toByteArray()
        val json = buildJsonObject {
            put("png_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("scale", if (scale < 1f) scale.toDouble() else 1.0)
        }
        lastCapture = Triple(System.currentTimeMillis(), current.snapshotId, json)
        lookedId = current.snapshotId
        json
    }

    // -- apps and settings ---------------------------------------------------

    override fun apps(): JsonObject = buildJsonObject {
        put("apps", JsonArray(catalog.list().map { app -> buildJsonObject { put("label", app.label); put("package", app.packageName) } }))
    }

    override fun launch(packageName: String): JsonObject = serial {
        val why = guard.packageVerdict(packageName, catalog.label(packageName))
        if (why.isNotEmpty()) throw DeviceException(why, "guard", handover = false)
        val since = now()
        if (!catalog.launch(packageName)) throw DeviceException("no launchable app called $packageName", "failed")
        // Read once the app is in front and has drawn; one already in front simply settles.
        val after = settledSnapshot(since, now(), SettlePolicy.LAUNCH) { rootInActiveWindow?.packageName?.toString() == packageName }
        buildJsonObject {
            put("package", packageName); put("label", catalog.label(packageName))
            shown(after)?.let { put("after", it) }
        }
    }

    override fun openSettings(page: String, packageName: String): JsonObject = serial {
        val intent = SettingsPages.intentFor(page, packageName) ?: throw DeviceException("no Settings page called $page", "unsupported")
        // The page is in front when another app's window arrived, or when the app that answers the intent
        // is in front (it may have been already, on another page). Null when package visibility hides it.
        val front = rootInActiveWindow?.packageName?.toString() ?: ""
        @Suppress("DEPRECATION") val answers = packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
        val since = now()
        if (!SettingsPages.open(this, page, packageName)) throw DeviceException("no Settings page called $page", "unsupported")
        val after = settledSnapshot(since, now(), SettlePolicy.SETTINGS) {
            log.stateSeenOtherThan(front, since) || (answers != null && rootInActiveWindow?.packageName?.toString() == answers)
        }
        buildJsonObject {
            put("page", page)
            shown(after)?.let { put("after", it) }
        }
    }

    override fun install(packageName: String, query: String): JsonObject = serial {
        guard.requireActionable(null)
        val why = guard.packageVerdict(packageName, query)
        if (why.isNotEmpty()) throw DeviceException("$why -- not installed", "guard", handover = false)
        var since = now()
        if (!PlayStore.open(this, packageName, query)) throw DeviceException("the Play Store could not be opened", "unsupported")
        var actedAt = now()
        var state = "listing opened"
        val deadline = actedAt + 20_000
        // The listing loads in steps: look each time it settles, and walk only when it said it changed.
        var last: Snapshot? = null
        var eventsAtWalk = EventLog.NEVER
        while (now() < deadline) {
            settler.await(since, actedAt, SettlePolicy.ACTION)
            since = now(); actedAt = since
            val events = log.lastAnyAt
            if (last != null && events == eventsAtWalk) continue
            val snapshot = runCatching { snapshotNow() }.getOrNull() ?: continue
            last = snapshot; eventsAtWalk = events
            if (snapshot.packageName != PlayStore.PACKAGE) continue
            val priced = snapshot.nodes.any { PlayStore.PRICE.containsMatchIn(it.label) && it.clickable }
            if (priced) throw DeviceException("this app costs money -- the person decides that", "guard", handover = true)
            val installButton = snapshot.nodes.firstOrNull { it.clickable && it.label.equals("Install", ignoreCase = true) }
            if (installButton != null) {
                val info = lastNodes[installButton.index]
                val clickAt = now()
                if (info?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { state = "installing"; since = clickAt; actedAt = now(); break }
            }
            if (snapshot.nodes.any { it.clickable && (it.label.equals("Open", ignoreCase = true) || it.label.equals("Update", ignoreCase = true)) }) { state = "already installed"; break }
        }
        catalog.forgetLabels()
        // The last walk still answers when nothing has changed since it; a tap on Install always has.
        val after = if (state != "installing" && last != null && log.lastAnyAt == eventsAtWalk) last
            else settledSnapshot(since, actedAt, SettlePolicy.ACTION)
        buildJsonObject {
            put("state", state)
            shown(after)?.let { put("after", it) }
        }
    }

    companion object {
        @Volatile var instance: OttoAccessibilityService? = null
        /** The second look after a scroll whose screen was still changing: no minimum, 600 ms at most. */
        private val LATE_SCROLL = SettlePolicy.SCROLL.copy(minMs = 0, capMs = 600)
    }
}

/** The label the walker gives a node: its text, else its description. */
private fun AccessibilityNodeInfo.shownLabel(): String =
    (text?.toString()?.trim() ?: "").ifBlank { contentDescription?.toString()?.trim() ?: "" }

/** AccessibilityNodeInfo as a WalkNode. The walker reports kept indices, and the service keeps the info by index. */
private class AndroidWalkNode(val info: AccessibilityNodeInfo) : WalkNode {
    override val text: String get() = info.text?.toString() ?: ""
    override val contentDescription: String get() = info.contentDescription?.toString() ?: ""
    override val className: String get() = info.className?.toString() ?: ""
    override val isVisibleToUser: Boolean get() = info.isVisibleToUser
    override val isClickable: Boolean get() = info.isClickable
    override val isEditable: Boolean get() = info.isEditable
    override val isScrollable: Boolean get() = info.isScrollable
    override val isPassword: Boolean get() = info.isPassword
    override val isFocused: Boolean get() = info.isFocused
    override val isCheckable: Boolean get() = info.isCheckable
    override val isChecked: Boolean get() = info.isChecked
    override val viewId: String get() = info.viewIdResourceName ?: ""
    override fun boundsOnScreen(): IntArray {
        val r = android.graphics.Rect(); info.getBoundsInScreen(r)
        return intArrayOf(r.left, r.top, r.right, r.bottom)
    }
    override fun children(): List<WalkNode> = (0 until info.childCount).mapNotNull { info.getChild(it) }.map { AndroidWalkNode(it) }
}
