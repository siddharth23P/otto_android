package dev.otto.phone

import dev.otto.phone.access.PageInfo
import dev.otto.phone.access.Snapshot
import dev.otto.phone.access.UiNode
import dev.otto.phone.bridge.DeviceException
import dev.otto.phone.guard.GuardRules
import dev.otto.phone.guard.GuardRulesError
import dev.otto.phone.guard.PolicyGuard
import dev.otto.phone.guard.PolicyGuard.Control
import dev.otto.phone.guard.PolicyGuard.PageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The guard's enforcement and its word-level verdicts. Whole pages are PageCorpusTest's: the same corpus
 *  and the same numbers as otto's guard. */
class PolicyGuardTest {
    private val rulesText = File("src/main/assets/guard_rules.json").readText()
    private val rules = GuardRules.parse(rulesText)
    private val guard = PolicyGuard(rules)

    private fun node(i: Int, text: String, editable: Boolean = false, password: Boolean = false, clickable: Boolean = false,
                     top: Int = 0, bottom: Int = 40, role: String = if (editable) "edit-field" else "text", viewId: String = "",
                     focused: Boolean = false, hint: String = "", inputKind: String = "", maxLength: Int = -1) =
        UiNode(i, text, "", role, 0, top, 1080, bottom, clickable, editable, false, password, focused, null, viewId,
            hint = hint, inputKind = inputKind, maxLength = maxLength)

    private fun screen(pkg: String, label: String, vararg nodes: UiNode, secure: Boolean = false, page: PageInfo? = null) =
        Snapshot("s1", pkg, label, 1080, 2400, false, secure, nodes.toList(), takenAt = 1000, page = page)

    private inline fun refused(code: String, handover: Boolean, block: () -> Unit) {
        try { block(); fail("expected a $code refusal") }
        catch (e: DeviceException) { assertEquals(e.message, code, e.code); assertEquals(e.message, handover, e.handover) }
    }

    @Test fun moneyAppsAreWordsOfTheAppNotLettersInsideOne() {
        assertTrue(guard.packageVerdict("com.phonepe.app", "PhonePe").contains("payment or banking"))
        assertTrue(guard.packageVerdict("com.example.superbank", "SuperBank").contains("money-related"))
        assertTrue(guard.packageVerdict("com.hdfcbank.android.now", "HDFC Bank").contains("money-related"))
        assertTrue(guard.packageVerdict("", "PhonePe").contains("payment or banking"))
        for ((pkg, label) in listOf("com.grofers.customerapp" to "Blinkit", "com.mxtech.videoplayer.ad" to "MX Player",
                "com.google.android.calendar" to "Calendar", "com.google.android.apps.bard" to "Gemini",
                "com.samsung.android.app.reminder" to "Reminder", "com.example.payload" to "Payload", "" to "Otherwise Notes")) {
            assertEquals(pkg, "", guard.packageVerdict(pkg, label))
        }
    }

    @Test fun aFieldAskingForASecretMakesTheScreenSecure() {
        assertTrue(guard.secureReason(screen("com.example.shop", "Shop", node(1, "Verify"), node(2, "Enter OTP", editable = true))).contains("payment or sign-in"))
        assertTrue(guard.secureReason(screen("com.example.shop", "Shop", node(1, "Enter your OTP"), node(2, "Enter code", editable = true))).isNotEmpty())
        assertTrue(guard.secureReason(screen("com.example.shop", "Shop", node(1, "", editable = true, inputKind = "numpw"))).isNotEmpty())
        assertTrue(guard.secureReason(screen("com.example.shop", "Shop", node(1, "", editable = true, hint = "Card number"))).isNotEmpty())
        assertTrue(guard.secureReason(screen("com.example.shop", "Shop", secure = true)).contains("protected"))
        val pinPad = screen("in.amazon.mShop.android.shopping", "Amazon", node(1, "", clickable = true),
            page = PageInfo(4, "org.npci.upi.security.pinactivitycomponent.GetCredential"))
        assertTrue(guard.secureReason(pinPad).contains("payment screen"))
        assertEquals("", guard.secureReason(screen("com.whatsapp", "WhatsApp", node(1, "Mom: the OTP for the parcel is 4471"), node(2, "Type a message", editable = true))))
        assertEquals("", guard.secureReason(screen("in.amazon.mShop.android.shopping", "Amazon", node(1, "PIN code"),
            node(2, "", editable = true, hint = "6 digits [0-9] PIN code", inputKind = "num", maxLength = 6))))
    }

