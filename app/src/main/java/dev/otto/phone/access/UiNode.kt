package dev.otto.phone.access

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One element of the screen, in the shape agent/phone/digest.py reads. */
data class UiNode(
    val index: Int,
    val text: String,
    val desc: String,
    val role: String,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val password: Boolean,
    val focused: Boolean,
    val checked: Boolean?,
    /** The resource id, the app's package prefix dropped. A web page's form buttons can all read
     *  "Submit" and say what they do only here (Amazon's add-to-cart-button, buy-now-button). */
    val viewId: String = "",
) {
    val label: String get() = text.ifBlank { desc }.trim()
    val centreX: Int get() = (left + right) / 2
    val centreY: Int get() = (top + bottom) / 2

    fun toJson(): JsonObject = buildJsonObject {
        put("i", index); put("t", if (password) "" else text); put("d", if (password) "" else desc); put("r", role)
        put("b", buildJsonArray { add(JsonPrimitive(left)); add(JsonPrimitive(top)); add(JsonPrimitive(right)); add(JsonPrimitive(bottom)) })
        put("c", clickable); put("e", editable); put("s", scrollable); put("p", password); put("f", focused)
        put("k", checked?.let { JsonPrimitive(it) } ?: JsonNull)
        put("v", viewId)
    }
}

/** The whole screen at one moment. `snapshotId` is what a later tap_node must quote. */
data class Snapshot(
    val snapshotId: String,
    val packageName: String,
    val label: String,
    val width: Int,
    val height: Int,
    val keyboard: Boolean,
    val secure: Boolean,
    val nodes: List<UiNode>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("snapshot_id", snapshotId)
        put("app", buildJsonObject { put("package", packageName); put("label", label) })
        put("screen", buildJsonObject { put("w", width); put("h", height) })
        put("keyboard", keyboard); put("secure", secure)
        put("nodes", JsonArray(nodes.map { it.toJson() }))
    }

    fun node(index: Int): UiNode? = nodes.firstOrNull { it.index == index }
}
