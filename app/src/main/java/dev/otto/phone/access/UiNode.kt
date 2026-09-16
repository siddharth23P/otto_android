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
    /** An editable field's hint, what it asks for. Editable fields only. */
    val hint: String = "",
    /** TreeWalker.inputKind of an editable field: pw, numpw, num, phone, email, text; "" unknown. */
    val inputKind: String = "",
    /** An editable field's longest input, -1 unlimited. */
    val maxLength: Int = -1,
    val heading: Boolean = false,
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
        // Only when set, so every screen before these existed reads the same.
        if (hint.isNotEmpty()) put("h", hint)
        if (inputKind.isNotEmpty()) put("n", inputKind)
        if (maxLength > 0) put("m", maxLength)
        if (heading) put("g", true)
    }
}

/**
 * What the screen as a whole is, beyond its nodes. `seq` counts the package's own window changes, so a
 * judgement of this page can be held while it scrolls and dropped when a new one opens; `activity` is
 * the class the app named for its window; `offscreenIds` are ids of elements not on screen; `kind` is
 * the phone guard's class for this page, "" before it has judged one.
 */
data class PageInfo(
    val seq: Long,
    val activity: String,
    val offscreenIds: List<String> = emptyList(),
    val kind: String = "",
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("seq", seq); put("activity", activity)
        put("offscreen_ids", JsonArray(offscreenIds.map { JsonPrimitive(it) }))
        if (kind.isNotEmpty()) put("kind", kind)
    }
}

/** The whole screen at one moment. `snapshotId` is what a later tap_node must quote. `takenAt` is when
 *  the walk began (uptime); `settled` is false when the screen was still changing as it was read. */
data class Snapshot(
    val snapshotId: String,
    val packageName: String,
    val label: String,
    val width: Int,
    val height: Int,
    val keyboard: Boolean,
    val secure: Boolean,
    val nodes: List<UiNode>,
    val takenAt: Long = 0,
    val settled: Boolean = true,
    val page: PageInfo? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("snapshot_id", snapshotId)
        put("app", buildJsonObject { put("package", packageName); put("label", label) })
        put("screen", buildJsonObject { put("w", width); put("h", height) })
        put("keyboard", keyboard); put("secure", secure); put("settled", settled)
        page?.let { put("page", it.toJson()) }
        put("nodes", JsonArray(nodes.map { it.toJson() }))
    }

    fun node(index: Int): UiNode? = nodes.firstOrNull { it.index == index }
}
