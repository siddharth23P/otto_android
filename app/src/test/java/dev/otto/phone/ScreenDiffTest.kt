package dev.otto.phone

import dev.otto.phone.access.ScreenDiff
import dev.otto.phone.access.Snapshot
import dev.otto.phone.access.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScreenDiffTest {
    private fun row(i: Int, text: String, top: Int, viewId: String = "") =
        UiNode(i, text, "", "text", 0, top, 1080, top + 100, false, false, false, false, false, null, viewId)

    private val list = UiNode(3, "", "", "list", 0, 400, 1080, 2000, false, false, true, false, false, null)

    private fun screen(id: String, vararg nodes: UiNode, takenAt: Long = 0) =
        Snapshot(id, "com.example.shop", "Shop", 1080, 2400, false, false, nodes.toList(), takenAt = takenAt)

    @Test fun anUnmovedScreenHasTheSameSignature() {
        val a = screen("s1", row(1, "Cart", 100), list, row(4, "Milk", 500), row(5, "Bread", 600))
        val b = screen("s2", row(1, "Cart", 100), list, row(4, "Milk", 500), row(5, "Bread", 600), takenAt = 900)
        assertEquals(ScreenDiff.signature(a, null), ScreenDiff.signature(b, null))
        assertEquals(ScreenDiff.signature(a, list), ScreenDiff.signature(b, list))
    }

    @Test fun aScrolledListHasAnother() {
        val a = screen("s1", row(1, "Cart", 100), list, row(4, "Milk", 500), row(5, "Bread", 600))
        val scrolled = screen("s2", row(1, "Cart", 100), list, row(4, "Milk", 300), row(5, "Bread", 400), row(6, "Eggs", 500))
        assertNotEquals(ScreenDiff.signature(a, list), ScreenDiff.signature(scrolled, list))
        val relabelled = screen("s3", row(1, "Cart", 100), list, row(4, "Milk", 500), row(5, "Bread", 600, viewId = "item"))
        assertNotEquals(ScreenDiff.signature(a, list), ScreenDiff.signature(relabelled, list))
    }

    @Test fun onlyWhatLiesInTheRegionCounts() {
        val a = screen("s1", row(1, "Cart (1)", 100), list, row(4, "Milk", 500), row(7, "Total ₹28", 2100))
        val outside = screen("s2", row(1, "Cart (2)", 100), list, row(4, "Milk", 500), row(7, "Total ₹56", 2100))
        assertEquals(ScreenDiff.signature(a, list), ScreenDiff.signature(outside, list))
        assertNotEquals(ScreenDiff.signature(a, null), ScreenDiff.signature(outside, null))
    }
}
