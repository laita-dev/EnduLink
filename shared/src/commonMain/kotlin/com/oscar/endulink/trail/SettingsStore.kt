package com.oscar.endulink.trail

import com.oscar.endulink.bike.BikeMapConfig
import com.oscar.endulink.tiles.MapLayer
import com.oscar.endulink.tiles.PlatformPaths
import com.oscar.endulink.tiles.TileDiskStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PersistedSettings(
    val selectedLayerRaw: String = MapLayer.CARRETERAS.raw,
    val bikeMapConfig: BikeMapConfig = BikeMapConfig()
)

object SettingsStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun getFilePath(): String {
        return PlatformPaths.getAppDataDir() + "/user_settings.json"
    }

    fun loadSettings(): PersistedSettings {
        return try {
            val bytes = TileDiskStorage.readBytes(getFilePath()) ?: return PersistedSettings()
            json.decodeFromString<PersistedSettings>(bytes.decodeToString())
        } catch (_: Exception) {
            PersistedSettings()
        }
    }

    fun saveSettings(layer: MapLayer, bikeConfig: BikeMapConfig) {
        try {
            val payload = PersistedSettings(
                selectedLayerRaw = layer.raw,
                bikeMapConfig = bikeConfig
            )
            TileDiskStorage.writeBytes(getFilePath(), json.encodeToString(payload).encodeToByteArray())
        } catch (_: Exception) {}
    }
}

