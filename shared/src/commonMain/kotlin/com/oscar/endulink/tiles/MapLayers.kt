package com.oscar.endulink.tiles

import kotlin.math.abs

enum class MapLayer(
    val raw: String,
    val title: String,
    val shortBadge: String,
    val maxNativeZoom: Int,
    val supportsOfflineDownload: Boolean,
    val recommendedConcurrency: Int,
    val isRetina512: Boolean = false
) {
    CARRETERAS(
        raw = "carto_voyager_hd",
        title = "Carreteras (Vial HD · Estilo MotIOS)",
        shortBadge = "VIAL HD",
        maxNativeZoom = 20,
        supportsOfflineDownload = false,
        recommendedConcurrency = 6,
        isRetina512 = true
    ),
    ESRI_STREET(
        raw = "esri_street",
        title = "Esri World Street · Carreteras (Offline)",
        shortBadge = "ESRI VIAL",
        maxNativeZoom = 18,
        supportsOfflineDownload = true,
        recommendedConcurrency = 6,
        isRetina512 = false
    ),
    OSMAND(
        raw = "osmand_hd",
        title = "OsmAnd Outdoor · Pistas y Senderos (Offline)",
        shortBadge = "OSMAND",
        maxNativeZoom = 19,
        supportsOfflineDownload = true,
        recommendedConcurrency = 4,
        isRetina512 = true
    ),
    TRAIL_OSM(
        raw = "trail_cyclosm",
        title = "Trail OSM · Pistas y Caminos (Offline)",
        shortBadge = "TRAIL OSM",
        maxNativeZoom = 18,
        supportsOfflineDownload = true,
        recommendedConcurrency = 2,
        isRetina512 = false
    ),
    IGN_TOPO(
        raw = "ign_topo",
        title = "IGN Topográfico España · Curvas (Offline)",
        shortBadge = "IGN TOPO",
        maxNativeZoom = 17,
        supportsOfflineDownload = true,
        recommendedConcurrency = 6,
        isRetina512 = false
    ),
    SATELITE_HIBRIDO(
        raw = "sat_hybrid_hd",
        title = "Satélite + Calles HD (Offline)",
        shortBadge = "SAT+VIAL",
        maxNativeZoom = 19,
        supportsOfflineDownload = true,
        recommendedConcurrency = 4,
        isRetina512 = true
    ),
    SATELITE_PURO(
        raw = "sat_pure_esri",
        title = "Satélite Puro · Ortofoto (Offline)",
        shortBadge = "SATÉLITE",
        maxNativeZoom = 18,
        supportsOfflineDownload = true,
        recommendedConcurrency = 6,
        isRetina512 = false
    );

    val estimatedTileBytes: Long
        get() = when {
            isRetina512 -> 49_152L
            this == IGN_TOPO -> 30_720L
            this == TRAIL_OSM -> 26_624L
            else -> 20_480L
        }

    fun tileURL(z: Int, x: Int, y: Int): String? {
        val clampedZ = minOf(maxOf(z, 1), maxNativeZoom)
        return when (this) {
            CARRETERAS -> {
                // Cartografía vial limpia estilo Apple Maps / MotIOS en resolución Retina @2x (512x512)
                val sub = listOf("a", "b", "c", "d")[abs(x + y) % 4]
                "https://$sub.basemaps.cartocdn.com/rastertiles/voyager/$clampedZ/$x/$y@2x.png?key=$CARTO_API_KEY"
            }
            ESRI_STREET -> {
                // Esri World Street Map (misma capa vial usada en MotIOS para la TFT, compatible con offline)
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/$clampedZ/$y/$x"
            }
            OSMAND -> {
                // Servidor oficial OsmAnd en Alta Definición (512x512 HD)
                "https://tile.osmand.net/hd/$clampedZ/$x/$y.png"
            }
            TRAIL_OSM -> {
                val sub = listOf("a", "b", "c")[abs(x + y) % 3]
                "https://$sub.tile-cyclosm.openstreetmap.fr/cyclosm/$clampedZ/$x/$y.png"
            }
            IGN_TOPO -> {
                "https://www.ign.es/wmts/mapa-raster?request=GetTile&service=WMTS&Version=1.0.0&Layer=MTN&Style=default&Format=image/jpeg&TileMatrixSet=GoogleMapsCompatible&TileMatrix=$clampedZ&TileCol=$x&TileRow=$y"
            }
            SATELITE_HIBRIDO -> {
                // Satélite híbrido con carreteras y localidades en resolución Retina @2x (512x512)
                "https://mt1.google.com/vt/lyrs=y&hl=es&x=$x&y=$y&z=$clampedZ&scale=2"
            }
            SATELITE_PURO -> {
                // Esri World Imagery (idéntico a MotIOS) con soporte de descarga offline rápida
                "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$clampedZ/$y/$x"
            }
        }
    }

    companion object {
        private const val CARTO_API_KEY = "cb1_57xe_1_59e8b74994b87bf911a96917"

        fun fromRaw(raw: String): MapLayer {
            return when (raw.lowercase()) {
                "road_online", "osm_std", "google_roads", "apple_roads_hd", "carto_voyager_hd" -> CARRETERAS
                "esri_street", "world_street", "esri_world_street" -> ESRI_STREET
                "osmand", "osmand_hd", "world_topo" -> OSMAND
                "sat_hybrid", "google_hybrid", "sat_hybrid_hd" -> SATELITE_HIBRIDO
                "sat_pure", "google_satellite", "sat_pure_esri" -> SATELITE_PURO
                else -> entries.find { it.raw.equals(raw, ignoreCase = true) } ?: CARRETERAS
            }
        }

        val offlineCapableLayers: List<MapLayer>
            get() = entries.filter { it.supportsOfflineDownload }

        fun parseOfflinePackageSpec(packageKey: String): OfflinePackageSpec? {
            val parts = packageKey.split("@")
            val baseKey = parts.firstOrNull() ?: return null
            val customMaxZ = parts.getOrNull(1)?.toIntOrNull()
            val layer = entries.firstOrNull { baseKey.endsWith("_${it.raw}") } ?: return null
            val presetId = baseKey.removeSuffix("_${layer.raw}")
            val preset = OfflineRegionPreset.catalog.firstOrNull { it.id == presetId } ?: return null
            val effectiveMaxZ = minOf(maxOf(customMaxZ ?: preset.maxZoom, preset.minZoom), layer.maxNativeZoom)
            return OfflinePackageSpec(preset = preset, layer = layer, maxZoom = effectiveMaxZ)
        }

        fun parseOfflinePackageKey(packageKey: String): Pair<OfflineRegionPreset, MapLayer>? {
            return parseOfflinePackageSpec(packageKey)?.let { it.preset to it.layer }
        }
    }
}

