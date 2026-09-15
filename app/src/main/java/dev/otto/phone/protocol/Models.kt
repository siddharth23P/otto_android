@file:OptIn(ExperimentalSerializationApi::class)

package dev.otto.phone.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Every reply `otto serve` (agent/server/app.py) and the embedded entry.py send, as data. Field names
// are the wire's; every field has a default so an older or newer otto still decodes.

@Serializable
data class Hello(
    @SerialName("otto_version") val ottoVersion: String = "",
    @SerialName("api_version") val apiVersion: Int = 0,
    @SerialName("protocol_version") val protocolVersion: Int = 1,
    @SerialName("min_protocol") val minProtocol: Int = 1,
    val features: List<String> = emptyList(),
)

// -- sessions ---------------------------------------------------------------------------------------

@Serializable
data class SessionRow(
    val id: String = "",
    @SerialName("short_id") val shortId: String = "",
    val title: String = "",
    val workspace: String? = null,
    val turns: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("last_active_at") val lastActiveAt: String = "",
    val age: String = "",
)

@Serializable
data class SessionList(val sessions: List<SessionRow> = emptyList())

@Serializable
data class OpenedSession(
    @SerialName("session_id") val sessionId: String = "",
    val title: String = "",
    val turns: Int = 0,
)

@Serializable
data class TranscriptMessage(val role: String = "otto", val text: String = "")

@Serializable
data class Transcript(
    val id: String = "",
    val title: String = "",
    val turns: Int = 0,
    val earlier: String = "",
    val messages: List<TranscriptMessage> = emptyList(),
)

@Serializable
data class DeletedSession(@SerialName("session_id") val sessionId: String = "", val deleted: Boolean = false)

@Serializable
data class ClosedSession(@SerialName("session_id") val sessionId: String = "", val closed: Boolean = true)

@Serializable
data class RenamedSession(@SerialName("session_id") val sessionId: String = "", val title: String = "")

/** `data` is the export payload as otto wrote it; the app saves it as-is and hands it back to import. */
@Serializable
data class ExportedSession(val filename: String = "", val data: JsonElement = JsonNull)

@Serializable
data class ImportedSession(
    @SerialName("session_id") val sessionId: String = "",
    val title: String = "",
    val turns: Int = 0,
)

// -- usage --------------------------------------------------------------------------------------------

/** One model's share (agent/pipeline/usage.py snapshot). `reported = false` or `cost = null` means
 *  "not known", which the UI shows as a dash, never as zero. */
@Serializable
data class ModelUsage(
    val model: String = "",
    val calls: Int = 0,
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cached_input_tokens") val cachedInputTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    val reported: Boolean = true,
    val cost: Double? = null,
)

@Serializable
data class UsageSnapshot(
    val calls: Int = 0,
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cached_input_tokens") val cachedInputTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    val cost: Double? = null,
    @SerialName("fully_priced") val fullyPriced: Boolean = true,
    val models: List<ModelUsage> = emptyList(),
)

/** What one turn spent: the ledger delta otto puts on `final` and `error`. */
@Serializable
data class TurnTotals(val tokens: Long = 0, val calls: Int = 0, val cost: Double? = null)

@Serializable
data class SessionUsage(
    val usage: UsageSnapshot = UsageSnapshot(),
    @SerialName("turn_tokens") val turnTokens: List<Long> = emptyList(),
    val turn: TurnTotals? = null,
    val title: String = "",
    val turns: Int = 0,
)

// -- setup, doctor, models ----------------------------------------------------------------------------

@Serializable
data class VersionInfo(val otto: String = "", val api: Int = 0, val python: String = "")

@Serializable
data class CompatInfo(val otto: String = "", val api: Int = 0, val features: List<String> = emptyList())

/** agent/router/setup.py VendorRow. */
@Serializable
data class VendorRow(
    val name: String = "",
    val label: String = "",
    @SerialName("key_var") val keyVar: String = "",
    @SerialName("url_var") val urlVar: String? = null,
    @SerialName("key_present") val keyPresent: Boolean = false,
    @SerialName("url_present") val urlPresent: Boolean = false,
    val custom: Boolean = false,
    @SerialName("masked_key") val maskedKey: String = "",
)

/** Serve's `status` says `key_status`; today's embedded entry says `keys`. Both are masked values. */
@Serializable
data class SetupStatus(
    val available: Boolean = true,
    val ready: Boolean = false,
    @SerialName("key_status") val keyStatus: Map<String, String> = emptyMap(),
    val keys: Map<String, String> = emptyMap(),
    @SerialName("vendor_rows") val vendorRows: List<VendorRow> = emptyList(),
    val version: VersionInfo = VersionInfo(),
    @SerialName("setup_write") val setupWrite: Boolean = false,
    val compat: CompatInfo? = null,
) {
    val maskedKeys: Map<String, String> get() = keyStatus.ifEmpty { keys }
}