    @Test fun aControlIsJudgedByItsOwnShortLabelAndItsId() {
        for (label in listOf("Pay", "Buy", "Pay ₹499", "Buy · ₹1,299", "Buy now", "PLACE YOUR ORDER", "Proceed to Pay ₹28", "Pаy now", "Pay​now", "Ｐａｙ now")) {
            assertEquals(label, Control.PAY, guard.controlVerdict(label))
        }
        for (label in listOf("Proceed to checkout", "Proceed to Buy (1 item)", "Continue to checkout")) assertEquals(label, Control.ENTRY, guard.controlVerdict(label))
        for (label in listOf("Send", "Delete chat", "Ѕend")) assertEquals(label, Control.COMMIT, guard.controlVerdict(label))
        for (label in listOf("Continue", "Deliver to this address")) assertEquals(label, Control.FORWARD, guard.controlVerdict(label))
        for (label in listOf("Buy for ₹59,850 with HDFC Bank credit card", "Buy again", "Verified Purchase", "Transfer files", "iphone delete", "Payload", "Sending…", "ADD")) {
            assertEquals(label, Control.NONE, guard.controlVerdict(label))
        }
        assertEquals(Control.PAY, guard.controlVerdict("Submit", "buy-now-button"))
        assertEquals(Control.COMMIT, guard.controlVerdict("Submit", "add-to-cart-button"))
        assertEquals(Control.NONE, guard.controlVerdict("", "sc-buy-box-gift-checkbox"))
        assertEquals("buy now button", guard.idWords("in.amazon.mShop.android.shopping:id/buyNowButton"))
        assertEquals("pay now", guard.normal("  Pay​  NOW "))
    }

    @Test fun aPayControlIsRefusedOnAnyPageWithoutHandingOver() {
        val g = PolicyGuard(rules)
        refused("refused", handover = false) { g.requireTappable(node(1, "Pay now", clickable = true), commit = true) }
        refused("refused", handover = false) { g.requireTappable(node(6, "Submit", clickable = true, viewId = "buy-now-button"), commit = true) }
        assertTrue(!g.handedOver)
        refused("refused", handover = false) { g.requireTappable(node(1, "Send", clickable = true), commit = false) }
        g.requireTappable(node(1, "Send", clickable = true), commit = true)
        refused("refused", handover = false) { g.requireTappable(node(1, "Proceed to checkout", clickable = true), commit = false, kind = PageKind.CART) }
        g.requireTappable(node(1, "Proceed to checkout", clickable = true), commit = true, kind = PageKind.CART)
        g.requireTappable(node(1, "Continue", clickable = true), commit = false, kind = PageKind.NONE)
        refused("refused", handover = false) { g.requireTappable(node(1, "Deliver to this address", clickable = true), commit = false, kind = PageKind.CHECKOUT) }
    }

    private val checkout = screen("com.example.shop", "Shop", node(1, "Qty"), node(2, "2", editable = true, focused = true),
        node(3, "Total ₹56", top = 100, bottom = 140), node(4, "Pay", clickable = true, top = 200, bottom = 300, role = "button"))

    @Test fun nothingOnAPaymentPageIsTouchedAndThePersonTakesOver() {
        val g = PolicyGuard(rules)
        assertEquals(PageKind.PAYMENT, g.kindOf(checkout))
        refused("guard", handover = true) { g.requireInteractive(checkout) }
        assertTrue(g.handedOver)
        refused("guard", handover = true) { PolicyGuard(rules).requireSubmit(checkout) }
        refused("refused", handover = false) { PolicyGuard(rules).requireCapturable(checkout) }
    }

    @Test fun enterIsPressedUnlessThePageHasSomethingItCouldSubmit() {
        val search = node(1, "Search or ask a question", editable = true, focused = true, viewId = "rs_search_src_text")
        val offer = node(2, "₹71,599 M.R.P: ₹1,09,999 (35% off) Buy for ₹71,549 with HDFC Bank credit card", clickable = true, top = 1000, bottom = 1100, role = "view")
        val quantity = node(1, "Quantity", editable = true, focused = true)
        val buyNow = node(3, "Submit", clickable = true, top = 2180, bottom = 2320, role = "button", viewId = "buy-now-button")
        PolicyGuard(rules).requireSubmit(screen("in.amazon.mShop.android.shopping", "Amazon", quantity, offer))
        PolicyGuard(rules).requireSubmit(screen("in.amazon.mShop.android.shopping", "Amazon", search, buyNow))
        val g = PolicyGuard(rules)
        refused("refused", handover = false) { g.requireSubmit(screen("in.amazon.mShop.android.shopping", "Amazon", quantity, buyNow)) }
        assertTrue(!g.handedOver)
        PolicyGuard(rules).requireSubmit(screen("com.whatsapp", "WhatsApp", node(1, "Type a message", editable = true), node(2, "Send", clickable = true)))
    }

    @Test fun aBlindTapIsRefusedOnAScreenThatHasElementsAndNeedsALookOnOneThatHasNone() {
        val chat = screen("com.whatsapp", "WhatsApp", node(1, "Type a message", editable = true), node(2, "Send", clickable = true))
        refused("refused", handover = false) { guard.requireBlindTap(chat, chat.snapshotId) }
        val game = screen("com.example.game", "Blocks")
        for (looked in listOf<String?>(null, "other-id")) refused("refused", handover = false) { guard.requireBlindTap(game, looked) }
        guard.requireBlindTap(game, game.snapshotId)
    }

    @Test fun handoverBlocksUntilResume() {
        val g = PolicyGuard(rules)
        g.handedOver = true
        refused("guard", handover = true) { g.requireActionable(null) }
        g.handedOver = false
        g.requireActionable(null)
        g.allowedToAct = false
        refused("guard", handover = false) { g.requireActionable(null) }
    }

