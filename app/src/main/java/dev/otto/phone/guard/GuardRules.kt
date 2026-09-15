package dev.otto.phone.guard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The rules file is unreadable, of another version, or missing a section: a guard with no rules
 *  refuses everything but the way out, it does not guess. */
class GuardRulesError(message: String) : IllegalStateException(message)

/**
 * The rules file, byte-identical to otto's agent/phone/assets/guard_rules.json (CI checks), compiled
 * once the way otto's guard._compiled() compiles it. The patterns are the Java-compatible subset.
 */
class GuardRules private constructor(root: JsonObject) {
    private fun list(root: JsonObject, key: String): List<String> = root.getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private fun ci(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    val version: Int = root.getValue("version").jsonPrimitive.int()
    val deniedPackages: Set<String> = list(root, "denied_packages").map(Words::normal).toSet()
    val deniedNames: List<Regex> = list(root, "denied_names").map(Words::wordRegex)
    private val packageWords = root.getValue("package_words").jsonObject
    val wholeWords: Set<String> = list(packageWords, "whole").map(Words::normal).toSet()
    val affixWords: List<String> = list(packageWords, "affix").map(Words::normal)
    val packageWordExceptions: List<String> = list(root, "package_word_exceptions").map(Words::normal)
    val secureActivities: List<Regex> = list(root, "secure_activities").map { Regex(it) }
    val fieldPatterns: List<Regex> = list(root, "sensitive_field_patterns").map(::ci)
    val screenPatterns: List<Regex> = list(root, "sensitive_screen_patterns").map(::ci)

    private val pay = root.getValue("pay_controls").jsonObject
    val payExact: Set<String> = list(pay, "exact").map(Words::normal).toSet()
    val payPhrases: List<Regex> = list(pay, "phrases").map(Words::wordRegex)
    val paySquashed: Set<String> = list(pay, "phrases").map(Words::normal)
        .filter { " " in it && it.replace(" ", "").let { s -> s.isNotEmpty() && s.all(Char::isLetter) } }
        .map { it.replace(" ", "") }.toSet()
    val payIds: List<Regex> = list(pay, "ids").map(Words::wordRegex)
    private val entry = root.getValue("entry_controls").jsonObject
    val entryExact: Set<String> = list(entry, "exact").map(Words::normal).toSet()
    val entryPhrases: List<Regex> = list(entry, "phrases").map(Words::wordRegex)
    val entryIds: List<Regex> = list(entry, "ids").map(Words::wordRegex)
    private val forward = root.getValue("forward_controls").jsonObject
    val forwardExact: Set<String> = list(forward, "exact").map(Words::normal).toSet()
    val forwardPrefixes: List<String> = list(forward, "prefixes").map(Words::normal)
    val forwardIds: List<Regex> = list(forward, "ids").map(Words::wordRegex)
    val commitWords: List<String> = list(root, "commit_words").map(Words::normal)
    val commitIds: List<Regex> = list(root, "commit_words").map(Words::wordRegex)
    val commitMaxWords: Int = root.getValue("commit_max_words").jsonPrimitive.int()
    val controlMaxWords: Int = root.getValue("control_max_words").jsonPrimitive.int()
    val settingsPages: List<String> = list(root, "settings_pages")

    private val page = root.getValue("page").jsonObject
    val paymentMin: Int = page.getValue("payment_min").jsonPrimitive.int()
    val checkoutMin: Int = page.getValue("checkout_min").jsonPrimitive.int()
    val cartMin: Int = page.getValue("cart_min").jsonPrimitive.int()
    val weights: Map<String, Int> = page.getValue("weights").jsonObject.mapValues { it.value.jsonPrimitive.int() }
    val finalPayPhrases: List<Regex> = list(page, "final_pay_phrases").map(Words::wordRegex)
    val amount: Regex = ci(page.getValue("amount").jsonPrimitive.content)
    val total: Regex = ci(page.getValue("total").jsonPrimitive.content)
    val paymentTitles: List<String> = list(page, "payment_titles").map(Words::normal)
    val paymentMethods: List<Regex> = list(page, "payment_methods").map(::ci)
    val maskedCard: Regex = ci(page.getValue("masked_card").jsonPrimitive.content)
    val checkoutTitles: List<String> = list(page, "checkout_titles").map(Words::normal)
    val addressChoices: List<Regex> = list(page, "address_choices").map(Words::wordRegex)
    val addressIds: List<Regex> = list(page, "address_ids").map(Words::wordRegex)
    val cartTitles: List<String> = list(page, "cart_titles").map(Words::normal)
    val cartStructure: List<Regex> = list(page, "cart_structure").map(::ci)
    val cartIds: List<Regex> = list(page, "cart_ids").map(::ci)
    val productIds: List<String> = list(page, "product_ids").map { it.lowercase() }
    val addToCart: Regex = ci(page.getValue("add_to_cart").jsonPrimitive.content)
    val topRegion: Double = page.getValue("top_region").jsonPrimitive.number()
    val wideControl: Double = page.getValue("wide_control").jsonPrimitive.number()
    val titleMaxWords: Int = page.getValue("title_max_words").jsonPrimitive.int()
    val rowBand: Int = page.getValue("row_band").jsonPrimitive.int()
    val rowContainerMax: Int = page.getValue("row_container_max").jsonPrimitive.int()

    fun weight(name: String): Int = weights[name] ?: throw GuardRulesError("guard_rules.json lacks the weight $name")

    companion object {
        const val VERSION = 3

        /** Every section and the JSON shape it must have -- otto's guard._REQUIRED_SECTIONS. */
        private val REQUIRED: Map<String, (JsonElement) -> Boolean> = mapOf(
            "denied_packages" to { it is JsonArray }, "denied_names" to { it is JsonArray },
            "package_words" to { it is JsonObject }, "package_word_exceptions" to { it is JsonArray },
            "secure_activities" to { it is JsonArray }, "sensitive_field_patterns" to { it is JsonArray },
            "sensitive_screen_patterns" to { it is JsonArray }, "pay_controls" to { it is JsonObject },
            "entry_controls" to { it is JsonObject }, "forward_controls" to { it is JsonObject },
            "commit_words" to { it is JsonArray }, "commit_max_words" to ::isInt, "control_max_words" to ::isInt,
            "page" to { it is JsonObject }, "settings_pages" to { it is JsonArray },
        )

        private fun isInt(e: JsonElement) = e is JsonPrimitive && !e.isString && e.intOrNull != null

        private fun JsonPrimitive.int(): Int = intOrNull ?: throw GuardRulesError("guard_rules.json: $content is not a whole number")
        private fun JsonPrimitive.number(): Double = doubleOrNull ?: throw GuardRulesError("guard_rules.json: $content is not a number")

        fun parse(text: String): GuardRules {
            val root = try {
                Json.parseToJsonElement(text)
            } catch (e: Exception) {
                throw GuardRulesError("guard_rules.json is unreadable (${e.message})")
            }
            if (root !is JsonObject) throw GuardRulesError("guard_rules.json is not an object")
            val missing = REQUIRED.filter { (key, shaped) -> root[key]?.let(shaped) != true }.keys
            if (missing.isNotEmpty()) throw GuardRulesError("guard_rules.json lacks ${missing.joinToString(", ")}")
            val version = (root["version"] as? JsonPrimitive)?.intOrNull
            if (version != VERSION) throw GuardRulesError("guard_rules.json is version $version; this app reads $VERSION")
            return try {
                GuardRules(root)
            } catch (e: GuardRulesError) {
                throw e
            } catch (e: Exception) {
                throw GuardRulesError("guard_rules.json has a malformed entry (${e.message})")
            }
        }
    }
}