@Serializable
data class KeySet(val name: String = "", val shown: String = "")

@Serializable
data class ProviderHealth(
    val provider: String = "",
    val status: String = "",
    val models: Int = 0,
    val detail: String = "",
)

@Serializable
data class ModelInfo(
    val spec: String = "",
    val provider: String = "",
    val id: String = "",
    @SerialName("display_name") val displayName: String? = null,
    val capabilities: List<String> = emptyList(),
    @SerialName("context_window") val contextWindow: Int? = null,
)

@Serializable
data class ProbeResult(
    val name: String = "",
    val ok: Boolean = false,
    val status: String = "",
    val detail: String = "",
    val models: List<ModelInfo> = emptyList(),
)

@Serializable
data class DoctorReport(
    val providers: List<ProviderHealth> = emptyList(),
    val ready: Boolean = false,
    @SerialName("also_configured") val alsoConfigured: List<String> = emptyList(),
)

@Serializable
data class ModelList(val models: List<ModelInfo> = emptyList())

// -- routing ------------------------------------------------------------------------------------------

@Serializable
data class RouteRow(
    val task: String = "",
    val pin: String? = null,
    @SerialName("default") val default: String = "",
    /** A provider name, or the (provider, …) tuple overrides.PROVIDER_ONLY holds. */
    @SerialName("provider_only") val providerOnly: JsonElement? = null,
    @SerialName("phone_seat") val phoneSeat: String? = null,
) {
    val boundProvider: String?
        get() = when (val p = providerOnly) {
            is JsonPrimitive -> p.content.takeUnless { p is JsonNull || it.isBlank() }
            is JsonArray -> (p.firstOrNull() as? JsonPrimitive)?.content
            else -> null
        }
}

@Serializable
data class RoutingList(@JsonNames("routes", "tasks", "rows") val routes: List<RouteRow> = emptyList())

data class RouteOption(val spec: String, val label: String)

/** `pin_options` returns (value, label) pairs, which JSON makes arrays; objects are read too. */
@Serializable
data class RouteOptions(val task: String = "", val options: List<JsonElement> = emptyList()) {
    val choices: List<RouteOption>
        get() = options.mapNotNull { o ->
            when (o) {
                is JsonArray -> RouteOption((o.getOrNull(0) as? JsonPrimitive)?.content ?: "", (o.getOrNull(1) as? JsonPrimitive)?.content ?: "")
                is JsonObject -> RouteOption(o.str("spec") ?: o.str("value") ?: "", o.str("label") ?: o.str("spec") ?: "")
                is JsonPrimitive -> RouteOption(o.content, o.content)
            }
        }
}

@Serializable
data class RouteChange(val task: String = "", val pin: String? = null)

// -- lessons & app notes ------------------------------------------------------------------------------

@Serializable
data class LessonRow(
    val id: String = "",
    @JsonNames("text", "lesson", "note") val text: String = "",
    val kind: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    val uses: Int? = null,
)

@Serializable
data class LessonList(val kind: String = "", val lessons: List<LessonRow> = emptyList())

@Serializable
data class LessonDeleted(val kind: String = "", @JsonNames("lesson_id", "id") val lessonId: String = "", val deleted: Boolean = false)

@Serializable
data class LessonsCleared(val kind: String = "", val cleared: Int = 0)

@Serializable
data class NoteSummary(@SerialName("package") val packageName: String = "", val seeded: Boolean = false, val learned: Int = 0)

@Serializable
data class NoteList(val notes: List<NoteSummary> = emptyList())

/** `seeded` is the shipped note text (read-only), `learned` what Otto added, `shown` what the model sees. */
@Serializable
data class NoteDetail(
    @SerialName("package") val packageName: String = "",
    val seeded: String? = null,
    val learned: List<LessonRow> = emptyList(),
    val shown: String = "",
)

// -- files, turns -------------------------------------------------------------------------------------

@Serializable
data class FileBlob(
    @SerialName("session_id") val sessionId: String = "",
    val name: String = "",
    /** base64 */
    val data: String = "",
    val size: Long? = null,
    val mime: String? = null,
)

@Serializable
data class DocumentInfo(
    val path: String = "",
    val format: String = "md",
    val markdown: String = "",
    val truncated: Boolean = false,
    val files: List<String> = emptyList(),
)

/** A turn is accepted when its `started` event arrives (or, on the embedded runtime, when
 *  start_turn returns). */
data class TurnStarted(val sessionId: String, val budgetMax: Int? = null)

/** A request with nothing to return beyond having been taken. */
data object Ack

enum class PhoneMode(val wire: String) { AUTO("auto"), ON("on"), OFF("off") }
