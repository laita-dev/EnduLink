package com.oscar.endulink.platform

import com.oscar.endulink.trail.GeoCoord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BugReportManagerTest {

    @Test
    fun testBugReportPayloadSerializationPreservesUserMessage() {
        val originalMessage = "Al cargar un track de 280 km y abrir Mapa Offline, debería guardar la zona.\nSegunda línea con detalle."
        val payload = BugReportPayload(
            reportId = "rep_test_1",
            createdAtEpochMs = 1795800000000L,
            category = BugReportCategory.ROUTING_MAP,
            userTitle = "Mejora al descargar corredor offline",
            userMessage = originalMessage,
            userContact = "piloto@endulink.test",
            appVersion = "0.4.0",
            platformName = "Android",
            isTestChannel = false,
            pinCoordinate = GeoCoord(40.4168, -3.7038),
            pinAddress = "Puerta del Sol, Madrid",
            includeDiagnostics = true,
            diagnosticsSummary = "App: EnduLink v0.4.0",
            recentBikeLogs = listOf("TCP conectado", "Keyframe OK"),
            crashLog = null,
            hasMapSnapshot = true
        )

        val jsonCodec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val encoded = jsonCodec.encodeToString(payload)
        val decoded = jsonCodec.decodeFromString<BugReportPayload>(encoded)

        assertEquals(originalMessage, decoded.userMessage)
        assertEquals(BugReportCategory.ROUTING_MAP, decoded.category)
        assertEquals("piloto@endulink.test", decoded.userContact)
        assertEquals(40.4168, decoded.pinCoordinate?.latitude ?: 0.0, 1e-6)
        assertEquals(2, decoded.recentBikeLogs.size)
        assertTrue(decoded.hasMapSnapshot)
    }

    @Test
    fun testWebhookResolutionPrefersValidHttpsCustomUrl() {
        assertEquals(
            "https://script.google.com/macros/s/abc/exec",
            BugReportManager.resolveWebhookUrl("  https://script.google.com/macros/s/abc/exec  ")
        )
        assertEquals(
            BugReportManager.DEFAULT_RELAY_WEBHOOK_URL,
            BugReportManager.resolveWebhookUrl("http://insecure-not-allowed")
        )
    }

    @Test
    fun testRelayResponseAcceptanceChecksStatusAndJsonOkFlag() {
        assertTrue(BugReportManager.isRelayResponseAccepted(200, """{"ok":true,"dispatchedToGitHub":true}"""))
        assertFalse(BugReportManager.isRelayResponseAccepted(302, ""))
        assertFalse(BugReportManager.isRelayResponseAccepted(200, """{"ok": false, "error": "bad json"}"""))
        assertFalse(BugReportManager.isRelayResponseAccepted(500, """{"ok":true}"""))
    }
}
