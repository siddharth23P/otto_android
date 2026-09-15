package dev.otto.phone

import dev.otto.phone.access.Snapshot
import dev.otto.phone.access.UiNode
import dev.otto.phone.bridge.DeviceException
import dev.otto.phone.guard.GuardRules
import dev.otto.phone.guard.PolicyGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PolicyGuardTest {
    private val rules = GuardRules.parse(File("src/main/assets/guard_rules.json").readText())
    private val guard = PolicyGuard(rules)

    private fun node(i: Int, text: String, editable: Boolean = false, password: Boolean = false, clickable: Boolean = false) =
        UiNode(i, text, "", if (editable) "edit-field" else "text", 0, 0, 100, 40, clickable, editable, false, password, false, null)

    private fun screen(pkg: String, label: String, vararg nodes: UiNode, secure: Boolean = false) =
        Snapshot("s1", pkg, label, 1080, 2400, false, secure, nodes.toList())

    @Test fun deniedAndMoneyWordedPackages() {
        assertTrue(guard.packageVerdict("com.phonepe.app", "PhonePe").contains("payment or banking"))
        assertTrue(guard.packageVerdict("com.example.superbank", "SuperBank").contains("money-related"))
        assertEquals("", guard.packageVerdict("com.grofers.customerapp", "Blinkit"))
        assertEquals("", guard.packageVerdict("com.mxtech.videoplayer.ad", "MX Player"))
    }

    @Test fun aSensitiveWordAloneIsNotAPaymentScreen() {
        assertEquals("", guard.screenVerdict(screen("com.whatsapp", "WhatsApp", node(1, "Mom: the OTP for the parcel is 4471"), node(2, "Type a message", editable = true))))
        assertEquals("", guard.screenVerdict(screen("com.grofers.customerapp", "Blinkit", node(1, "₹28"), node(2, "Proceed to Pay ₹28", clickable = true))))
    }

    @Test fun anInputFieldAskingForTheSecretIs() {
        assertTrue(guard.screenVerdict(screen("com.example.shop", "Shop", node(1, "Verify"), node(2, "Enter OTP", editable = true))).contains("payment or sign-in"))
        assertTrue(guard.screenVerdict(screen("com.example.shop", "Shop", node(1, "CVV"), secure = true)).contains("secure window"))
    }

    @Test fun payWordsAreNeverTappableAndCommitWordsNeedCommit() {
        assertEquals(PolicyGuard.Target.PAY, guard.targetVerdict("Proceed to Pay ₹28"))
        assertEquals(PolicyGuard.Target.PAY, guard.targetVerdict("PLACE YOUR ORDER"))
        assertEquals(PolicyGuard.Target.COMMIT, guard.targetVerdict("Send"))
        assertEquals(PolicyGuard.Target.NONE, guard.targetVerdict("Sending…"))
        assertEquals(PolicyGuard.Target.COMMIT, guard.targetVerdict("Proceed to checkout"))  // the person's call, once
        try { guard.requireTappable(node(1, "Pay now", clickable = true), commit = true); fail("expected a guard refusal") }
        catch (e: DeviceException) { assertEquals("guard", e.code); assertTrue(e.handover) }
        try { guard.requireTappable(node(1, "Send", clickable = true), commit = false); fail("expected a refusal") }
        catch (e: DeviceException) { assertEquals("guard", e.code) }
        guard.requireTappable(node(1, "Send", clickable = true), commit = true)
    }

    @Test fun theBareWordsArePayWordsMatchedWhole() {
        for (label in listOf("Pay", "PAY", "Buy", "Purchase", "Subscribe", "Pay ₹499", "Buy · ₹1,299", "Order now")) {
            assertEquals(label, PolicyGuard.Target.PAY, guard.targetVerdict(label))
        }
        assertEquals(PolicyGuard.Target.NONE, guard.targetVerdict("Payload"))
        assertEquals(PolicyGuard.Target.NONE, guard.targetVerdict("Buyer's guide"))
        assertEquals(PolicyGuard.Target.NONE, guard.targetVerdict("Player"))
    }

    @Test fun aForwardWordIsAPayWordOnlyOnACheckoutScreen() {
        val checkout = listOf("Order summary", "Amul Taaza Toned Milk 500 ml x1", "Total ₹28", "Continue")
        assertEquals(PolicyGuard.Target.PAY, guard.targetVerdict("Continue", checkout))
        assertEquals(PolicyGuard.Target.PAY, guard.targetVerdict("Next", listOf("Payment method", "UPI", "Next")))
        assertEquals(PolicyGuard.Target.PAY, guard.targetVerdict("Confirm", listOf("Grand total ₹1,299", "Confirm")))
        assertEquals(PolicyGuard.Target.NONE, guard.targetVerdict("Continue", listOf("Welcome to Blinkit", "Pick your location", "Continue")))
        assertEquals(PolicyGuard.Target.COMMIT, guard.targetVerdict("Confirm", listOf("Delete this chat?", "Confirm")))
        assertEquals(PolicyGuard.Target.NONE, guard.targetVerdict("Continue"))
        assertEquals(PolicyGuard.Target.PAY, guard.targetVerdict("ReviewOrder", checkout))  // a phrase, squashed like a pay word
        assertEquals("Order summary", guard.checkoutContext(checkout))
        assertEquals("", guard.checkoutContext(listOf("Step 2 of 3")))
        try { guard.requireTappable(node(1, "Continue", clickable = true), commit = true, texts = checkout); fail("expected a guard refusal") }
        catch (e: DeviceException) { assertTrue(e.handover) }
    }

    @Test fun lookAlikeLettersFromOtherScriptsDoNotHideAWord() {
        assertEquals(PolicyGuard.Target.PAY, guard.targetVerdict("P\u0430y now"))      // Cyrillic а
        assertEquals(PolicyGuard.Target.COMMIT, guard.targetVerdict("\u0405end"))      // Cyrillic Ѕ
        assertEquals(PolicyGuard.Target.PAY, guard.targetVerdict("\u0392uy"))          // Greek Β
        assertTrue(guard.packageVerdict("com.example.b\u0430nk", "").isNotEmpty())
        assertEquals("pay", guard.normal("P\u0430y"))
    }

    @Test fun enterIsJudgedByTheScreenItWouldSubmit() {
        val checkout = screen("com.example.shop", "Shop", node(1, "Qty"), node(2, "2", editable = true), node(3, "Total ₹56"), node(4, "Pay", clickable = true))
        try { PolicyGuard(rules).requireSubmit(checkout); fail("expected a refusal") } catch (e: DeviceException) { assertTrue(e.handover) }
        PolicyGuard(rules).requireSubmit(screen("com.whatsapp", "WhatsApp", node(1, "Type a message", editable = true), node(2, "Send", clickable = true)))
    }

    @Test fun aBlindTapIsRefusedOnAScreenThatHasElements() {
        val chat = screen("com.whatsapp", "WhatsApp", node(1, "Type a message", editable = true), node(2, "Send", clickable = true))
        try { guard.requireBlindTap(chat, chat.snapshotId); fail("expected a refusal") } catch (e: DeviceException) { assertEquals("guard", e.code) }
        guard.requireBlindTap(screen("com.example.game", "Blocks"), "s1")
    }

    @Test fun aBlindTapOnAnElementlessScreenNeedsALookOnThatCapture() {
        val game = screen("com.example.game", "Blocks")
        for (looked in listOf<String?>(null, "other-id")) {
            try { guard.requireBlindTap(game, looked); fail("expected a refusal") }
            catch (e: DeviceException) { assertEquals("guard", e.code); assertTrue(!e.handover) }
        }
        guard.requireBlindTap(game, game.snapshotId)
    }

    @Test fun handoverBlocksUntilResume() {
        val g = PolicyGuard(rules)
        g.handedOver = true
        try { g.requireActionable(null); fail("expected a refusal") } catch (e: DeviceException) { assertTrue(e.handover) }
        g.handedOver = false
        g.requireActionable(null)
        g.allowedToAct = false
        try { g.requireActionable(null); fail("expected a refusal") } catch (e: DeviceException) { assertEquals("guard", e.code) }
    }

    private fun row(i: Int, text: String, top: Int, clickable: Boolean = false, viewId: String = "") =
        UiNode(i, text, "", if (clickable) "button" else "text", 0, top, 1080, top + 100, clickable, false, false, false, false, null, viewId)

    private val address = Snapshot("s1", "com.example.shop", "Shop", 1080, 2400, false, false,
        listOf(row(1, "Delivery address", 100), row(2, "Continue", 2200, clickable = true)), takenAt = 1000)

    @Test fun aTotalThatAppearedAfterTheReadMakesTheTapAPayment() {
        val g = PolicyGuard(rules)
        g.requireTappable(address.node(2)!!, commit = false, texts = address.nodes.map { it.label })  // the old read alone allows it
        val fresh = address.copy(snapshotId = "s2", takenAt = 1400,
            nodes = listOf(row(1, "Delivery address", 100), row(2, "Order total ₹499", 1900), row(3, "Continue", 2200, clickable = true)))
        try { g.requireStillTappable(address, fresh, 2, commit = false); fail("expected a guard refusal") }
        catch (e: DeviceException) { assertEquals("guard", e.code); assertTrue(e.handover); assertTrue(g.handedOver) }
    }

    @Test fun anElementThatChangedSinceTheReadIsStale() {
        val g = PolicyGuard(rules)
        val relabelled = address.copy(nodes = listOf(row(1, "Delivery address", 100), row(2, "Remove address", 2200, clickable = true)))
        val moved = address.copy(nodes = listOf(row(1, "Delivery address", 100), row(2, "Continue", 900, clickable = true)))
        for (fresh in listOf(relabelled, moved)) {
            try { g.requireStillTappable(address, fresh, 2, commit = false); fail("expected stale") }
            catch (e: DeviceException) { assertEquals("stale", e.code); assertTrue(!e.handover) }
        }
        val shifted = address.copy(nodes = listOf(row(1, "Deliver to Home", 50), row(2, "Delivery address", 100), row(3, "Continue", 2210, clickable = true)))
        assertEquals(3, g.requireStillTappable(address, shifted, 2, commit = false).index)
        assertTrue(!g.handedOver)
    }

    @Test fun aPaymentAppInFrontOnTheFreshWalkHandsOver() {
        val g = PolicyGuard(rules)
        try { g.requireStillTappable(address, address.copy(packageName = "com.phonepe.app", label = "PhonePe"), 2, commit = false); fail("expected a guard refusal") }
        catch (e: DeviceException) { assertEquals("guard", e.code); assertTrue(e.handover) }
    }

    @Test fun aTapByPointIsJudgedOnWhatIsThereNow() {
        val g = PolicyGuard(rules)
        val dialog = address.copy(nodes = address.nodes + row(3, "Pay ₹499", 2200, clickable = true).copy(left = 100, right = 980))
        try { g.requireStillAtPoint(address, dialog, 540, 2250); fail("expected stale") } catch (e: DeviceException) { assertEquals("stale", e.code) }
        assertEquals(2, g.requireStillAtPoint(address, address.copy(takenAt = 2000), 540, 2250)?.index)
        val game = Snapshot("s9", "com.example.game", "Blocks", 1080, 2400, false, false, emptyList())
        assertEquals(null, g.requireStillAtPoint(game, game, 540, 1200))
        try { g.requireStillAtPoint(game, game.copy(nodes = listOf(row(1, "Pay", 0).copy(right = 10, bottom = 10, clickable = true))), 540, 1200); fail("expected a refusal") }
        catch (e: DeviceException) { assertEquals("guard", e.code) }
    }

    @Test fun aJudgementIsMadeAgainWhenTheScreenMayHaveMoved() {
        assertTrue(!guard.needsRecheck(null, 5000))
        assertTrue(!guard.needsRecheck(address, 900))
        assertTrue(!guard.needsRecheck(address, 1000))
        assertTrue(guard.needsRecheck(address, 1001))
        assertTrue(guard.needsRecheck(address.copy(settled = false), 900))
        assertTrue(guard.needsRecheck(address.copy(settled = false), 1001))
    }

    @Test fun passwordFieldsAreNeverTyped() {
        try { guard.requireTypeable(node(1, "", password = true)); fail("expected a refusal") } catch (e: DeviceException) { assertTrue(e.handover) }
        guard.requireTypeable(node(1, "Search", editable = true))
    }
}

