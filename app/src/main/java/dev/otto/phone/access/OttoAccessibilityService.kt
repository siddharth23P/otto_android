package dev.otto.phone.access

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import android.util.Base64
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
    private val counter = AtomicInteger()
    private var lastSnapshot: Snapshot? = null
    private var lastNodes: Map<Int, AccessibilityNodeInfo> = emptyMap()
    private var lastCapture: Pair<Long, JsonObject>? = null
    lateinit var guard: PolicyGuard
    private lateinit var catalog: AppCatalog

    override fun onServiceConnected() {
        super.onServiceConnected()
        guard = PolicyGuard(GuardRules.parse(assets.open("guard_rules.json").bufferedReader().readText()))
        catalog = AppCatalog(this)
        instance = this
        PyBridge.ops = this
    }

    override fun onDestroy() {
        if (instance === this) { instance = null; PyBridge.ops = DeviceOps.Unavailable }
        actions.shutdownNow()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
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

    private fun snapshotNow(): Snapshot {
        val root = rootInActiveWindow ?: throw DeviceException("no window is in front to read", "failed")
        val nodes = mutableMapOf<Int, AccessibilityNodeInfo>()
        val walked = TreeWalker.walk(AndroidWalkNode(root)) { index, node -> nodes[index] = (node as AndroidWalkNode).info }
        val metrics = resources.displayMetrics
        val pkg = root.packageName?.toString() ?: ""
        val secure = windows.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName == root.packageName && isSecureWindow(it) }
        val keyboard = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val snapshot = Snapshot(
            snapshotId = "s${counter.incrementAndGet()}", packageName = pkg, label = catalog.label(pkg),
            width = metrics.widthPixels, height = metrics.heightPixels, keyboard = keyboard, secure = secure, nodes = walked,
        )
        lastSnapshot = snapshot
        lastNodes = nodes
        return snapshot
    }

    private fun isSecureWindow(window: AccessibilityWindowInfo): Boolean = false // reported by the screenshot path instead

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

    private fun after(done: String): JsonObject {
        Thread.sleep(350)
        val after = runCatching { snapshotNow() }.getOrNull()
        val json = after?.let { s ->
            if (guard.packageVerdict(s.packageName, s.label).isNotEmpty()) null else s.toJson()
        }
        return doneWith(done, json)
    }

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
        // A tap by coordinates is a tap on whatever is drawn there.
        guard.nodeAt(current, x, y)?.let { under -> guard.requireTappable(under, commit = false); guard.requireTypeable(under.takeIf { it.password }) }
        if (!Gestures.dispatch(this, Gestures.tap(x, y))) throw DeviceException("the tap was not delivered", "failed")
        after("tapped $x,$y")
    }

    override fun tapNode(snapshotId: String, node: Int, long: Boolean, commit: Boolean): JsonObject = serial {
        guard.requireActionable(lastSnapshot)
        val (ui, info) = requireNode(snapshotId, node)
        guard.requireTappable(ui, commit)
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        val done = info.performAction(action) || Gestures.dispatch(this, Gestures.tap(ui.centreX, ui.centreY, long))
        if (!done) throw DeviceException("could not tap [$node] '${ui.label}'", "failed")
        after("${if (long) "long-pressed" else "tapped"} [$node] '${ui.label}'")
    }

    override fun typeText(text: String, node: Int): JsonObject = serial {
        guard.requireActionable(lastSnapshot)
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
            ok = inputMethod?.currentInputConnection?.commitText(text, 1, null) ?: false
        }
        if (!ok && target != null) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("otto", text))
            ok = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        if (!ok) throw DeviceException("nothing accepted the text -- tap the field first", "failed")
        after("typed ${text.take(60)}")
    }

    override fun press(key: String): JsonObject = serial {
        val ok = when (key) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "enter" -> findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) ?: false
            else -> throw DeviceException("not a key this knows: $key", "unsupported")
        }
        if (!ok) throw DeviceException("$key was not accepted", "failed")
        after("pressed $key")
    }

    private fun swipeGesture(direction: String, w: Int, h: Int) = when (direction) {
        "up" -> Gestures.swipe(w / 2, (h * 0.7).toInt(), w / 2, (h * 0.3).toInt())
        "down" -> Gestures.swipe(w / 2, (h * 0.3).toInt(), w / 2, (h * 0.7).toInt())
        "left" -> Gestures.swipe((w * 0.8).toInt(), h / 2, (w * 0.2).toInt(), h / 2)
        "right" -> Gestures.swipe((w * 0.2).toInt(), h / 2, (w * 0.8).toInt(), h / 2)
        else -> throw DeviceException("not a direction: $direction", "unsupported")
    }

    override fun swipe(direction: String): JsonObject = serial {
        guard.requireActionable(lastSnapshot ?: snapshotNow())
        val m = resources.displayMetrics
        if (!Gestures.dispatch(this, swipeGesture(direction, m.widthPixels, m.heightPixels))) throw DeviceException("the swipe was not delivered", "failed")
        after("swiped $direction")
    }

    override fun scroll(direction: String, node: Int): JsonObject = serial {
        guard.requireActionable(lastSnapshot ?: snapshotNow())
        val info = if (node >= 0) requireNode(lastSnapshot?.snapshotId ?: "", node).second else null
        val forward = direction == "down" || direction == "right"
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val done = info?.performAction(action) ?: false
        if (!done) {
            val m = resources.displayMetrics
            // Scrolling down means content moves up: the finger swipes up.
            val gesture = swipeGesture(when (direction) { "down" -> "up"; "up" -> "down"; "left" -> "right"; else -> "left" }, m.widthPixels, m.heightPixels)
            if (!Gestures.dispatch(this, gesture)) throw DeviceException("could not scroll", "failed")
        }
        after("scrolled $direction")
    }

    // -- looking -------------------------------------------------------------

    override fun screenshot(): JsonObject = serial {
        val current = lastSnapshot ?: snapshotNow()
        if (guard.packageVerdict(current.packageName, current.label).isNotEmpty()) {
            throw DeviceException(guard.packageVerdict(current.packageName, current.label), "guard", handover = true)
        }
        lastCapture?.let { (at, json) -> if (System.currentTimeMillis() - at < 1000) return@serial json }
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var error = 0
        takeScreenshot(Display.DEFAULT_DISPLAY, actions, object : TakeScreenshotCallback {
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
        lastCapture = System.currentTimeMillis() to json
        json
    }

    // -- apps and settings ---------------------------------------------------

    override fun apps(): JsonObject = buildJsonObject {
        put("apps", JsonArray(catalog.list().map { app -> buildJsonObject { put("label", app.label); put("package", app.packageName) } }))
    }

    override fun launch(packageName: String): JsonObject = serial {
        val why = guard.packageVerdict(packageName, catalog.label(packageName))
        if (why.isNotEmpty()) throw DeviceException(why, "guard", handover = false)
        if (!catalog.launch(packageName)) throw DeviceException("no launchable app called $packageName", "failed")
        Thread.sleep(1500)
        buildJsonObject {
            put("package", packageName); put("label", catalog.label(packageName))
            runCatching { snapshotNow() }.getOrNull()?.let { put("after", it.toJson()) }
        }
    }

    override fun openSettings(page: String, packageName: String): JsonObject = serial {
        if (!SettingsPages.open(this, page, packageName)) throw DeviceException("no Settings page called $page", "unsupported")
        Thread.sleep(1200)
        buildJsonObject {
            put("page", page)
            runCatching { snapshotNow() }.getOrNull()?.let { put("after", it.toJson()) }
        }
    }

    override fun install(packageName: String, query: String): JsonObject = serial {
        guard.requireActionable(null)
        val why = guard.packageVerdict(packageName, query)
        if (why.isNotEmpty()) throw DeviceException("$why -- not installed", "guard", handover = false)
        if (!PlayStore.open(this, packageName, query)) throw DeviceException("the Play Store could not be opened", "unsupported")
        var state = "listing opened"
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(800)
            val snapshot = runCatching { snapshotNow() }.getOrNull() ?: continue
            if (snapshot.packageName != PlayStore.PACKAGE) continue
            val priced = snapshot.nodes.any { PlayStore.PRICE.containsMatchIn(it.label) && it.clickable }
            if (priced) throw DeviceException("this app costs money -- the person decides that", "guard", handover = true)
            val installButton = snapshot.nodes.firstOrNull { it.clickable && it.label.equals("Install", ignoreCase = true) }
            if (installButton != null) {
                val info = lastNodes[installButton.index]
                if (info?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { state = "installing"; break }
            }
            if (snapshot.nodes.any { it.clickable && (it.label.equals("Open", ignoreCase = true) || it.label.equals("Update", ignoreCase = true)) }) { state = "already installed"; break }
        }
        buildJsonObject {
            put("state", state)
            runCatching { snapshotNow() }.getOrNull()?.let { put("after", it.toJson()) }
        }
    }

    companion object {
        @Volatile var instance: OttoAccessibilityService? = null
    }
}

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
    override fun boundsOnScreen(): IntArray {
        val r = android.graphics.Rect(); info.getBoundsInScreen(r)
        return intArrayOf(r.left, r.top, r.right, r.bottom)
    }
    override fun children(): List<WalkNode> = (0 until info.childCount).mapNotNull { info.getChild(it) }.map { AndroidWalkNode(it) }
}
