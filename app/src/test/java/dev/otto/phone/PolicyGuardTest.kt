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
        assertEquals(PolicyGuard.Target.NONE, guard.targetVerdict("Proceed to checkout"))
        try { guard.requireTappable(node(1, "Pay now", clickable = true), commit = true); fail("expected a guard refusal") }
        catch (e: DeviceException) { assertEquals("guard", e.code); assertTrue(e.handover) }
        try { guard.requireTappable(node(1, "Send", clickable = true), commit = false); fail("expected a refusal") }
        catch (e: DeviceException) { assertEquals("guard", e.code) }
        guard.requireTappable(node(1, "Send", clickable = true), commit = true)
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
