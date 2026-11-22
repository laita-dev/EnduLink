package com.oscar.endulink.platform

import com.oscar.endulink.bike.BikeMapConfig
import com.oscar.endulink.trail.SettingsStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneNotificationCenterTest {

    @BeforeTest
    fun setUp() {
        PhoneNotificationCenter.resetForTesting()
    }

    @AfterTest
    fun tearDown() {
        PhoneNotificationCenter.resetForTesting()
    }

    @Test
    fun testNotificationLifecycleAndExpiration() {
        PhoneNotificationCenter.postNotification(
            appName = "WhatsApp",
            title = "Carlos",
            text = "Nos vemos\nen el cruce",
            category = TftNotificationCategory.MESSAGE,
            durationSeconds = 5,
            key = "wa_1",
            nowMs = 10_000L
        )

        val activeAtStart = PhoneNotificationCenter.getActiveNotification(nowMs = 10_000L)
        assertNotNull(activeAtStart)
        assertEquals("wa_1", activeAtStart.id)
        assertEquals("WhatsApp", activeAtStart.appName)
        assertEquals("Carlos", activeAtStart.title)
        assertEquals("Nos vemos en el cruce", activeAtStart.text)
        assertEquals(5_000L, activeAtStart.durationMs)
        assertEquals(1.0f, activeAtStart.remainingProgress(10_000L), 0.001f)

        val activeHalfway = PhoneNotificationCenter.getActiveNotification(nowMs = 12_500L)
        assertNotNull(activeHalfway)
        assertEquals(0.5f, activeHalfway.remainingProgress(12_500L), 0.001f)

        assertTrue(activeHalfway.isActive(15_000L))
        assertFalse(activeHalfway.isActive(15_001L))
        assertNull(PhoneNotificationCenter.getActiveNotification(nowMs = 15_001L))
        assertNull(PhoneNotificationCenter.activeNotification.value)
    }

    @Test
    fun testDeduplicationWindowAndDismissByKey() {
        PhoneNotificationCenter.postNotification(
            appName = "Telegram",
            title = "Grupo",
            text = "Salimos ya",
            durationSeconds = 6,
            key = "tg_1",
            nowMs = 20_000L
        )
        // Repetida dentro de 2500ms -> ignorada (mantiene id original)
        PhoneNotificationCenter.postNotification(
            appName = "Telegram",
            title = "Grupo",
            text = "Salimos ya",
            durationSeconds = 6,
            key = "tg_2",
            nowMs = 21_200L
        )
        assertEquals("tg_1", PhoneNotificationCenter.getActiveNotification(21_200L)?.id)

        // Pasada la ventana de deduplicación -> actualiza
        PhoneNotificationCenter.postNotification(
            appName = "Telegram",
            title = "Grupo",
            text = "Salimos ya",
            durationSeconds = 6,
            key = "tg_3",
            nowMs = 23_000L
        )
        assertEquals("tg_3", PhoneNotificationCenter.getActiveNotification(23_000L)?.id)

        // Dismiss con clave distinta no borra; con clave coincidente sí borra
        PhoneNotificationCenter.dismissByKey("other")
        assertNotNull(PhoneNotificationCenter.getActiveNotification(23_100L))
        PhoneNotificationCenter.dismissByKey("tg_3")
        assertNull(PhoneNotificationCenter.getActiveNotification(23_100L))
    }

    @Test
    fun testSampleRotationAndDisabledState() {
        PhoneNotificationCenter.postSampleNotification(durationSeconds = 4, nowMs = 1_000L)
        val first = PhoneNotificationCenter.getActiveNotification(1_100L)
        assertNotNull(first)
        assertEquals("WhatsApp", first.appName)
        assertEquals(4_000L, first.durationMs)

        PhoneNotificationCenter.postSampleNotification(durationSeconds = 10, nowMs = 1_200L)
        val second = PhoneNotificationCenter.getActiveNotification(1_300L)
        assertNotNull(second)
        assertEquals(TftNotificationCategory.CALL, second.category)
        assertEquals(10_000L, second.durationMs)

        PhoneNotificationCenter.enabledOnTft = false
        PhoneNotificationCenter.dismissActive()
        PhoneNotificationCenter.postNotification("SMS", "Test", "Body", nowMs = 2_000L)
        assertNull(PhoneNotificationCenter.getActiveNotification(2_000L))
    }

    @Test
    fun testNotificationTextWrappingAndTruncation() {
        val measureMonospace: (String) -> Float = { it.length * 10f }

        val shortLines = TftNotificationTextLayout.wrapLines(
            text = "Hola equipo",
            maxWidth = 200f,
            maxLines = 2,
            measureWidth = measureMonospace
        )
        assertEquals(listOf("Hola equipo"), shortLines)

        val wrappedLines = TftNotificationTextLayout.wrapLines(
            text = "Ojo que hay una cancela cerrada en la bajada del cortafuegos principal",
            maxWidth = 200f, // 20 chars
            maxLines = 2,
            measureWidth = measureMonospace
        )
        assertEquals(2, wrappedLines.size)
        assertTrue(wrappedLines[0].length <= 20)
        assertTrue(wrappedLines[1].length <= 20)
        assertTrue(wrappedLines[1].endsWith("..."))
    }

    @Test
    fun testBikeMapConfigNotificationSettingsBackwardCompatibility() {
        val legacyJson = """
            {
              "selectedLayerRaw": "carreteras",
              "bikeMapConfig": { "zoom": 14, "orientation": "heading" },
              "lanTestingDefaultApplied": true
            }
        """.trimIndent()
        val decoded = SettingsStore.decodeSettings(legacyJson)
        assertTrue(decoded.bikeMapConfig.showPhoneNotificationsOnTft)
        assertEquals(6, decoded.bikeMapConfig.tftNotificationDurationSeconds)

        val custom = BikeMapConfig(showPhoneNotificationsOnTft = false, tftNotificationDurationSeconds = 10)
        assertFalse(custom.showPhoneNotificationsOnTft)
        assertEquals(10, custom.tftNotificationDurationSeconds)
    }
}
