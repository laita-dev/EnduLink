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
enum class HudButtonSlot(val label: String, val shortLabel: String) {
    BOTTOM("Barra inferior", "Abajo"),
    RIGHT("Lateral derecho", "Derecha"),
    LEFT("Lateral izquierdo", "Izquierda"),
    HIDDEN("Oculto", "Oculto")
}

@Serializable
enum class HudLayoutPreset(val title: String, val subtitle: String) {
    BALANCED("Equilibrado", "Abajo: REC, Tracks, Grupo, Moto · Derecha: Mapa y Puntos"),
    MINIMAL_BAR("Barra Minimalista", "Abajo: Tracks, Grupo, Moto · Derecha: REC, Puntos y Mapa"),
    CLASSIC_FULL("Barra Completa", "Abajo: REC, Tracks, Crear Ruta, Puntos, Grupo, Moto"),
    LEFT_HAND("Controles a la Izquierda", "Izquierda: Brújula, Zoom, REC, Puntos · Abajo: Tracks, Grupo, Moto")
}

@Serializable
data class HudButtonsLayout(
    val recSlot: HudButtonSlot = HudButtonSlot.BOTTOM,
    val routesSlot: HudButtonSlot = HudButtonSlot.BOTTOM,
    val routeGeneratorSlot: HudButtonSlot = HudButtonSlot.HIDDEN,
    val waypointsSlot: HudButtonSlot = HudButtonSlot.HIDDEN,
    val quickPinSlot: HudButtonSlot = HudButtonSlot.RIGHT,
    val groupSlot: HudButtonSlot = HudButtonSlot.BOTTOM,
    val bikeSlot: HudButtonSlot = HudButtonSlot.BOTTOM,
    val compassSlot: HudButtonSlot = HudButtonSlot.RIGHT,
    val zoomSlot: HudButtonSlot = HudButtonSlot.RIGHT,
    val gpsSide: HudButtonSlot = HudButtonSlot.RIGHT
) {
    fun normalized(): HudButtonsLayout = copy(
        routesSlot = if (routesSlot == HudButtonSlot.HIDDEN) HudButtonSlot.BOTTOM else routesSlot,
        compassSlot = if (compassSlot == HudButtonSlot.BOTTOM) HudButtonSlot.RIGHT else compassSlot,
        zoomSlot = if (zoomSlot == HudButtonSlot.BOTTOM) HudButtonSlot.RIGHT else zoomSlot,
        gpsSide = if (gpsSide == HudButtonSlot.LEFT) HudButtonSlot.LEFT else HudButtonSlot.RIGHT
    )

    companion object {
        fun fromPreset(preset: HudLayoutPreset): HudButtonsLayout = when (preset) {
            HudLayoutPreset.BALANCED -> HudButtonsLayout()
            HudLayoutPreset.MINIMAL_BAR -> HudButtonsLayout(
                recSlot = HudButtonSlot.RIGHT,
                routesSlot = HudButtonSlot.BOTTOM,
                routeGeneratorSlot = HudButtonSlot.HIDDEN,
                waypointsSlot = HudButtonSlot.HIDDEN,
                quickPinSlot = HudButtonSlot.RIGHT,
                groupSlot = HudButtonSlot.BOTTOM,
                bikeSlot = HudButtonSlot.BOTTOM,
                compassSlot = HudButtonSlot.RIGHT,
                zoomSlot = HudButtonSlot.RIGHT,
                gpsSide = HudButtonSlot.RIGHT
            )
            HudLayoutPreset.CLASSIC_FULL -> HudButtonsLayout(
                recSlot = HudButtonSlot.BOTTOM,
                routesSlot = HudButtonSlot.BOTTOM,
                routeGeneratorSlot = HudButtonSlot.BOTTOM,
                waypointsSlot = HudButtonSlot.BOTTOM,
                quickPinSlot = HudButtonSlot.RIGHT,
                groupSlot = HudButtonSlot.BOTTOM,
                bikeSlot = HudButtonSlot.BOTTOM,
                compassSlot = HudButtonSlot.RIGHT,
                zoomSlot = HudButtonSlot.RIGHT,
                gpsSide = HudButtonSlot.RIGHT
            )
            HudLayoutPreset.LEFT_HAND -> HudButtonsLayout(
                recSlot = HudButtonSlot.LEFT,
                routesSlot = HudButtonSlot.BOTTOM,
                routeGeneratorSlot = HudButtonSlot.HIDDEN,
                waypointsSlot = HudButtonSlot.HIDDEN,
                quickPinSlot = HudButtonSlot.LEFT,
                groupSlot = HudButtonSlot.BOTTOM,
                bikeSlot = HudButtonSlot.BOTTOM,
                compassSlot = HudButtonSlot.LEFT,
                zoomSlot = HudButtonSlot.LEFT,
                gpsSide = HudButtonSlot.LEFT
            )
        }
    }
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
    val keepScreenOnMode: KeepScreenOnMode = KeepScreenOnMode.OFF,
    val hudLayout: HudButtonsLayout = HudButtonsLayout()
) {
    fun normalized(): AppDisplaySettings = copy(
        hudFontScale = hudFontScale.coerceIn(0.9f, 1.8f),
        tftHudFontScale = tftHudFontScale.coerceIn(1.0f, 1.4f),
        trackStrokeScale = trackStrokeScale.coerceIn(0.75f, 1.5f),
        gpxOffTrackThresholdMeters = gpxOffTrackThresholdMeters.coerceIn(20, 200),
        hudLayout = hudLayout.normalized()
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

