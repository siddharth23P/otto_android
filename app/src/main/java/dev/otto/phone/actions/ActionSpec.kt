package dev.otto.phone.actions

import dev.otto.phone.bridge.DeviceException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * A phone action otto can ask for by name instead of finding it on screen (the action registry,
 * 2026-09-17): what it does, its arguments, and what it does to the phone.
 *
 * - [Effect.READ] reads something and changes nothing.
 * - [Effect.CHANGE] changes a setting or starts something the person can undo (an alarm, the torch).
 * - [Effect.CONFIRM] prepares something the PERSON finishes -- a message to send, a call to place, an
 *   event to save: the screen is opened for them and the phone is handed over, so otto never presses
 *   Send itself.
 */
data class ActionSpec(
    val name: String,
    val summary: String,
    val params: List<Param> = emptyList(),
    val effect: Effect = Effect.CHANGE,
    /** Which of otto's action tools lists it: clock, device, message or files. */
    val group: String = "",
    /** Other words a person might use for it, so otto's search finds it ("wake me up" -> alarm.set). */
    val keywords: List<String> = emptyList(),
) {
    enum class Effect(val wire: String) { READ("read"), CHANGE("change"), CONFIRM("confirm") }

    data class Param(
        val name: String,
        val type: Type,
        val doc: String = "",
        val required: Boolean = true,
        val choices: List<String> = emptyList(),
        val min: Double? = null,
        val max: Double? = null,
    ) {
        enum class Type(val wire: String) { TEXT("string"), INT("integer"), NUMBER("number"), BOOL("boolean"), TEXT_LIST("string_list") }
    }

    /** One line for otto's menu: `alarm.set{hour, minute, label?} set an alarm`. */
    val signature: String
        get() = name + params.joinToString(", ", "{", "}") { it.name + if (it.required) "" else "?" } + " " + summary

    fun toJson(): JsonObject = buildJsonObject {
        put("name", name); put("summary", summary); put("effect", effect.wire)
        if (group.isNotEmpty()) put("group", group)
        if (keywords.isNotEmpty()) put("keywords", JsonArray(keywords.map(::JsonPrimitive)))
        put("params", buildJsonArray {
            params.forEach { p ->
                add(buildJsonObject {
                    put("name", p.name); put("type", p.type.wire); put("required", p.required)
                    if (p.doc.isNotBlank()) put("doc", p.doc)
                    if (p.choices.isNotEmpty()) put("choices", JsonArray(p.choices.map(::JsonPrimitive)))
                    p.min?.let { put("min", it) }
                    p.max?.let { put("max", it) }
                })
            }
        })
    }

    /** The arguments checked against the spec: known names, required ones present, right types, in
     *  range, one of the choices. Anything else is refused before the phone is touched. */
    fun validate(args: JsonObject): Args {
        val unknown = args.keys - params.map { it.name }.toSet()
        if (unknown.isNotEmpty()) throw invalid("$name takes no ${unknown.sorted().joinToString()}")
        val values = mutableMapOf<String, Any>()
        for (p in params) {
            val raw = args[p.name]
            if (raw == null || raw is JsonNull) {
                if (p.required) throw invalid("$name needs ${p.name}")
                continue
            }
            val value: Any = when (p.type) {
                Param.Type.TEXT -> (raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                    ?.takeIf { it.isNotEmpty() || !p.required } ?: throw invalid("${p.name} must be text")
                Param.Type.INT -> (raw as? JsonPrimitive)?.intOrNull
                    ?: (raw as? JsonPrimitive)?.doubleOrNull?.takeIf { it % 1.0 == 0.0 }?.toInt()
                    ?: throw invalid("${p.name} must be a whole number")
                Param.Type.NUMBER -> (raw as? JsonPrimitive)?.doubleOrNull ?: throw invalid("${p.name} must be a number")
                Param.Type.BOOL -> (raw as? JsonPrimitive)?.booleanOrNull ?: throw invalid("${p.name} must be true or false")
                Param.Type.TEXT_LIST -> (raw as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { e -> e.isString }?.content?.trim()
                    ?: throw invalid("${p.name} must be a list of text") } ?: throw invalid("${p.name} must be a list of text")
            }
            if (p.choices.isNotEmpty()) {
                val each = if (value is List<*>) value.map { it.toString().lowercase() } else listOf(value.toString().lowercase())
                val bad = each.filterNot { it in p.choices }
                if (bad.isNotEmpty()) throw invalid("${p.name} is one of ${p.choices.joinToString("|")}, not ${bad.joinToString()}")
            }
            val number = (value as? Int)?.toDouble() ?: value as? Double
            if (number != null) {
                p.min?.let { if (number < it) throw invalid("${p.name} is at least ${fmt(it)}") }
                p.max?.let { if (number > it) throw invalid("${p.name} is at most ${fmt(it)}") }
            }
            if (value is String && value.length > MAX_TEXT) throw invalid("${p.name} is longer than $MAX_TEXT characters")
            values[p.name] = when {
                p.choices.isEmpty() -> value
                value is String -> value.lowercase()
                value is List<*> -> value.map { it.toString().lowercase() }
                else -> value
            }
        }
        return Args(values)
    }

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

    private fun invalid(message: String) = DeviceException(message, "invalid")

    class Args(private val values: Map<String, Any>) {
        fun text(name: String): String = values[name] as String
        fun textOr(name: String, default: String = ""): String = values[name] as? String ?: default
        fun int(name: String): Int = values[name] as Int
        fun intOr(name: String): Int? = values[name] as? Int
        fun number(name: String): Double = (values[name] as? Double) ?: (values[name] as Int).toDouble()
        fun bool(name: String, default: Boolean = false): Boolean = values[name] as? Boolean ?: default
        @Suppress("UNCHECKED_CAST")
        fun list(name: String): List<String> = values[name] as? List<String> ?: emptyList()
        fun has(name: String): Boolean = name in values
    }

    companion object {
        const val MAX_TEXT = 4000
    }
}

/** What an action did, as the bridge returns it. */
fun actionDone(done: String, handedOver: Boolean = false, data: JsonElement? = null): JsonObject = buildJsonObject {
    put("done", done)
    if (handedOver) put("handed_over", true)
    if (data != null) put("data", data)
}
