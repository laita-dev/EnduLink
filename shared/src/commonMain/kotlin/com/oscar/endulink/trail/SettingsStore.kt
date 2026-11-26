package com.oscar.endulink.trail

import com.oscar.endulink.bike.BikeMapConfig
import com.oscar.endulink.bike.BikeQr
import com.oscar.endulink.group.GroupConfig
import com.oscar.endulink.tiles.MapLayer
import com.oscar.endulink.tiles.PlatformPaths
import com.oscar.endulink.tiles.TileDiskStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class KeepScreenOnMode {
    OFF,
    WHILE_ACTIVE,
    ALWAYS
}

@Serializable
data class AppDisplaySettings(
    val hudFontScale: Float = 1.0f,
    val tftHudFontScale: Float = 1.0f,
    val showWaypointLabelsOnMap: Boolean = true,
    val alwaysShowTelemetryBar: Boolean = false,
    val trackStrokeScale: Float = 1.0f,
    val showTrackDirectionArrows: Boolean = true,
    val gpxOffTrackThresholdMeters: Int = 35,
    val keepScreenOnMode: KeepScreenOnMode = KeepScreenOnMode.OFF
) {
    fun normalized(): AppDisplaySettings = copy(
        hudFontScale = hudFontScale.coerceIn(0.9f, 1.8f),
        tftHudFontScale = tftHudFontScale.coerceIn(1.0f, 1.4f),
        trackStrokeScale = trackStrokeScale.coerceIn(0.75f, 1.5f),
        gpxOffTrackThresholdMeters = gpxOffTrackThresholdMeters.coerceIn(20, 200)
    )
}

@Serializable
data class PersistedSettings(
    val selectedLayerRaw: String = MapLayer.CARRETERAS.raw,
    val bikeMapConfig: BikeMapConfig = BikeMapConfig(),
    val bikeQr: BikeQr? = null,
    val allowLocalLanTesting: Boolean = true,
    val lanTestingDefaultApplied: Boolean = false,
    val groupConfig: GroupConfig = GroupConfig(),
    val appDisplaySettings: AppDisplaySettings = AppDisplaySettings()
)

object SettingsStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun getFilePath(): String {
        return PlatformPaths.getAppDataDir() + "/user_settings.json"
    }

    /** Decodifica ajustes aplicando el valor por defecto LAN=true a instalaciones previas sin migrar. */
    internal fun decodeSettings(rawJson: String): PersistedSettings {
        val parsed = json.decodeFromString<PersistedSettings>(rawJson)
        val normalizedDisplay = parsed.appDisplaySettings.normalized()
        val withNormalized = parsed.copy(appDisplaySettings = normalizedDisplay)
        return if (withNormalized.lanTestingDefaultApplied) {
            withNormalized
        } else {
            withNormalized.copy(allowLocalLanTesting = true)
        }
    }

    fun loadSettings(): PersistedSettings {
        return try {
            val bytes = TileDiskStorage.readBytes(getFilePath())
                ?: return PersistedSettings(allowLocalLanTesting = true, lanTestingDefaultApplied = true)
            decodeSettings(bytes.decodeToString())
        } catch (_: Exception) {
            PersistedSettings(allowLocalLanTesting = true, lanTestingDefaultApplied = true)
        }
    }

    fun saveSettings(
        layer: MapLayer,
        bikeConfig: BikeMapConfig,
        bikeQr: BikeQr? = null,
        allowLocalLanTesting: Boolean = true,
        groupConfig: GroupConfig = GroupConfig(),
        appDisplaySettings: AppDisplaySettings = AppDisplaySettings()
    ) {
        try {
            val payload = PersistedSettings(
                selectedLayerRaw = layer.raw,
                bikeMapConfig = bikeConfig,
                bikeQr = bikeQr,
                allowLocalLanTesting = allowLocalLanTesting,
                lanTestingDefaultApplied = true,
                groupConfig = groupConfig,
                appDisplaySettings = appDisplaySettings.normalized()
            )
            TileDiskStorage.writeBytes(getFilePath(), json.encodeToString(payload).encodeToByteArray())
        } catch (_: Exception) {}
    }
}

