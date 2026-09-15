package dev.otto.phone.bridge

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

    private inline fun envelope(block: () -> JsonElement): String = try {
        Envelope.ok(block()).toString()
    } catch (e: DeviceException) {
        Envelope.error(e.code, e.message ?: "failed", e.handover).toString()
    } catch (e: Exception) {
        Envelope.error("failed", "${e.javaClass.simpleName}: ${e.message}").toString()
    }

    @JvmStatic fun tree(): String = envelope { ops.tree() }
    @JvmStatic fun foreground(): String = envelope { ops.foreground() }
    @JvmStatic fun tap(x: Int, y: Int): String = envelope { ops.tap(x, y) }
    @JvmStatic fun tap_node(snapshotId: String, node: Int, long: Boolean, commit: Boolean): String =
        envelope { ops.tapNode(snapshotId, node, long, commit) }
    @JvmStatic fun type_text(text: String, node: Int): String = envelope { ops.typeText(text, node) }
    @JvmStatic fun press(key: String): String = envelope { ops.press(key) }
    @JvmStatic fun swipe(direction: String): String = envelope { ops.swipe(direction, -1, -1) }
    /** A swipe from a point: otto sends (direction, x, y) when it has one. */
    @JvmStatic fun swipe(direction: String, x: Int, y: Int): String = envelope { ops.swipe(direction, x, y) }
    @JvmStatic fun scroll(direction: String, node: Int): String = envelope { ops.scroll(direction, node) }
    @JvmStatic fun screenshot(): String = envelope { ops.screenshot() }
    @JvmStatic fun apps(): String = envelope { ops.apps() }
    @JvmStatic fun launch(packageName: String): String = envelope { ops.launch(packageName) }
    @JvmStatic fun open_settings(page: String, packageName: String): String = envelope { ops.openSettings(page, packageName) }
    @JvmStatic fun install(packageName: String, query: String): String = envelope { ops.install(packageName, query) }

    /** An agent/embed.py event, as JSON, from the embedded runtime. */
    @JvmStatic fun onEvent(json: String) { EventBus.emit(json) }

    /** Dispatch by name, for the serve transport's device_call frames. */
    fun call(method: String, args: List<JsonElement>): String {
        fun s(i: Int) = args.getOrNull(i)?.toString()?.trim('"') ?: ""
        fun i(i: Int) = s(i).toIntOrNull() ?: -1
        fun b(i: Int) = s(i) == "true"
        return when (method) {
            "tree" -> tree()
            "foreground" -> foreground()
            "tap" -> tap(i(0), i(1))
            "tap_node" -> tap_node(s(0), i(1), b(2), b(3))
            "type_text" -> type_text(s(0), i(1))
            "press" -> press(s(0))
            "swipe" -> swipe(s(0), i(1), i(2))
            "scroll" -> scroll(s(0), i(1))
            "screenshot" -> screenshot()
            "apps" -> apps()
            "launch" -> launch(s(0))
            "open_settings" -> open_settings(s(0), s(1))
            "install" -> install(s(0), s(1))
            else -> Envelope.error("unsupported", "no such device method: $method").toString()
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
    }
}

internal fun doneWith(done: String, after: JsonObject?): JsonObject = buildJsonObject {
    put("done", done)
    if (after != null) put("after", after)
}
