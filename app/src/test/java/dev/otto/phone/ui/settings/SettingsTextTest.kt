package dev.otto.phone.ui.settings

import dev.otto.phone.protocol.ProbeResult
import dev.otto.phone.protocol.ProviderHealth
import dev.otto.phone.protocol.RouteRow
import dev.otto.phone.protocol.VendorRow
import dev.otto.phone.state.Board
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsTextTest {
    @Test fun whereAndWhich() {
        assertEquals("on this phone", SettingsText.transport("embedded", ""))
        assertEquals("otto serve · ws://127.0.0.1:8765", SettingsText.transport("serve", "ws://127.0.0.1:8765"))
        assertEquals("otto 0.3.0 · api 1 · protocol 2", SettingsText.version("0.3.0", 1, 2))
        assertEquals("—", SettingsText.version("", 0, 1))
    }

    @Test fun keysShowWhatOttoMasked() {
        val row = VendorRow(name = "inception", label = "Inception", keyVar = "INCEPTION_API_KEY", keyPresent = true, maskedKey = "sk-…1234")
        assertEquals("sk-…1234", SettingsText.keyValue(row, emptyMap()))
        assertEquals("not set", SettingsText.keyValue(row.copy(keyPresent = false, maskedKey = ""), emptyMap()))
        assertEquals("AI…9x", SettingsText.keyValue(row.copy(maskedKey = ""), mapOf("INCEPTION_API_KEY" to "AI…9x")))
        val fromOld = SettingsText.vendors(emptyList(), mapOf("GEMINI_API_KEY" to "not set", "INCEPTION_API_KEY" to "sk…1"))
        assertEquals(listOf("GEMINI_API_KEY", "INCEPTION_API_KEY"), fromOld.map { it.keyVar })
        assertEquals(listOf(false, true), fromOld.map { it.keyPresent })
        assertEquals("works · 2 models", SettingsText.probe(ProbeResult(ok = true, modelCount = 2)))
        assertEquals("unauthorized · 401 from provider", SettingsText.probe(ProbeResult(ok = false, status = "unauthorized", detail = "401 from provider")))
    }

    @Test fun doctorAndRouting() {
        assertEquals(Board.Level.OK, SettingsText.level("ok"))
        assertEquals(Board.Level.BAD, SettingsText.level("error"))
        assertEquals(Board.Level.WARN, SettingsText.level("slow"))
        assertNull(SettingsText.level("missing"))
        assertEquals("ok · 3 models", SettingsText.provider(ProviderHealth("inception", "ok", 3, "")))
        val pinned = RouteRow(task = "reason", pin = "openai:gpt-5-mini", default = "inception:mercury-2")
        assertEquals("openai:gpt-5-mini", SettingsText.routeModel(pinned))
        assertNull(SettingsText.routeNote(pinned))
        val evaluate = RouteRow(task = "evaluate", default = "gemini:gemini-3-flash", phoneSeat = "gemini:gemini-3-flash",
            providerOnly = buildJsonObject { put("provider", JsonPrimitive("gemini")); put("reason", JsonPrimitive("needs screenshots")) })
        assertEquals("gemini:gemini-3-flash", SettingsText.routeModel(evaluate))
        assertEquals("default · gemini only — needs screenshots · on the phone: gemini:gemini-3-flash", SettingsText.routeNote(evaluate))
    }
}