data class OfflinePackageSpec(
    val preset: OfflineRegionPreset,
    val layer: MapLayer,
    val maxZoom: Int
)

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
            // === COMUNIDADES AUTÓNOMAS DE ESPAÑA (17 CC.AA.) ===
            OfflineRegionPreset("ccaa_andalucia", "Comunidades España", "Andalucía", "Sierra Morena, Tabernas, Cazorla y Costa Z7–Z11", 35.95, 38.75, -7.55, -1.60, 7, 11),
            OfflineRegionPreset("ccaa_aragon", "Comunidades España", "Aragón", "Pirineo Aragonés, Monegros y Maestrazgo Z8–Z12", 39.85, 42.95, -2.15, 0.77, 8, 12),
            OfflineRegionPreset("ccaa_asturias", "Comunidades España", "Asturias (Principado)", "Picos de Europa, Somiedo y Cordillera Z8–Z13", 42.85, 43.65, -7.20, -4.50, 8, 13),
            OfflineRegionPreset("ccaa_baleares", "Comunidades España", "Islas Baleares", "Mallorca (Tramuntana), Menorca e Ibiza Z9–Z13", 38.60, 40.15, 1.15, 4.35, 9, 13),
            OfflineRegionPreset("ccaa_canarias", "Comunidades España", "Islas Canarias", "Tenerife, Gran Canaria, La Palma y archipiélago Z8–Z12", 27.50, 29.50, -18.25, -13.30, 8, 12),
            OfflineRegionPreset("ccaa_cantabria", "Comunidades España", "Cantabria", "Valles Pasiegos y macizo oriental de Picos Z9–Z13", 42.75, 43.55, -4.85, -3.15, 9, 13),
            OfflineRegionPreset("ccaa_castillalamancha", "Comunidades España", "Castilla-La Mancha", "Montes de Toledo, Serranía de Cuenca y Ruidera Z7–Z11", 38.00, 41.35, -5.45, -0.90, 7, 11),
            OfflineRegionPreset("ccaa_castillayleon", "Comunidades España", "Castilla y León", "Gredos, Cañón del Río Lobos y Urbión Z7–Z11", 40.05, 43.25, -7.15, -1.75, 7, 11),
            OfflineRegionPreset("ccaa_catalunya", "Comunidades España", "Cataluña / Catalunya", "Pirineo Catalán, Montseny y Priorat Z8–Z12", 40.50, 42.90, 0.15, 3.35, 8, 12),
            OfflineRegionPreset("ccaa_valencia", "Comunidades España", "Comunitat Valenciana", "Calderona, Espadán, Maestrat y Aitana Z8–Z12", 37.84, 40.79, -1.53, 0.52, 8, 12),
            OfflineRegionPreset("ccaa_extremadura", "Comunidades España", "Extremadura", "Las Hurdes, Monfragüe y Sierra de Gata Z8–Z12", 37.90, 40.50, -7.55, -4.65, 8, 12),
            OfflineRegionPreset("ccaa_galicia", "Comunidades España", "Galicia", "Ribeira Sacra, Ancares y Cañón del Sil Z8–Z12", 41.80, 43.80, -9.30, -6.70, 8, 12),
            OfflineRegionPreset("ccaa_madrid", "Comunidades España", "Comunidad de Madrid", "Sierra de Guadarrama y Sierra Norte Z8–Z13", 39.88, 41.17, -4.58, -3.05, 8, 13),
            OfflineRegionPreset("ccaa_murcia", "Comunidades España", "Región de Murcia", "Sierra Espuña y comarca del Noroeste Z8–Z12", 37.35, 38.75, -2.35, -0.65, 8, 12),
            OfflineRegionPreset("ccaa_navarra", "Comunidades España", "Navarra", "Bardenas Reales, Irati y Pirineo Navarro Z8–Z12", 41.90, 43.35, -2.50, -0.70, 8, 12),
            OfflineRegionPreset("ccaa_euskadi", "Comunidades España", "País Vasco / Euskadi", "Gorbeia, Aizkorri, Aralar y costa cantábrica Z9–Z13", 42.45, 43.45, -3.45, -1.70, 9, 13),
            OfflineRegionPreset("ccaa_larioja", "Comunidades España", "La Rioja", "Sierra de la Demanda y Cameros Z9–Z13", 41.90, 42.65, -3.15, -1.95, 9, 13),

            // === PAÍSES Y GRANDES REGIONES ===
            OfflineRegionPreset("pais_es_peninsula", "Países", "España (Península)", "Red vial, pistas y relieve general peninsular Z6–Z10", 35.95, 43.80, -9.35, 3.35, 6, 10),
            OfflineRegionPreset("pais_pt", "Países", "Portugal", "País completo (Norte, Centro, Alentejo y Algarve) Z6–Z11", 36.95, 42.16, -9.55, -6.18, 6, 11),
            OfflineRegionPreset("pais_ad", "Países", "Andorra", "Carreteras y pistas de alta montaña Z9–Z15", 42.42, 42.66, 1.40, 1.79, 9, 15),
            OfflineRegionPreset("pais_fr_sur", "Países", "Francia (Sur / Pirineos)", "Zona sur, Pirineos franceses y Occitania Z6–Z11", 42.30, 45.10, -1.80, 5.50, 6, 11),
            OfflineRegionPreset("pais_maroc", "Países", "Marruecos (Norte y Atlas)", "Rif, Medio Atlas, Alto Atlas y pistas trail Z6–Z10", 30.80, 36.00, -9.50, -1.80, 6, 10),
            OfflineRegionPreset("pais_it_alpes", "Países", "Italia (Norte / Alpes)", "Alpes italianos, Dolomitas y pasos alpinos Z6–Z11", 44.00, 47.10, 6.60, 13.80, 6, 11),

            // === ZONAS EMBLEMÁTICAS TRAIL Y MONTAÑA ===
            OfflineRegionPreset("trail_pirineos", "Zonas Trail", "Pirineos Completos", "De costa a costa (Atlántico a Mediterráneo) Z8–Z12", 42.15, 43.05, -1.80, 3.25, 8, 12),
            OfflineRegionPreset("trail_picos", "Zonas Trail", "Picos de Europa y Cantábrica", "Macizos Occidental, Central y Oriental Z9–Z13", 42.90, 43.40, -5.30, -4.50, 9, 13),
            OfflineRegionPreset("trail_central", "Zonas Trail", "Sistema Central (Guadarrama y Gredos)", "Pasos de montaña y detalle comarcal Z8–Z13", 40.10, 41.25, -5.60, -3.50, 8, 13),
            OfflineRegionPreset("trail_cazorla", "Zonas Trail", "Sierras de Cazorla y Segura", "Parque Natural y pistas de alta sierra Z9–Z13", 37.70, 38.45, -3.20, -2.40, 9, 13),
            OfflineRegionPreset("trail_bardenas", "Zonas Trail", "Bardenas Reales y Monegros", "Pistas desérticas y de tierra de referencia trail Z8–Z12", 41.30, 42.30, -1.70, 0.20, 8, 12)
        )
    }
}