class PolicyGuardReviewTest {
    private val rules = GuardRules.parse(File("src/main/assets/guard_rules.json").readText())
    private val guard = PolicyGuard(rules)

    @Test fun deniedNamesAndNormalisation() {
        assertTrue(guard.packageVerdict("", "PhonePe").contains("payment or banking"))
        assertTrue(guard.packageVerdict("", "Google Pay: Save and Pay").contains("payment or banking"))
        assertEquals("", guard.packageVerdict("", "Otherwise Notes"))
        assertEquals(PolicyGuard.Target.PAY, guard.targetVerdict("Pay​now"))
        assertEquals(PolicyGuard.Target.PAY, guard.targetVerdict("Ｐａｙ now"))
        assertEquals("pay now", guard.normal("  Pay​  NOW "))
    }

    @Test fun anElementIsJudgedByItsIdAsWellAsItsLabel() {
        // The same corpus as otto's tests/test_phone_element_ids.py.
        assertEquals("buy now button", guard.idWords("buy-now-button"))
        assertEquals("buy now button", guard.idWords("in.amazon.mShop.android.shopping:id/buyNowButton"))
        assertEquals("buybox add to cart", guard.idWords("buybox.addToCart"))
        assertEquals(PolicyGuard.Target.PAY, guard.targetVerdict("Submit", viewId = "buy-now-button"))
        assertEquals(PolicyGuard.Target.PAY, guard.targetVerdict("", viewId = "com.shop:id/buyNow"))
        assertEquals(PolicyGuard.Target.COMMIT, guard.targetVerdict("Submit", viewId = "add-to-cart-button"))
        assertEquals(PolicyGuard.Target.NONE, guard.targetVerdict("Add to cart", viewId = "add-to-cart-button"))
        assertEquals(PolicyGuard.Target.PAY, guard.targetVerdict("Pay now", viewId = "add-to-cart-button"))
        assertEquals(PolicyGuard.Target.PAY, guard.targetVerdict("", listOf("Total ₹28"), viewId = "continue-button"))
        assertEquals(PolicyGuard.Target.NONE, guard.targetVerdict("Buyer's guide", viewId = "buyers-guide"))
        val buyNow = UiNode(6, "Submit", "", "button", 52, 2180, 1387, 2320, true, false, false, false, false, null, viewId = "buy-now-button")
        try { PolicyGuard(rules).requireTappable(buyNow, commit = true); fail("expected a guard refusal") }
        catch (e: DeviceException) { assertTrue(e.handover); assertTrue(e.message!!.contains("buy-now-button")) }
        val page = Snapshot("s6", "in.amazon.mShop.android.shopping", "Amazon", 1440, 3088, false, false,
            listOf(UiNode(1, "Search Amazon", "", "edit-field", 0, 100, 1440, 200, true, true, false, false, true, null), buyNow))
        try { PolicyGuard(rules).requireSubmit(page); fail("expected a refusal") } catch (e: DeviceException) { assertTrue(e.handover) }
    }

    @Test fun theElementUnderAPoint() {
        val big = UiNode(1, "Cart", "", "list", 0, 0, 1080, 2400, false, false, true, false, false, null)
        val pay = UiNode(2, "Pay now", "", "button", 60, 2200, 1020, 2300, true, false, false, false, false, null)
        val snapshot = Snapshot("s", "com.grofers.customerapp", "Blinkit", 1080, 2400, false, false, listOf(big, pay))
        assertEquals(2, guard.nodeAt(snapshot, 540, 2250)?.index)
        assertEquals(1, guard.nodeAt(snapshot, 10, 10)?.index)
        assertEquals(null, guard.nodeAt(snapshot, 5000, 5000))
    }
}
