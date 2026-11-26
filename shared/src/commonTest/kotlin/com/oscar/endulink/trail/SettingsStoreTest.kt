package com.oscar.endulink.trail

import com.oscar.endulink.bike.BikeMapConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsStoreTest {

    @Test
    fun testLegacySettingsWithoutAppDisplaySettingsUsesDefaults() {
        val legacyJson = """
            {
              "selectedLayerRaw": "ign_topo",
              "bikeMapConfig": { "zoom": 16, "orientation": "heading" },
              "allowLocalLanTesting": true,
              "lanTestingDefaultApplied": true
            }
        """.trimIndent()

        val decoded = SettingsStore.decodeSettings(legacyJson)
        val display = decoded.appDisplaySettings

        assertEquals(1.0f, display.hudFontScale)
        assertEquals(1.0f, display.tftHudFontScale)
        assertTrue(display.showWaypointLabelsOnMap)
        assertFalse(display.alwaysShowTelemetryBar)
        assertEquals(1.0f, display.trackStrokeScale)
        assertTrue(display.showTrackDirectionArrows)
        assertEquals(35, display.gpxOffTrackThresholdMeters)
        assertEquals(KeepScreenOnMode.OFF, display.keepScreenOnMode)

        assertEquals(1.0f, decoded.bikeMapConfig.tftHudFontScale)
        assertTrue(decoded.bikeMapConfig.showWaypointLabelsOnTft)
        assertEquals(35, decoded.bikeMapConfig.gpxOffTrackThresholdMeters)
        assertEquals(1.0f, decoded.bikeMapConfig.trackStrokeScale)
        assertTrue(decoded.bikeMapConfig.showTrackDirectionArrows)
    }

    @Test
    fun testAppDisplaySettingsNormalizationClampsExtremeValues() {
        val outOfBoundsJson = """
            {
              "lanTestingDefaultApplied": true,
              "appDisplaySettings": {
                "hudFontScale": 4.2,
                "tftHudFontScale": 0.3,
                "showWaypointLabelsOnMap": false,
                "alwaysShowTelemetryBar": true,
                "trackStrokeScale": 3.5,
                "showTrackDirectionArrows": false,
                "gpxOffTrackThresholdMeters": 500,
                "keepScreenOnMode": "WHILE_ACTIVE"
              }
            }
        """.trimIndent()

        val decoded = SettingsStore.decodeSettings(outOfBoundsJson)
        val display = decoded.appDisplaySettings

        assertEquals(1.8f, display.hudFontScale)
        assertEquals(1.0f, display.tftHudFontScale)
        assertFalse(display.showWaypointLabelsOnMap)
        assertTrue(display.alwaysShowTelemetryBar)
        assertEquals(1.5f, display.trackStrokeScale)
        assertFalse(display.showTrackDirectionArrows)
        assertEquals(200, display.gpxOffTrackThresholdMeters)
        assertEquals(KeepScreenOnMode.WHILE_ACTIVE, display.keepScreenOnMode)

        val lowClamped = AppDisplaySettings(
            hudFontScale = 0.2f,
            tftHudFontScale = 2.5f,
            trackStrokeScale = 0.1f,
            gpxOffTrackThresholdMeters = 5,
            keepScreenOnMode = KeepScreenOnMode.ALWAYS
        ).normalized()

        assertEquals(0.9f, lowClamped.hudFontScale)
        assertEquals(1.4f, lowClamped.tftHudFontScale)
        assertEquals(0.75f, lowClamped.trackStrokeScale)
        assertEquals(20, lowClamped.gpxOffTrackThresholdMeters)
        assertEquals(KeepScreenOnMode.ALWAYS, lowClamped.keepScreenOnMode)
    }

    @Test
    fun testBikeMapConfigDisplayDefaultsRemainValid() {
        val cfg = BikeMapConfig(
            tftHudFontScale = 1.4f,
            showWaypointLabelsOnTft = false,
            gpxOffTrackThresholdMeters = 80,
            trackStrokeScale = 1.4f,
            showTrackDirectionArrows = false
        )
        assertEquals(1.4f, cfg.tftHudFontScale)
        assertFalse(cfg.showWaypointLabelsOnTft)
        assertEquals(80, cfg.gpxOffTrackThresholdMeters)
        assertEquals(1.4f, cfg.trackStrokeScale)
        assertFalse(cfg.showTrackDirectionArrows)
    }
}
