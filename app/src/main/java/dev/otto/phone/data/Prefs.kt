package dev.otto.phone.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val Context.store: DataStore<Preferences> by preferencesDataStore("otto")

/** Everything the app remembers. Secrets go through Crypt. */
class Prefs(private val context: Context) {
    private object Keys {
        val disclosure = booleanPreferencesKey("disclosure_accepted")
        val transport = stringPreferencesKey("transport")          // "embedded" | "serve"
        val serveUrl = stringPreferencesKey("serve_url")
        val serveToken = stringPreferencesKey("serve_token_enc")
        val vendorKeys = stringPreferencesKey("vendor_keys_enc")    // JSON object, encrypted
        val allowedToAct = booleanPreferencesKey("allowed_to_act")
    }

    suspend fun disclosureAccepted(): Boolean = context.store.data.first()[Keys.disclosure] ?: false
    suspend fun setDisclosureAccepted(value: Boolean) { context.store.edit { it[Keys.disclosure] = value } }

    suspend fun transport(): String = context.store.data.first()[Keys.transport] ?: "embedded"
    suspend fun setTransport(value: String) { context.store.edit { it[Keys.transport] = value } }

    suspend fun serveUrl(): String = context.store.data.first()[Keys.serveUrl] ?: ""
    suspend fun setServeUrl(value: String) { context.store.edit { it[Keys.serveUrl] = value } }

    suspend fun serveToken(): String = context.store.data.first()[Keys.serveToken]?.let { runCatching { Crypt.decrypt(it) }.getOrDefault("") } ?: ""
    suspend fun setServeToken(value: String) { context.store.edit { it[Keys.serveToken] = Crypt.encrypt(value) } }

    suspend fun allowedToAct(): Boolean = context.store.data.first()[Keys.allowedToAct] ?: true
    suspend fun setAllowedToAct(value: Boolean) { context.store.edit { it[Keys.allowedToAct] = value } }

    /** Vendor keys as a map; values are decrypted only here and handed to Python's environment. */
    suspend fun vendorKeys(): Map<String, String> {
        val stored = context.store.data.first()[Keys.vendorKeys] ?: return emptyMap()
        val json = runCatching { Crypt.decrypt(stored) }.getOrElse { return emptyMap() }
        return Json.parseToJsonElement(json).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    suspend fun setVendorKey(name: String, value: String) {
        val keys = vendorKeys().toMutableMap()
        if (value.isBlank()) keys.remove(name) else keys[name] = value
        val json = buildJsonObject { keys.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }.toString()
        context.store.edit { it[Keys.vendorKeys] = Crypt.encrypt(json) }
    }

    fun vendorKeysJson(keys: Map<String, String>): String =
        buildJsonObject { keys.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }.toString()
}
