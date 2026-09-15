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
    /** [left, top, right, bottom] on screen. */
    fun boundsOnScreen(): IntArray
    fun children(): List<WalkNode>
}

object TreeWalker {
    const val MAX_NODES = 400
    const val MAX_DEPTH = 60
    const val MAX_ID = 120

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
     *  back to the platform objects it needs for actions. */
    fun walk(root: WalkNode, maxNodes: Int = MAX_NODES, onKept: (Int, WalkNode) -> Unit = { _, _ -> }): List<UiNode> {
        val out = mutableListOf<UiNode>()
        fun visit(node: WalkNode, depth: Int) {
            if (out.size >= maxNodes || depth > MAX_DEPTH) return
            if (node.isVisibleToUser) {
                val text = node.text.trim()
                val desc = node.contentDescription.trim()
                val keep = text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable || node.isScrollable
                if (keep) {
                    val b = node.boundsOnScreen()
                    if (b.size == 4 && b[2] > b[0] && b[3] > b[1]) {
                        out += UiNode(
                            index = out.size + 1, text = text, desc = desc,
                            role = roleOf(node.className, node.isEditable),
                            left = b[0], top = b[1], right = b[2], bottom = b[3],
                            clickable = node.isClickable, editable = node.isEditable, scrollable = node.isScrollable,
                            password = node.isPassword, focused = node.isFocused,
                            checked = if (node.isCheckable) node.isChecked else null,
                            viewId = node.viewId.substringAfter(":id/").take(MAX_ID),
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
