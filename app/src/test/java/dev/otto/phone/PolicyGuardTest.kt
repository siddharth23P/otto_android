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

    @Test fun theElementUnderAPoint() {
        val big = UiNode(1, "Cart", "", "list", 0, 0, 1080, 2400, false, false, true, false, false, null)
        val pay = UiNode(2, "Pay now", "", "button", 60, 2200, 1020, 2300, true, false, false, false, false, null)
        val snapshot = Snapshot("s", "com.grofers.customerapp", "Blinkit", 1080, 2400, false, false, listOf(big, pay))
        assertEquals(2, guard.nodeAt(snapshot, 540, 2250)?.index)
        assertEquals(1, guard.nodeAt(snapshot, 10, 10)?.index)
        assertEquals(null, guard.nodeAt(snapshot, 5000, 5000))
    }
}
