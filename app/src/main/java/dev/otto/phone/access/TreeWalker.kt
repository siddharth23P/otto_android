package dev.otto.phone.access

/** A node as the walker sees it: an interface so the walk is a pure function
 *  (unit-tested on the JVM) and AccessibilityNodeInfo is one implementation. */
interface WalkNode {
    val text: String
    val contentDescription: String
    val className: String
    val isVisibleToUser: Boolean
    val isClickable: Boolean
    val isEditable: Boolean
    val isScrollable: Boolean
    val isPassword: Boolean
    val isFocused: Boolean
    val isCheckable: Boolean
    val isChecked: Boolean
    /** The resource id -- a web element's HTML id -- or "" when it has none. */
    val viewId: String get() = ""
    /** An editable field's hint ("Card number", "Enter UPI ID"): what it asks for, whatever is typed in it. */
    val hint: String get() = ""
    /** android.text.InputType bits, 0 when unknown. */
    val inputType: Int get() = 0
    /** The most characters the field takes, -1 when unlimited or unknown. */
    val maxTextLength: Int get() = -1
    /** A heading (a web page's h1-h6 among them). */
    val isHeading: Boolean get() = false
    /** [left, top, right, bottom] on screen. */
    fun boundsOnScreen(): IntArray
    fun children(): List<WalkNode>
}

object TreeWalker {
    const val MAX_NODES = 400
    const val MAX_DEPTH = 60
    const val MAX_ID = 120
    const val MAX_HINT = 60
    /** Ids of elements not on screen that are collected at most, and the longest kept. */
    const val MAX_OFFSCREEN_IDS = 40
    const val MAX_OFFSCREEN_ID = 60

    /** Words an off-screen id must contain to be worth sending: what a shop's buttons and a checkout's
     *  forms are called. Everything else scrolled out of view says nothing about paying. */
    val PAGE_ID_WORDS = setOf("buy", "cart", "checkout", "order", "pay", "payment", "place", "submit", "ptc", "purchase")

    /** An id's words: split at camelCase and at anything not a letter or digit. */
    fun idWords(id: String): List<String> =
        id.substringAfter(":id/").replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase()
            .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    fun pageId(id: String): Boolean = idWords(id).any { it in PAGE_ID_WORDS }

    /** A field's input type as the guard reads it: `pw` a text password, `numpw` a numeric one (a PIN),
     *  else `num`, `phone`, `email`, `text`; "" when unknown. Bits from android.text.InputType. */
    fun inputKind(inputType: Int): String {
        if (inputType == 0) return ""
        val variation = inputType and 0xff0
        return when (inputType and 0xf) {
            1 -> when (variation) {
                0x80, 0x90, 0xe0 -> "pw"
                0x20, 0xd0 -> "email"
                else -> "text"
            }
            2 -> if (variation == 0x10) "numpw" else "num"
            3 -> "phone"
            else -> ""
        }
    }

    fun roleOf(className: String, editable: Boolean): String {
        val simple = className.substringAfterLast('.').lowercase()
        return when {
            editable || simple.contains("edittext") -> "edit-field"
            simple.contains("button") -> "button"
            simple.contains("checkbox") -> "checkbox"
            simple.contains("switch") || simple.contains("toggle") -> "switch"
            simple.contains("radio") -> "radio"
            simple.contains("image") -> "image"
            simple.contains("recycler") || simple.contains("listview") || simple.contains("scrollview") || simple.contains("viewpager") -> "list"
            simple.contains("webview") -> "web"
            simple.contains("textview") || simple.contains("text") -> "text"
            simple.contains("tab") -> "tab"
            simple.contains("seekbar") || simple.contains("slider") -> "slider"
            else -> "view"
        }
    }

    /** Depth-first, in reading order, keeping what a person can read or act on.
     *  `onKept` receives each kept node's index, so a caller can map indices
     *  back to the platform objects it needs for actions. `offscreenIds`, when
     *  given, collects the ids `wantId` accepts of elements that are not on
     *  screen: a page's Buy Now button scrolled out of view still says what the
     *  page is. */
    fun walk(
        root: WalkNode, maxNodes: Int = MAX_NODES,
        offscreenIds: MutableList<String>? = null, wantId: (String) -> Boolean = { false },
        onKept: (Int, WalkNode) -> Unit = { _, _ -> },
    ): List<UiNode> {
        val out = mutableListOf<UiNode>()
        fun offscreen(node: WalkNode) {
            if (offscreenIds == null || offscreenIds.size >= MAX_OFFSCREEN_IDS) return
            val id = node.viewId.substringAfter(":id/").take(MAX_OFFSCREEN_ID)
            if (id.isNotEmpty() && id !in offscreenIds && wantId(id)) offscreenIds += id
        }
        fun visit(node: WalkNode, depth: Int) {
            if (out.size >= maxNodes || depth > MAX_DEPTH) return
            if (!node.isVisibleToUser) offscreen(node)
            else {
                val text = node.text.trim()
                val desc = node.contentDescription.trim()
                val keep = text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable || node.isScrollable
                val b = node.boundsOnScreen()
                val sized = b.size == 4 && b[2] > b[0] && b[3] > b[1]
                if (!sized) offscreen(node)
                if (keep) {
                    if (sized) {
                        val editable = node.isEditable
                        out += UiNode(
                            index = out.size + 1, text = text, desc = desc,
                            role = roleOf(node.className, node.isEditable),
                            left = b[0], top = b[1], right = b[2], bottom = b[3],
                            clickable = node.isClickable, editable = node.isEditable, scrollable = node.isScrollable,
                            password = node.isPassword, focused = node.isFocused,
                            checked = if (node.isCheckable) node.isChecked else null,
                            viewId = node.viewId.substringAfter(":id/").take(MAX_ID),
                            hint = if (editable) node.hint.trim().take(MAX_HINT) else "",
                            inputKind = if (editable) inputKind(node.inputType) else "",
                            maxLength = if (editable && node.maxTextLength > 0) node.maxTextLength else -1,
                            heading = node.isHeading,
                        )
                        onKept(out.size, node)
                    }
                }
            }
            for (child in node.children()) visit(child, depth + 1)
        }
        visit(root, 0)
        return out
    }
}
