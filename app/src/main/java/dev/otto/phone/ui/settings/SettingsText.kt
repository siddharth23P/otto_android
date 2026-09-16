package dev.otto.phone.ui.settings

import dev.otto.phone.protocol.ProbeResult
import dev.otto.phone.protocol.ProviderHealth
import dev.otto.phone.protocol.RouteRow
import dev.otto.phone.protocol.VendorRow
import dev.otto.phone.state.Board

/** The settings screens' words, pure so the tests pin them. */
object SettingsText {
    const val NOT_SET = "not set"

    fun transport(name: String, serveUrl: String): String =
        if (name == "serve") "otto serve · ${serveUrl.ifBlank { "not paired" }}" else "on this phone"

    fun version(otto: String, api: Int, protocol: Int): String =
        if (otto.isBlank()) "—" else "otto $otto · api $api · protocol $protocol"

    /** A vendor's key as the row shows it: the masked value otto sent, or "not set". */
    fun keyValue(row: VendorRow, masked: Map<String, String>): String =
        row.maskedKey.ifBlank { masked[row.keyVar] ?: "" }.ifBlank { if (row.keyPresent) "set" else NOT_SET }

    /** The vendors to list: otto's own rows, or one row per masked key an older otto sent. */
    fun vendors(rows: List<VendorRow>, masked: Map<String, String>): List<VendorRow> =
        rows.ifEmpty {
            masked.keys.sorted().map { key ->
                VendorRow(name = key.removeSuffix("_API_KEY").lowercase(), label = key, keyVar = key,
                    keyPresent = masked[key]?.let { it.isNotBlank() && it != NOT_SET } == true, maskedKey = masked[key].orEmpty().takeIf { it != NOT_SET }.orEmpty())
            }
        }

    fun probe(result: ProbeResult): String {
        val models = result.modelCount ?: result.models.size.takeIf { it > 0 }
        val head = if (result.ok) "works" else result.status.ifBlank { "failed" }
        return listOfNotNull(head, models?.let { "$it model${if (it == 1) "" else "s"}" }, result.detail.ifBlank { null }).joinToString(" · ")
    }

    /** How a doctor status reads: fine, needs attention, broken, or simply absent. */
    fun level(status: String): Board.Level? = when (status.lowercase()) {
        "ok", "ready", "healthy", "configured" -> Board.Level.OK
        "missing", "not configured", "unset", "skipped", "" -> null
        "error", "failed", "unreachable", "invalid", "unauthorized" -> Board.Level.BAD
        else -> Board.Level.WARN
    }

    fun provider(row: ProviderHealth): String =
        listOfNotNull(row.status.ifBlank { null }, if (row.models > 0) "${row.models} models" else null, row.detail.ifBlank { null }).joinToString(" · ")

    /** A routing row's model: the pin when there is one, the default otherwise. */
    fun routeModel(row: RouteRow): String = row.pin?.ifBlank { null } ?: row.default.ifBlank { "—" }

    fun routeNote(row: RouteRow): String? {
        val parts = mutableListOf<String>()
        if (row.pin.isNullOrBlank()) parts += "default"
        row.boundProvider?.let { p -> parts += "$p only" + (row.boundReason?.let { " — $it" } ?: "") }
        row.phoneSeat?.ifBlank { null }?.let { parts += "on the phone: $it" }
        return parts.joinToString(" · ").ifBlank { null }
    }
}
