package dev.otto.phone.bridge

import dev.otto.phone.actions.PhoneActions
import dev.otto.phone.log.OttoLog
import dev.otto.phone.transport.EventBus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What Python calls. Every method is static, takes positional arguments in
 * the order agent/phone/backend.py documents, and returns a JSON envelope as a
 * String. The embedded transport reaches these through Chaquopy
 * (`jclass("dev.otto.phone.bridge.PyBridge")`); the serve transport reaches
 * them by name from a `device_call` frame. Both go through the same guard.
 */
object PyBridge {
    @Volatile var ops: DeviceOps = DeviceOps.Unavailable

    /** Every device op, named, timed and logged (OttoDevice): a refusal says its code and whether it
     *  handed the phone over. Arguments are not logged -- one of them is text being typed. */
    private inline fun envelope(op: String, block: () -> JsonElement): JsonObject {
        val started = System.nanoTime()
        return try {
            Envelope.ok(block()).also { OttoLog.i(TAG, "$op ok [${since(started)} ms]") }
        } catch (e: DeviceException) {
            OttoLog.w(TAG, "$op ${e.code}${if (e.handover) " (handed over)" else ""}: ${e.message} [${since(started)} ms]")
            Envelope.error(e.code, e.message ?: "failed", e.handover)
        } catch (e: Exception) {
            OttoLog.e(TAG, "$op failed [${since(started)} ms]", e)
            Envelope.error("failed", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun since(started: Long): Long = (System.nanoTime() - started) / 1_000_000
    private const val TAG = "OttoDevice"

    @JvmStatic fun tree(): String = envelope("tree") { ops.tree() }.toString()
    @JvmStatic fun foreground(): String = envelope("foreground") { ops.foreground() }.toString()
    @JvmStatic fun tap(x: Int, y: Int): String = envelope("tap") { ops.tap(x, y) }.toString()
    @JvmStatic fun tap_node(snapshotId: String, node: Int, long: Boolean, commit: Boolean): String =
        envelope("tapNode") { ops.tapNode(snapshotId, node, long, commit) }.toString()
    @JvmStatic fun type_text(text: String, node: Int): String = envelope("typeText") { ops.typeText(text, node) }.toString()
    @JvmStatic fun press(key: String): String = envelope("press") { ops.press(key) }.toString()
    @JvmStatic fun swipe(direction: String): String = envelope("swipe") { ops.swipe(direction, -1, -1) }.toString()
    /** A swipe from a point: otto sends (direction, x, y) when it has one. */
    @JvmStatic fun swipe(direction: String, x: Int, y: Int): String = envelope("swipe") { ops.swipe(direction, x, y) }.toString()
    @JvmStatic fun scroll(direction: String, node: Int): String = envelope("scroll") { ops.scroll(direction, node) }.toString()
    @JvmStatic fun screenshot(): String = envelope("screenshot") { ops.screenshot() }.toString()
    @JvmStatic fun apps(): String = envelope("apps") { ops.apps() }.toString()
    @JvmStatic fun launch(packageName: String): String = envelope("launch") { ops.launch(packageName) }.toString()
    @JvmStatic fun open_settings(page: String, packageName: String): String = envelope("openSettings") { ops.openSettings(page, packageName) }.toString()
    @JvmStatic fun install(packageName: String, query: String): String = envelope("install") { ops.install(packageName, query) }.toString()
    /** The phone actions otto may ask for (actions/ActionCatalog.kt), as {"actions": [spec...]}. */
    @JvmStatic fun actions(): String = envelope("actions") { PhoneActions.menu() }.toString()
    /** One phone action by name, with its arguments as a JSON object. */
    @JvmStatic fun run_action(name: String, argsJson: String): String = envelope("action $name") { ops.runAction(name, parseArgs(argsJson)) }.toString()

    private fun parseArgs(argsJson: String): JsonObject =
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(argsJson.ifBlank { "{}" }) as JsonObject }
            .getOrElse { throw DeviceException("the action's arguments are not a JSON object", "invalid") }

    /** An agent/embed.py event, as JSON, from the embedded runtime. */
    @JvmStatic fun onEvent(json: String) { EventBus.emit(json) }

    /** Dispatch by name, for the serve transport's device_call frames. */
    fun call(method: String, args: List<JsonElement>): String = callJson(method, args).toString()

    /** [call] as the envelope object: the serve transport puts it in a frame as it is, rather than
     *  printing a snapshot of a few hundred nodes and parsing it straight back. */
    fun callJson(method: String, args: List<JsonElement>): JsonObject {
        fun s(i: Int) = args.getOrNull(i)?.toString()?.trim('"') ?: ""
        fun i(i: Int) = s(i).toIntOrNull() ?: -1
        fun b(i: Int) = s(i) == "true"
        return when (method) {
            "tree" -> envelope("tree") { ops.tree() }
            "foreground" -> envelope("foreground") { ops.foreground() }
            "tap" -> envelope("tap") { ops.tap(i(0), i(1)) }
            "tap_node" -> envelope("tapNode") { ops.tapNode(s(0), i(1), b(2), b(3)) }
            "type_text" -> envelope("typeText") { ops.typeText(s(0), i(1)) }
            "press" -> envelope("press") { ops.press(s(0)) }
            "swipe" -> envelope("swipe") { ops.swipe(s(0), i(1), i(2)) }
            "scroll" -> envelope("scroll") { ops.scroll(s(0), i(1)) }
            "screenshot" -> envelope("screenshot") { ops.screenshot() }
            "apps" -> envelope("apps") { ops.apps() }
            "launch" -> envelope("launch") { ops.launch(s(0)) }
            "open_settings" -> envelope("openSettings") { ops.openSettings(s(0), s(1)) }
            "install" -> envelope("install") { ops.install(s(0), s(1)) }
            "actions" -> envelope("actions") { PhoneActions.menu() }
            "run_action" -> envelope("action ${s(0)}") {
                ops.runAction(s(0), (args.getOrNull(1) as? JsonObject) ?: parseArgs(s(1)))
            }
            else -> Envelope.error("unsupported", "no such device method: $method")
        }
    }
}

/** The device operations behind the bridge. The accessibility service binds
 *  the real one; `Unavailable` says what is missing rather than crashing. */
interface DeviceOps {
    fun tree(): JsonObject
    fun foreground(): JsonObject
    fun tap(x: Int, y: Int): JsonObject
    fun tapNode(snapshotId: String, node: Int, long: Boolean, commit: Boolean): JsonObject
    fun typeText(text: String, node: Int): JsonObject
    fun press(key: String): JsonObject
    /** From x,y when both are >= 0, else across the middle of the screen. */
    fun swipe(direction: String, x: Int, y: Int): JsonObject
    fun scroll(direction: String, node: Int): JsonObject
    fun screenshot(): JsonObject
    fun apps(): JsonObject
    fun launch(packageName: String): JsonObject
    fun openSettings(page: String, packageName: String): JsonObject
    fun install(packageName: String, query: String): JsonObject
    /** A phone action by name (actions/PhoneActions.kt). */
    fun runAction(name: String, args: JsonObject): JsonObject

    object Unavailable : DeviceOps {
        private fun off(): Nothing = throw DeviceException(
            "Otto's accessibility service is not enabled -- turn it on in Settings > Accessibility", "unsupported")
        override fun tree() = off()
        override fun foreground() = off()
        override fun tap(x: Int, y: Int) = off()
        override fun tapNode(snapshotId: String, node: Int, long: Boolean, commit: Boolean) = off()
        override fun typeText(text: String, node: Int) = off()
        override fun press(key: String) = off()
        override fun swipe(direction: String, x: Int, y: Int) = off()
        override fun scroll(direction: String, node: Int) = off()
        override fun screenshot() = off()
        override fun apps() = off()
        override fun launch(packageName: String) = off()
        override fun openSettings(page: String, packageName: String) = off()
        override fun install(packageName: String, query: String) = off()
        override fun runAction(name: String, args: JsonObject) = off()
    }
}

internal fun doneWith(done: String, after: JsonObject?): JsonObject = buildJsonObject {
    put("done", done)
    if (after != null) put("after", after)
}
