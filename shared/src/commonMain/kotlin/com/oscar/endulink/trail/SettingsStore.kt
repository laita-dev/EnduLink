package com.oscar.endulink.trail

import com.oscar.endulink.bike.BikeMapConfig
import com.oscar.endulink.bike.BikeQr
import com.oscar.endulink.tiles.MapLayer
import com.oscar.endulink.tiles.PlatformPaths
import com.oscar.endulink.tiles.TileDiskStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PersistedSettings(
    val selectedLayerRaw: String = MapLayer.CARRETERAS.raw,
    val bikeMapConfig: BikeMapConfig = BikeMapConfig(),
    val bikeQr: BikeQr? = null,
    val allowLocalLanTesting: Boolean = true,
    val lanTestingDefaultApplied: Boolean = false
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
        return if (parsed.lanTestingDefaultApplied) {
            parsed
        } else {
            parsed.copy(allowLocalLanTesting = true)
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
        allowLocalLanTesting: Boolean = true
    ) {
        try {
            val payload = PersistedSettings(
                selectedLayerRaw = layer.raw,
                bikeMapConfig = bikeConfig,
                bikeQr = bikeQr,
                allowLocalLanTesting = allowLocalLanTesting,
                lanTestingDefaultApplied = true
            )
            TileDiskStorage.writeBytes(getFilePath(), json.encodeToString(payload).encodeToByteArray())
        } catch (_: Exception) {}
    }
}

