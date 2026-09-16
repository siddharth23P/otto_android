package dev.otto.phone.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** What a request came back with. `Unsupported` is its own case, not an error: the other end is an
 *  older otto (or an embedded entry.py without the function), and the UI says "needs a newer otto"
 *  instead of showing a failure. */
sealed interface Reply<out T> {
    data class Ok<out T>(val value: T) : Reply<T>
    data class Err(val code: String, val message: String) : Reply<Nothing>
    data object Unsupported : Reply<Nothing>
}

inline fun <T, R> Reply<T>.map(transform: (T) -> R): Reply<R> = when (this) {
    is Reply.Ok -> Reply.Ok(transform(value))
    is Reply.Err -> this
    Reply.Unsupported -> Reply.Unsupported
}

fun <T> Reply<T>.valueOrNull(): T? = (this as? Reply.Ok)?.value

/** One reading of the wire for every model: unknown keys are ignored (a newer otto adds fields),
 *  and a null where a default exists takes the default. */
object Protocol {
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    fun parse(text: String): JsonObject? = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    /** Codes that mean "the other end does not know this request", not "it failed". */
    private val UNSUPPORTED_CODES = setOf("unknown", "unsupported")

    /** The error a frame carries, in either spelling: a serve `error` frame (`code`, `message` at the
     *  top) or an embedded reply (`ok: false`, `error: {code, message}`). Null when it is not one. */
    fun errorOf(frame: JsonObject): Reply<Nothing>? {
        val (code, message) = when {
            frame.str("type") == "error" -> (frame.str("code") ?: "failed") to (frame.str("message") ?: "")
            frame.bool("ok") == false -> {
                val error = frame["error"] as? JsonObject
                (error?.str("code") ?: "failed") to (error?.str("message") ?: "")
            }
            else -> return null
        }
        return if (code in UNSUPPORTED_CODES) Reply.Unsupported else Reply.Err(code, message)
    }

    fun <T> decode(deserializer: DeserializationStrategy<T>, frame: JsonObject): Reply<T> {
        errorOf(frame)?.let { return it }
        return runCatching { Reply.Ok(json.decodeFromJsonElement(deserializer, frame)) }
            .getOrElse { Reply.Err("malformed", "could not read the reply: ${it.message}") }
    }
}

internal fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }
internal fun JsonObject.str(key: String): String? = prim(key)?.content
internal fun JsonObject.int(key: String): Int? = prim(key)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
internal fun JsonObject.long(key: String): Long? = prim(key)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
internal fun JsonObject.double(key: String): Double? = prim(key)?.doubleOrNull
internal fun JsonObject.bool(key: String): Boolean? = prim(key)?.let { it.booleanOrNull ?: it.content.lowercase().let { c -> if (c == "true") true else if (c == "false") false else null } }
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: it.toString() } ?: emptyList()
internal fun JsonElement?.nullIfJsonNull(): JsonElement? = if (this == null || this is JsonNull) null else this