    private fun row(i: Int, text: String, top: Int, clickable: Boolean = false, viewId: String = "") =
        UiNode(i, text, "", if (clickable) "button" else "text", 0, top, 1080, top + 100, clickable, false, false, false, false, null, viewId)

    private val address = Snapshot("s1", "com.example.shop", "Shop", 1080, 2400, false, false,
        listOf(row(1, "Pick your location", 100), row(2, "Continue", 2200, clickable = true)), takenAt = 1000)

    @Test fun aFreshWalkThatScoresAPaymentPageHandsOver() {
        val g = PolicyGuard(rules)
        g.requireTappable(address.node(2)!!, commit = false, kind = g.kindOf(address))  // the old read alone allows it
        val fresh = address.copy(snapshotId = "s2", takenAt = 1400,
            nodes = listOf(row(1, "Order summary", 100), row(3, "Order total ₹499", 1900), row(2, "Continue", 2200, clickable = true)))
        refused("guard", handover = true) { g.requireStillTappable(address, fresh, 2, commit = false) }
        assertTrue(g.handedOver)
    }

    @Test fun anElementThatChangedSinceTheReadIsStale() {
        val g = PolicyGuard(rules)
        val relabelled = address.copy(nodes = listOf(row(1, "Pick your location", 100), row(2, "Remove address", 2200, clickable = true)))
        val moved = address.copy(nodes = listOf(row(1, "Pick your location", 100), row(2, "Continue", 900, clickable = true)))
        for (fresh in listOf(relabelled, moved)) refused("stale", handover = false) { g.requireStillTappable(address, fresh, 2, commit = false) }
        val shifted = address.copy(nodes = listOf(row(1, "Deliver to Home", 50), row(2, "Pick your location", 100), row(3, "Continue", 2210, clickable = true)))
        assertEquals(3, g.requireStillTappable(address, shifted, 2, commit = false).index)
        assertTrue(!g.handedOver)
    }

    @Test fun aPaymentAppInFrontOnTheFreshWalkHandsOver() {
        refused("guard", handover = true) {
            PolicyGuard(rules).requireStillTappable(address, address.copy(packageName = "com.phonepe.app", label = "PhonePe"), 2, commit = false)
        }
    }

    @Test fun aTapByPointIsJudgedOnWhatIsThereNow() {
        val g = PolicyGuard(rules)
        val dialog = address.copy(nodes = address.nodes + row(3, "Pay ₹499", 2200, clickable = true).copy(left = 100, right = 980))
        refused("stale", handover = false) { g.requireStillAtPoint(address, dialog, 540, 2250) }
        assertEquals(2, g.requireStillAtPoint(address, address.copy(takenAt = 2000), 540, 2250)?.index)
        val game = Snapshot("s9", "com.example.game", "Blocks", 1080, 2400, false, false, emptyList())
        assertEquals(null, g.requireStillAtPoint(game, game, 540, 1200))
        refused("refused", handover = false) {
            g.requireStillAtPoint(game, game.copy(nodes = listOf(row(1, "Tap", 0).copy(right = 10, bottom = 10, clickable = true))), 540, 1200)
        }
    }

    @Test fun aJudgementIsMadeAgainWhenTheScreenMayHaveMoved() {
        assertTrue(!guard.needsRecheck(null, 5000))
        assertTrue(!guard.needsRecheck(address, 1000))
        assertTrue(guard.needsRecheck(address, 1001))
        assertTrue(guard.needsRecheck(address.copy(settled = false), 900))
    }

    @Test fun passwordFieldsAreNeverTyped() {
        refused("guard", handover = true) { guard.requireTypeable(node(1, "", password = true)) }
        guard.requireTypeable(node(1, "Search", editable = true))
    }

    @Test fun theElementUnderAPoint() {
        val big = UiNode(1, "Cart", "", "list", 0, 0, 1080, 2400, false, false, true, false, false, null)
        val pay = UiNode(2, "Pay now", "", "button", 60, 2200, 1020, 2300, true, false, false, false, false, null)
        val snapshot = Snapshot("s", "com.grofers.customerapp", "Blinkit", 1080, 2400, false, false, listOf(big, pay))
        assertEquals(2, guard.nodeAt(snapshot, 540, 2250)?.index)
        assertEquals(1, guard.nodeAt(snapshot, 10, 10)?.index)
        assertEquals(null, guard.nodeAt(snapshot, 5000, 5000))
    }

    @Test fun aBrokenRulesFileIsANamedErrorNotAGuess() {
        for ((text, why) in listOf("{not json" to "unreadable", "[]" to "not an object",
                """{"version": 3, "pay_controls": {}}""" to "lacks", rulesText.replaceFirst("\"version\": 3", "\"version\": 2") to "version 2")) {
            try { GuardRules.parse(text); fail("expected $why") } catch (e: GuardRulesError) { assertTrue(e.message, e.message!!.contains(why)) }
        }
    }
}
