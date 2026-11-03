package com.oscar.endulink.tiles

import kotlin.math.abs

enum class MapLayer(
    val raw: String,
    val title: String,
    val shortBadge: String,
    val maxNativeZoom: Int,
    val supportsOfflineDownload: Boolean,
    val recommendedConcurrency: Int
) {
    CARRETERAS(
        raw = "google_roads",
        title = "Carreteras (Google Maps / Vial)",
        shortBadge = "CARRETERAS",
        maxNativeZoom = 20,
        supportsOfflineDownload = false,
        recommendedConcurrency = 4
    ),
    SATELITE_HIBRIDO(
        raw = "google_hybrid",
        title = "Satélite + Calles (Google Híbrido)",
        shortBadge = "SATÉLITE",
        maxNativeZoom = 20,
        supportsOfflineDownload = false,
        recommendedConcurrency = 4
    ),
    SATELITE_PURO(
        raw = "google_satellite",
        title = "Satélite Puro (Sin etiquetas)",
        shortBadge = "SAT PURO",
        maxNativeZoom = 20,
        supportsOfflineDownload = false,
        recommendedConcurrency = 4
    ),
    TRAIL_OSM(
        raw = "trail_cyclosm",
        title = "Trail OSM · Pistas y Caminos (Offline)",
        shortBadge = "TRAIL OSM",
        maxNativeZoom = 18,
        supportsOfflineDownload = true,
        recommendedConcurrency = 2
    ),
    IGN_TOPO(
        raw = "ign_topo",
        title = "IGN Topográfico España · Curvas (Offline)",
        shortBadge = "IGN TOPO",
        maxNativeZoom = 17,
        supportsOfflineDownload = true,
        recommendedConcurrency = 6
    ),
    WORLD_TOPO(
        raw = "world_topo",
        title = "Topo Global · Relieve (Offline)",
        shortBadge = "TOPO GLOBAL",
        maxNativeZoom = 18,
        supportsOfflineDownload = true,
        recommendedConcurrency = 4
    );

    fun tileURL(z: Int, x: Int, y: Int): String? {
        val clampedZ = minOf(maxOf(z, 1), maxNativeZoom)
        return when (this) {
            CARRETERAS -> {
                // Google Maps estándar de carreteras
                "https://mt1.google.com/vt/lyrs=m&x=$x&y=$y&z=$clampedZ"
            }
            SATELITE_HIBRIDO -> {
                // Google Maps Satélite con calles y carreteras
                "https://mt1.google.com/vt/lyrs=y&x=$x&y=$y&z=$clampedZ"
            }
            SATELITE_PURO -> {
                // Google Maps Satélite puro
                "https://mt1.google.com/vt/lyrs=s&x=$x&y=$y&z=$clampedZ"
            }
            TRAIL_OSM -> {
                val sub = listOf("a", "b", "c")[abs(x + y) % 3]
                "https://$sub.tile-cyclosm.openstreetmap.fr/cyclosm/$clampedZ/$x/$y.png"
            }
            IGN_TOPO -> {
                "https://www.ign.es/wmts/mapa-raster?request=GetTile&service=WMTS&Version=1.0.0&Layer=MTN&Style=default&Format=image/jpeg&TileMatrixSet=GoogleMapsCompatible&TileMatrix=$clampedZ&TileCol=$x&TileRow=$y"
            }
            WORLD_TOPO -> {
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/$clampedZ/$y/$x"
            }
        }
    }

    companion object {
        fun fromRaw(raw: String): MapLayer {
            if (raw == "road_online" || raw == "osm_std") return CARRETERAS
            if (raw == "sat_hybrid") return SATELITE_HIBRIDO
            if (raw == "sat_pure") return SATELITE_PURO
            return entries.find { it.raw.equals(raw, ignoreCase = true) } ?: CARRETERAS
        }

        val offlineCapableLayers: List<MapLayer>
            get() = entries.filter { it.supportsOfflineDownload }
    }
}

data class OfflineRegionPreset(
    val id: String,
    val category: String,
    val name: String,
    val subtitle: String,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val minZoom: Int,
    val maxZoom: Int
) {
    companion object {
        val catalog: List<OfflineRegionPreset> = listOf(
            OfflineRegionPreset("es_peninsula", "Países", "España (Península)", "Red vial y relieve general Z6–Z10", 35.95, 43.80, -9.35, 3.35, 6, 10),
            OfflineRegionPreset("pt", "Países", "Portugal", "País completo Z6–Z11", 36.95, 42.16, -9.55, -6.18, 6, 11),
            OfflineRegionPreset("ad", "Países", "Andorra", "Carreteras y pistas Z9–Z15", 42.42, 42.66, 1.40, 1.79, 9, 15),
            OfflineRegionPreset("pirineos", "Montaña / Trail", "Pirineos Completos", "De costa a costa Z8–Z12", 42.15, 43.05, -1.80, 3.25, 8, 12),
            OfflineRegionPreset("guadarrama", "Montaña / Trail", "Guadarrama y Gredos", "Detalle comarcal Z9–Z13", 40.35, 41.25, -4.75, -3.45, 9, 13),
            OfflineRegionPreset("ccaa_valencia", "Comunidades", "Comunitat Valenciana", "Comunidad completa Z8–Z12", 37.84, 40.79, -1.53, 0.52, 8, 12),
            OfflineRegionPreset("ccaa_madrid", "Comunidades", "Comunidad de Madrid", "Carreteras y pistas Z8–Z13", 39.88, 41.17, -4.58, -3.05, 8, 13)
        )
    }
}
