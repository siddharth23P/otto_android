package dev.otto.phone.guard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.regex.Pattern

/** The rules file, byte-identical to otto's agent/phone/assets/guard_rules.json
 *  (CI checks). Parsed once; the patterns are the Java-compatible subset. */
class GuardRules private constructor(
    val version: Int,
    val deniedPackages: Set<String>,
    val deniedNames: List<Pattern>,
    val packageWords: List<String>,
    val packageWordExceptions: List<String>,
    val sensitivePatterns: List<Pattern>,
    val payWords: List<String>,
    val commitWords: List<String>,
    val settingsPages: List<String>,
) {
    companion object {
        fun parse(text: String): GuardRules {
            val root = Json.parseToJsonElement(text).jsonObject
            fun list(key: String) = root[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            return GuardRules(
                version = root["version"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                deniedPackages = list("denied_packages").map { it.lowercase() }.toSet(),
                deniedNames = list("denied_names").map { Pattern.compile("(^|\\W)" + Pattern.quote(it.lowercase()) + "($|\\W)") },
                packageWords = list("package_words").map { it.lowercase() },
                packageWordExceptions = list("package_word_exceptions").map { it.lowercase() },
                sensitivePatterns = list("sensitive_patterns").map { Pattern.compile(it, Pattern.CASE_INSENSITIVE) },
                payWords = list("pay_words").map { it.lowercase() },
                commitWords = list("commit_words").map { it.lowercase() },
                settingsPages = list("settings_pages"),
            )
        }
    }
}
