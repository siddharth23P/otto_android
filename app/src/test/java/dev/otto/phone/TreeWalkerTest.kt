package dev.otto.phone

import dev.otto.phone.access.TreeWalker
import dev.otto.phone.access.WalkNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeWalkerTest {
    private class Fake(
        override val text: String = "", override val contentDescription: String = "", override val className: String = "android.widget.TextView",
        override val isVisibleToUser: Boolean = true, override val isClickable: Boolean = false, override val isEditable: Boolean = false,
        override val isScrollable: Boolean = false, override val isPassword: Boolean = false, override val isFocused: Boolean = false,
        override val isCheckable: Boolean = false, override val isChecked: Boolean = false,
        private val bounds: IntArray = intArrayOf(0, 0, 100, 40), private val kids: List<WalkNode> = emptyList(),
    ) : WalkNode {
        override fun boundsOnScreen() = bounds
        override fun children() = kids
    }

    @Test fun keepsReadableAndActionableNodesInOrderWithIndices() {
        val root = Fake(className = "android.widget.FrameLayout", kids = listOf(
            Fake(text = "Search for products", className = "android.widget.EditText", isEditable = true, isClickable = true),
            Fake(className = "android.view.View"),                        // nothing to read or do: dropped
            Fake(contentDescription = "ADD", className = "android.widget.Button", isClickable = true, bounds = intArrayOf(900, 600, 1040, 660)),
            Fake(text = "hidden", isVisibleToUser = false),
        ))
        val nodes = TreeWalker.walk(root)
        assertEquals(listOf(1, 2), nodes.map { it.index })
        assertEquals("edit-field", nodes[0].role)
        assertEquals("ADD", nodes[1].label)
        assertEquals(970, nodes[1].centreX)
        assertEquals("button", nodes[1].role)
    }

    @Test fun passwordFieldsCarryNoTextInJson() {
        val nodes = TreeWalker.walk(Fake(className = "android.widget.EditText", text = "1234", isEditable = true, isPassword = true))
        assertTrue(nodes[0].password)
        assertTrue(!nodes[0].toJson().toString().contains("1234"))
    }

    @Test fun theWalkIsBounded() {
        val many = Fake(kids = (1..600).map { Fake(text = "row $it") })
        assertEquals(TreeWalker.MAX_NODES, TreeWalker.walk(many).size)
    }
}
