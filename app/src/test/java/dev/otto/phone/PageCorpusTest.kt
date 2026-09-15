package dev.otto.phone

import dev.otto.phone.access.PageInfo
import dev.otto.phone.access.Snapshot
import dev.otto.phone.access.UiNode
import dev.otto.phone.guard.GuardRules
import dev.otto.phone.guard.PolicyGuard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** A snapshot as the app sends it, read back: the shape of otto's page fixtures. */
fun snapshotFrom(o: JsonObject): Snapshot {
    fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content ?: ""
    fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull ?: false
    val app = o.getValue("app").jsonObject
    val screen = o["screen"]?.jsonObject
    val page = o["page"]?.jsonObject?.let { p ->
        PageInfo(p.getValue("seq").jsonPrimitive.long, p.str("activity"),
            p["offscreen_ids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(), p.str("kind"))
    }
    val nodes = o.getValue("nodes").jsonArray.map { element ->
        val n = element.jsonObject
        val b = n.getValue("b").jsonArray.map { it.jsonPrimitive.int }
        UiNode(
            index = n.getValue("i").jsonPrimitive.int, text = n.str("t"), desc = n.str("d"), role = n.str("r"),
            left = b[0], top = b[1], right = b[2], bottom = b[3],
            clickable = n.flag("c"), editable = n.flag("e"), scrollable = n.flag("s"), password = n.flag("p"), focused = n.flag("f"),
            checked = n["k"]?.jsonPrimitive?.booleanOrNull, viewId = n.str("v"),
            hint = n.str("h"), inputKind = n.str("n"), maxLength = n["m"]?.jsonPrimitive?.int ?: -1, heading = n.flag("g"),
        )
    }
    return Snapshot(o.str("snapshot_id"), app.str("package"), app.str("label"), screen?.get("w")?.jsonPrimitive?.int ?: 0,
        screen?.get("h")?.jsonPrimitive?.int ?: 0, o.flag("keyboard"), o.flag("secure"), nodes,
        settled = o["settled"]?.jsonPrimitive?.boolean ?: true, page = page)
}

/**
 * The page corpus both money guards judge: otto's tests/fixtures/phone_pages, vendored under
 * src/test/resources/guard_pages with otto's record of how each page is judged (guard_pages.json).
 * This guard must reach the same class, the same three scores, the same evidence, the same Enter and
 * the same verdict on every control, page by page and sequence by sequence -- otto's
 * tests/test_phone_page_corpus.py holds the Python guard to the same file.
 */
class PageCorpusTest {
    private val rules = GuardRules.parse(File("src/main/assets/guard_rules.json").readText())
    private val resources = File("src/test/resources")
    private val manifest = Json.parseToJsonElement(File(resources, "guard_pages.json").readText()).jsonObject

    private fun load(name: String) = snapshotFrom(Json.parseToJsonElement(File(resources, "guard_pages/$name").readText()).jsonObject)

    private fun record(guard: PolicyGuard, snapshot: Snapshot, memory: PolicyGuard.Memory?): Pair<JsonObject, PolicyGuard.Memory?> {
        val verdict = guard.classify(snapshot, memory)
        val enter = when (guard.enterVerdict(snapshot, verdict.kind)) {
            PolicyGuard.Enter.PRESS -> "press"
            PolicyGuard.Enter.DECLINE -> "decline"
            PolicyGuard.Enter.HANDOVER -> "handover"
        }
        val controls = guard.controls(snapshot).mapNotNull { target ->
            guard.controlVerdict(target).takeIf { it != PolicyGuard.Control.NONE }
                ?.let { JsonArray(listOf(target.label, target.viewId, target.role, it.wire).map(::JsonPrimitive)) }
        }
        return buildJsonObject {
            put("kind", verdict.kind.wire); put("payment", verdict.payment); put("checkout", verdict.checkout); put("cart", verdict.cart)
            put("features", JsonArray(verdict.features.sorted().map(::JsonPrimitive)))
            put("enter", enter)
            put("controls", JsonArray(controls))
        } to verdict.memory
    }

    @Test fun theCorpusIsOfTheseRulesAndEveryFixtureIsTheOneOttoJudged() {
        assertEquals(GuardRules.VERSION, manifest.getValue("version").jsonPrimitive.int)
        val sha = MessageDigest.getInstance("SHA-256")
        val onDisk = File(resources, "guard_pages").listFiles { f -> f.name.endsWith(".json") }!!
            .associate { it.name to sha.digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) } }
        val recorded = manifest.getValue("pages").jsonObject.mapValues { it.value.jsonObject.getValue("sha256").jsonPrimitive.content }
        assertEquals(recorded, onDisk)
    }

    @Test fun everyPageIsJudgedAsOttoJudgedIt() {
        val failures = mutableListOf<String>()
        for ((name, expected) in manifest.getValue("pages").jsonObject) {
            val (judged, _) = record(PolicyGuard(rules), load(name), null)
            val want = JsonObject(expected.jsonObject - "sha256")
            if (judged != want) failures += "$name\n  otto  $want\n  phone $judged"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun everySequenceIsJudgedAsOttoJudgedIt() {
        for (sequence in manifest.getValue("sequences").jsonArray.map { it.jsonObject }) {
            val guard = PolicyGuard(rules)
            var memory: PolicyGuard.Memory? = null
            val kinds = sequence.getValue("pages").jsonArray.map { page ->
                val (judged, next) = record(guard, load(page.jsonPrimitive.content), memory)
                memory = next
                judged.getValue("kind").jsonPrimitive.content
            }
            assertEquals(sequence.getValue("name").jsonPrimitive.content,
                sequence.getValue("kinds").jsonArray.map { it.jsonPrimitive.content }, kinds)
        }
    }

    @Test fun theGuardHoldsItsOwnMemoryAcrossWalksOfOneWindow() {
        val guard = PolicyGuard(rules)
        assertEquals(PolicyGuard.PageKind.PAYMENT, guard.page(load("amazon_order_review.json")).kind)
        assertEquals(PolicyGuard.PageKind.PAYMENT, guard.page(load("amazon_order_review_scrolled.json")).kind)
        assertEquals(PolicyGuard.PageKind.NONE, PolicyGuard(rules).page(load("amazon_order_review_scrolled.json")).kind)
        assertEquals(PolicyGuard.PageKind.NONE, guard.page(load("amazon_results_after_review.json")).kind)
    }
}
