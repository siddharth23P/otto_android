package dev.otto.phone.actions

import dev.otto.phone.bridge.DeviceException
import dev.otto.phone.bridge.DeviceOps
import dev.otto.phone.bridge.PyBridge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId

/** The phone action registry: its menu, its argument checks, and the rules behind the actions. */
class ActionsTest {
    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is String -> put(k, v); is Int -> put(k, v); is Boolean -> put(k, v); is Double -> put(k, v)
                is List<*> -> put(k, kotlinx.serialization.json.JsonArray(v.map { JsonPrimitive(it as String) }))
            }
        }
    }

    private inline fun refused(code: String, contains: String, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: DeviceException) {
            assertEquals(e.message, code, e.code)
            assertTrue("'${e.message}' should mention '$contains'", e.message!!.contains(contains))
        }
    }

    @Test fun theMenuHasEveryActionWithItsEffect() {
        val names = ActionCatalog.specs.map { it.name }
        assertEquals(names.toSet().size, names.size)
        assertTrue(names.containsAll(listOf("alarm.set", "timer.set", "calendar.add", "flashlight", "volume", "media",
            "dnd", "panel", "sms.compose", "email.compose", "whatsapp.compose", "dial", "contacts.find", "share",
            "open_url", "maps", "clipboard.copy", "note.create")))
        // Everything the person finishes is CONFIRM: otto never sends, calls or saves for them.
        val confirm = ActionCatalog.specs.filter { it.effect == ActionSpec.Effect.CONFIRM }.map { it.name }.toSet()
        assertEquals(setOf("calendar.add", "panel", "sms.compose", "email.compose", "whatsapp.compose", "dial", "share", "note.create"), confirm)
        assertEquals("alarm.set{hour, minute, label?, days?} set an alarm in the clock app", ActionCatalog.spec("alarm.set")!!.signature)
        val menu = PhoneActions.menu()
        val alarm = menu["actions"]!!.jsonArray.first { it.jsonObject["name"]!!.jsonPrimitive.content == "alarm.set" }.jsonObject
        assertEquals("change", alarm["effect"]!!.jsonPrimitive.content)
        assertEquals("clock", alarm["group"]!!.jsonPrimitive.content)
        assertEquals(mapOf("clock" to 4, "device" to 5, "message" to 5, "files" to 5),
            ActionCatalog.specs.groupingBy { it.group }.eachCount())
        // otto finds actions by searching: every one carries words people use for it.
        assertEquals(emptyList<String>(), ActionCatalog.specs.filter { it.keywords.isEmpty() }.map { it.name })
        assertEquals("wake", alarm["keywords"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("integer", alarm["params"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun argumentsAreCheckedBeforeThePhoneIsTouched() {
        val alarm = ActionCatalog.spec("alarm.set")!!
        val ok = alarm.validate(args("hour" to 7, "minute" to 30, "days" to listOf("Mon", "fri")))
        assertEquals(7, ok.int("hour")); assertEquals(listOf("mon", "fri"), ok.list("days")); assertEquals("", ok.textOr("label"))
        refused("invalid", "needs minute") { alarm.validate(args("hour" to 7)) }
        refused("invalid", "at most 23") { alarm.validate(args("hour" to 24, "minute" to 0)) }
        refused("invalid", "whole number") { alarm.validate(args("hour" to "seven", "minute" to 0)) }
        refused("invalid", "not funday") { alarm.validate(args("hour" to 7, "minute" to 0, "days" to listOf("funday"))) }
        refused("invalid", "takes no snooze") { alarm.validate(args("hour" to 7, "minute" to 0, "snooze" to true)) }
        assertEquals(8, alarm.validate(args("hour" to 8.0, "minute" to 0)).int("hour"))
        val volume = ActionCatalog.spec("volume")!!
        assertEquals("media", volume.validate(args("stream" to "MEDIA", "step" to "up")).text("stream"))
        refused("invalid", "longer than") { ActionCatalog.spec("clipboard.copy")!!.validate(args("text" to "x".repeat(5000))) }
    }

    @Test fun anUnknownActionIsRefusedByTheBridge() {
        PyBridge.ops = object : DeviceOps by DeviceOps.Unavailable {
            override fun runAction(name: String, args: JsonObject) = throw DeviceException("no phone action '$name'", "unsupported")
        }
        val reply = Json.parseToJsonElement(PyBridge.run_action("teleport", "{}")).jsonObject
        assertEquals("unsupported", reply["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        val bad = Json.parseToJsonElement(PyBridge.run_action("timer.set", "[1]")).jsonObject
        assertEquals("invalid", bad["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        val menu = Json.parseToJsonElement(PyBridge.actions()).jsonObject
        assertTrue(menu["data"]!!.jsonObject["actions"]!!.jsonArray.size >= 18)
        PyBridge.ops = DeviceOps.Unavailable
    }

    @Test fun eventTimes() {
        val utc = ZoneId.of("UTC")
        val (start, end, allDay) = ActionRules.eventTimes("2026-09-18T15:00", "", utc)
        assertEquals(1789743600000L, start); assertEquals(start + 3_600_000, end); assertEquals(false, allDay)
        val day = ActionRules.eventTimes("2026-09-18", "", utc)
        assertEquals(true, day.third); assertEquals(day.first + 86_400_000, day.second)
        refused("invalid", "not a date") { ActionRules.eventTimes("tomorrow at 3", "", utc) }
        refused("invalid", "ends before") { ActionRules.eventTimes("2026-09-18T15:00", "2026-09-18T14:00", utc) }
    }

    @Test fun numbersLinksAndAddresses() {
        assertEquals("+919876543210", ActionRules.phoneNumber("+91 98765-43210"))
        refused("invalid", "not a phone number") { ActionRules.phoneNumber("call me") }
        assertEquals("https://wa.me/919876543210?text=on%20my%20way%20%26%20late", ActionRules.whatsappUrl("+91 98765 43210", "on my way & late"))
        refused("invalid", "country code") { ActionRules.whatsappUrl("12345", "hi") }
        assertEquals("https://example.com/a", ActionRules.webUrl(" https://example.com/a "))
        for (bad in listOf("intent://x#Intent;end", "javascript:alert(1)", "file:///data/data", "upi://pay?pa=x", "example.com")) {
            refused("invalid", "only http") { ActionRules.webUrl(bad) }
        }
        assertEquals("geo:0,0?q=Koregaon+Park%2C+Pune", ActionRules.mapsUri("Koregaon Park, Pune", false))
        assertEquals("google.navigation:q=Pune", ActionRules.mapsUri("Pune", true))
        assertEquals(listOf("a@b.co", "c@d.in"), ActionRules.emails("a@b.co; c@d.in").toList())
        refused("invalid", "not an email") { ActionRules.emails("bob") }
    }

    @Test fun onlyAConversationsOwnFilesAreShared() {
        val root = Files.createTempDirectory("workspaces").toFile()
        val session = "a".repeat(32)
        val doc = File(root, "$session/documents/GTM.pdf").apply { parentFile.mkdirs(); writeText("%PDF") }
        val secret = File(root.parentFile, "keys.env").apply { writeText("x") }
        assertEquals(doc.canonicalFile, ActionRules.conversationFile("documents/GTM.pdf", root, session))
        assertEquals(doc.canonicalFile, ActionRules.conversationFile(doc.absolutePath, root, ""))
        refused("invalid", "not one of this conversation's files") { ActionRules.conversationFile("../../keys.env", root, session) }
        refused("invalid", "not one of this conversation's files") { ActionRules.conversationFile(secret.absolutePath, root, session) }
        refused("invalid", "no file at") { ActionRules.conversationFile("documents/missing.pdf", root, session) }
        refused("invalid", "no conversation is open") { ActionRules.conversationFile("documents/GTM.pdf", root, "") }
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", ActionRules.mimeOf("deck.PPTX"))
    }
}
