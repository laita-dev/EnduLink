package com.oscar.endulink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oscar.endulink.tiles.MapBoundingBox
import com.oscar.endulink.tiles.MapLayer
import com.oscar.endulink.tiles.OfflineDownloadProgress
import com.oscar.endulink.tiles.OfflinePackageSpec
import com.oscar.endulink.tiles.OfflineRegionPreset
import com.oscar.endulink.tiles.TileMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val MAX_TILES_PER_DOWNLOAD = 60_000L

private enum class OfflineSheetTab {
    DOWNLOAD,
    MANAGE
}

/**
 * Gestor completo de Mapas Offline para EnduLink:
 * - Pestaña "Descargar zonas": selector de capa, selector de zoom máximo, estimación de tamaño y catálogo.
 * - Pestaña "Descargados": administración de paquetes guardados y uso de almacenamiento por capa en segundo plano.
 */
@Composable
fun OfflineMapsSheet(
    selectedLayer: MapLayer,
    onSelectLayer: (MapLayer) -> Unit,
    downloadedPackages: Set<String>,
    progress: OfflineDownloadProgress,
    onDownloadRegion: (OfflineRegionPreset, MapLayer, Int?) -> Unit,
    onRemovePackage: (String) -> Unit,
    onClearLayerCache: (MapLayer) -> Unit,
    getCacheSizeBytes: (MapLayer?) -> Long,
    onCancelDownload: () -> Unit,
    onClearCache: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(OfflineSheetTab.DOWNLOAD) }
    var offlineTargetLayer by remember {
        mutableStateOf(
            if (selectedLayer.supportsOfflineDownload) selectedLayer else MapLayer.OSMAND
        )
    }
    var selectedCategory by remember { mutableStateOf("Comunidades España") }
    var selectedMaxZoomOverride by remember { mutableStateOf<Int?>(null) }
    var filterQuery by remember { mutableStateOf("") }
    var storageRevision by remember { mutableStateOf(0) }

    var totalBytes by remember { mutableStateOf(0L) }
    var layerStorage by remember { mutableStateOf<List<Triple<MapLayer, Int, Long>>>(emptyList()) }
    var isScanningStorage by remember { mutableStateOf(false) }

    val parsedDownloadedItems = remember(downloadedPackages) {
        downloadedPackages.mapNotNull { key ->
            MapLayer.parseOfflinePackageSpec(key)?.let { spec -> key to spec }
        }.sortedWith(compareBy({ it.second.preset.name }, { it.second.layer.shortBadge }))
    }

    // Cálculo de caché en disco fuera del hilo de UI y solo cuando cambia la pestaña, descarga terminada o limpieza
    LaunchedEffect(activeTab, downloadedPackages, progress.isDownloading, storageRevision) {
        isScanningStorage = true
        val needPerLayer = (activeTab == OfflineSheetTab.MANAGE)
        val (computedTotal, computedPerLayer) = withContext(Dispatchers.Default) {
            val tot = getCacheSizeBytes(null)
            val perLayer = if (needPerLayer) {
                MapLayer.entries.mapNotNull { lay ->
                    val size = getCacheSizeBytes(lay)
                    val count = parsedDownloadedItems.count { it.second.layer == lay }
                    if (size > 0L || count > 0) Triple(lay, count, size) else null
                }
            } else {
                emptyList()
            }
            tot to perLayer
        }
        totalBytes = computedTotal
        if (needPerLayer) {
            layerStorage = computedPerLayer
        }
        isScanningStorage = false
    }

    val downloadedPackageByPresetLayer = remember(parsedDownloadedItems) {
        buildMap {
            for ((key, spec) in parsedDownloadedItems) {
                put(spec.preset.id to spec.layer, key to spec)
            }
        }
    }

    val categories = remember { listOf("Comunidades España", "Países", "Zonas Trail", "Todas") }

    val filteredPresets = remember(selectedCategory, filterQuery) {
        OfflineRegionPreset.catalog.filter { preset ->
            val matchesCategory = (selectedCategory == "Todas" || preset.category == selectedCategory)
            val matchesQuery = filterQuery.isBlank() ||
                preset.name.contains(filterQuery, ignoreCase = true) ||
                preset.subtitle.contains(filterQuery, ignoreCase = true)
            matchesCategory && matchesQuery
        }
    }

    Card(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
        modifier = modifier.fillMaxWidth().fillMaxHeight(0.90f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 1. Cabecera con título, resumen y botón de cierre
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(EnduPalette.Accent.copy(alpha = 0.16f))
                    ) {
                        DownloadTrayIcon(color = EnduPalette.Accent, size = 18.dp)
                    }
                    Column {
                        Text(
                            text = "Mapas Offline",
                            color = EnduPalette.TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        val sizeText = if (isScanningStorage && totalBytes == 0L) "calculando..." else "${formatBytesCompact(totalBytes)} en caché"
                        Text(
                            text = "${downloadedPackages.size} zonas · $sizeText",
                            color = if (downloadedPackages.isNotEmpty()) EnduPalette.Success else EnduPalette.TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    CloseCrossIcon(color = EnduPalette.TextMuted, size = 14.dp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Selector de pestañas: Descargar vs Descargados
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(EnduPalette.Background)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OfflineTabButton(
                    label = "Descargar zonas",
                    isSelected = (activeTab == OfflineSheetTab.DOWNLOAD),
                    badge = null,
                    onClick = { activeTab = OfflineSheetTab.DOWNLOAD },
                    modifier = Modifier.weight(1f)
                )
                OfflineTabButton(
                    label = "Descargados",
                    isSelected = (activeTab == OfflineSheetTab.MANAGE),
                    badge = "${downloadedPackages.size}",
                    onClick = { activeTab = OfflineSheetTab.MANAGE },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3. Tarjeta de descarga en curso (visible en ambas pestañas)
            if (progress.isDownloading) {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = EnduPalette.SurfaceHigh),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Descargando ${progress.label}...",
                                color = EnduPalette.Accent,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Cancelar",
                                color = EnduPalette.Danger,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable { onCancelDownload() }
                                    .padding(4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        val pct = if (progress.total > 0) progress.completed.toFloat() / progress.total.toFloat() else 0f
                        LinearProgressIndicator(
                            progress = { pct },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = EnduPalette.Accent,
                            trackColor = EnduPalette.Outline
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val totalText = if (progress.total > 0) "${progress.completed} / ${progress.total} teselas (${(pct * 100).toInt()}%)" else "Preparando teselas..."
                        Text(
                            text = totalText,
                            color = EnduPalette.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (activeTab == OfflineSheetTab.DOWNLOAD) {
                // ==========================================
                // PESTAÑA 1: DESCARGAR ZONAS
                // ==========================================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Cartografía a descargar",
                        color = EnduPalette.TextSecondary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = offlineTargetLayer.shortBadge,
                        color = EnduPalette.Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))

                val row1Layers = remember {
                    listOf(
                        Triple(MapLayer.OSMAND, "OsmAnd HD", "Pistas y curvas"),
                        Triple(MapLayer.TRAIL_OSM, "Trail OSM", "Caminos y relieve"),
                        Triple(MapLayer.IGN_TOPO, "IGN Topo", "MTN25 España")
                    )
                }
                val row2Layers = remember {
                    listOf(
                        Triple(MapLayer.ESRI_STREET, "Esri Street", "Vial clásico"),
                        Triple(MapLayer.SATELITE_PURO, "Satélite Puro", "Ortofoto Esri"),
                        Triple(MapLayer.SATELITE_HIBRIDO, "Sat + Calles", "Híbrido HD")
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(row1Layers, row2Layers).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for ((lay, title, sub) in rowItems) {
                                val isSel = (offlineTargetLayer == lay)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSel) EnduPalette.Accent.copy(alpha = 0.20f) else EnduPalette.SurfaceHigh)
                                        .border(
                                            width = if (isSel) 1.5.dp else 0.5.dp,
                                            color = if (isSel) EnduPalette.Accent else EnduPalette.Outline,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            offlineTargetLayer = lay
                                            onSelectLayer(lay)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 7.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = title,
                                            color = if (isSel) EnduPalette.Accent else EnduPalette.TextPrimary,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = sub,
                                            color = EnduPalette.TextMuted,
                                            fontSize = 9.5.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Selector de detalle / zoom máximo a descargar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Nivel de zoom máximo",
                        color = EnduPalette.TextSecondary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = selectedMaxZoomOverride?.let { "Hasta Z$it (máx. capa Z${offlineTargetLayer.maxNativeZoom})" }
                            ?: "Estándar por región",
                        color = EnduPalette.TextMuted,
                        fontSize = 10.sp
                    )
                }
                Spacer(modifier = Modifier.height(5.dp))
                val zoomOptions = remember { listOf<Int?>(null, 11, 12, 13, 14, 15) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    zoomOptions.forEach { zOpt ->
                        val isSel = (selectedMaxZoomOverride == zOpt)
                        val isSupported = zOpt == null || zOpt <= offlineTargetLayer.maxNativeZoom
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        !isSupported -> EnduPalette.SurfaceHigh.copy(alpha = 0.45f)
                                        isSel -> EnduPalette.Accent
                                        else -> EnduPalette.SurfaceHigh
                                    }
                                )
                                .border(
                                    width = if (isSel) 1.dp else 0.5.dp,
                                    color = if (isSel) EnduPalette.Accent else EnduPalette.Outline,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable(enabled = isSupported) { selectedMaxZoomOverride = zOpt }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                text = zOpt?.let { "Z$it" } ?: "Auto",
                                color = when {
                                    !isSupported -> EnduPalette.TextMuted.copy(alpha = 0.45f)
                                    isSel -> Color.White
                                    else -> EnduPalette.TextSecondary
                                },
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Selector de Categorías
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { cat ->
                        val isSelected = (selectedCategory == cat)
                        val label = when (cat) {
                            "Comunidades España" -> "Comunidades (17)"
                            "Países" -> "Países"
                            "Zonas Trail" -> "Zonas Trail"
                            else -> "Todas (${OfflineRegionPreset.catalog.size})"
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) EnduPalette.Accent else EnduPalette.SurfaceHigh)
                                .clickable { selectedCategory = cat }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color.White else EnduPalette.TextSecondary,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Buscador de regiones
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(EnduPalette.SurfaceHigh)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SearchLensIcon(color = EnduPalette.TextMuted, size = 14.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicTextField(
                            value = filterQuery,
                            onValueChange = { filterQuery = it },
                            textStyle = TextStyle(color = EnduPalette.TextPrimary, fontSize = 12.sp),
                            cursorBrush = SolidColor(EnduPalette.Accent),
                            modifier = Modifier.weight(1f),
                            decorationBox = { innerTextField ->
                                if (filterQuery.isEmpty()) {
                                    Text(
                                        text = "Buscar región (ej. Valencia, Pirineos, Portugal...)",
                                        color = EnduPalette.TextMuted,
                                        fontSize = 11.5.sp
                                    )
                                }
                                innerTextField()
                            }
                        )
                        if (filterQuery.isNotEmpty()) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { filterQuery = "" }
                            ) {
                                CloseCrossIcon(color = EnduPalette.TextMuted, size = 11.dp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Lista filtrada de zonas con tamaño estimado por nivel de zoom
                if (filteredPresets.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No se encontraron regiones con '$filterQuery'",
                            color = EnduPalette.TextMuted,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredPresets, key = { it.id }) { preset ->
                            val existingEntry = downloadedPackageByPresetLayer[preset.id to offlineTargetLayer]
                            val isDownloaded = existingEntry != null
                            val effMaxZ = minOf(
                                maxOf(selectedMaxZoomOverride ?: preset.maxZoom, preset.minZoom),
                                offlineTargetLayer.maxNativeZoom
                            )
                            val estimate = remember(preset.id, offlineTargetLayer, effMaxZ) {
                                val bbox = MapBoundingBox(preset.minLat, preset.maxLat, preset.minLon, preset.maxLon)
                                val tiles = TileMath.countBoundingBoxTilesLong(
                                    bbox = bbox,
                                    minZ = preset.minZoom,
                                    maxZ = effMaxZ,
                                    maxNativeZ = offlineTargetLayer.maxNativeZoom
                                )
                                tiles to (tiles * offlineTargetLayer.estimatedTileBytes)
                            }
                            val estTiles = estimate.first
                            val estBytes = estimate.second
                            val exceedsLimit = estTiles > MAX_TILES_PER_DOWNLOAD

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(EnduPalette.Background)
                                    .border(
                                        width = if (isDownloaded) 1.dp else 0.5.dp,
                                        color = if (isDownloaded) EnduPalette.Success.copy(alpha = 0.65f) else EnduPalette.Outline,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = preset.name,
                                            color = EnduPalette.TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (isDownloaded) {
                                            CheckCircleIcon(color = EnduPalette.Success, size = 14.dp)
                                        }
                                    }
                                    Text(
                                        text = "Z${preset.minZoom}–Z$effMaxZ · ~${formatTileCountCompact(estTiles)} teselas · ~${formatBytesCompact(estBytes)}",
                                        color = if (exceedsLimit) EnduPalette.Warning else EnduPalette.AccentBlue,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (exceedsLimit) {
                                        Text(
                                            text = "Supera ${formatTileCountCompact(MAX_TILES_PER_DOWNLOAD)} teselas; elige un zoom menor.",
                                            color = EnduPalette.Warning,
                                            fontSize = 10.sp
                                        )
                                    } else if (existingEntry != null) {
                                        Text(
                                            text = "Guardado hasta Z${existingEntry.second.maxZoom} · ${preset.category}",
                                            color = EnduPalette.TextMuted,
                                            fontSize = 10.sp
                                        )
                                    } else {
                                        Text(
                                            text = preset.category,
                                            color = EnduPalette.TextMuted,
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Button(
                                        onClick = { onDownloadRegion(preset, offlineTargetLayer, effMaxZ) },
                                        enabled = !exceedsLimit,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isDownloaded) EnduPalette.SurfaceHigh else EnduPalette.Accent,
                                            disabledContainerColor = EnduPalette.SurfaceHigh.copy(alpha = 0.45f)
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            if (isDownloaded) {
                                                RefreshArrowsIcon(color = EnduPalette.TextPrimary, size = 12.dp)
                                            } else {
                                                DownloadTrayIcon(
                                                    color = if (exceedsLimit) EnduPalette.TextMuted else Color.White,
                                                    size = 12.dp
                                                )
                                            }
                                            Text(
                                                text = if (isDownloaded) "Actualizar" else "Descargar",
                                                color = when {
                                                    exceedsLimit -> EnduPalette.TextMuted
                                                    isDownloaded -> EnduPalette.TextPrimary
                                                    else -> Color.White
                                                },
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    if (existingEntry != null) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(EnduPalette.Danger.copy(alpha = 0.12f))
                                                .clickable {
                                                    onRemovePackage(existingEntry.first)
                                                    storageRevision++
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            TrashDeleteIcon(color = EnduPalette.Danger, size = 14.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // ==========================================
                // PESTAÑA 2: ADMINISTRAR MAPAS DESCARGADOS
                // ==========================================
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(EnduPalette.Background)
                        .border(0.5.dp, EnduPalette.Outline, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val usedStr = if (isScanningStorage && totalBytes == 0L) "calculando..." else formatBytesCompact(totalBytes)
                        Text(
                            text = "Espacio usado: $usedStr",
                            color = EnduPalette.TextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (totalBytes > 0L || downloadedPackages.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(EnduPalette.Danger.copy(alpha = 0.14f))
                                    .clickable {
                                        onClearCache()
                                        storageRevision++
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                TrashDeleteIcon(color = EnduPalette.Danger, size = 12.dp)
                                Text(
                                    text = "Vaciar todo",
                                    color = EnduPalette.Danger,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (layerStorage.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            layerStorage.forEach { (lay, count, size) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(EnduPalette.SurfaceHigh)
                                        .padding(horizontal = 8.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "${lay.shortBadge}: ${formatBytesCompact(size)}" + if (count > 0) " ($count)" else "",
                                        color = EnduPalette.TextSecondary,
                                        fontSize = 10.5.sp
                                    )
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable {
                                                onClearLayerCache(lay)
                                                storageRevision++
                                            }
                                    ) {
                                        CloseCrossIcon(color = EnduPalette.TextMuted, size = 10.dp)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (parsedDownloadedItems.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        DownloadTrayIcon(color = EnduPalette.TextMuted, size = 30.dp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No tienes zonas descargadas",
                            color = EnduPalette.TextPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Descarga una comunidad, país o zona trail para navegar sin cobertura.",
                            color = EnduPalette.TextMuted,
                            fontSize = 11.5.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { activeTab = OfflineSheetTab.DOWNLOAD },
                            colors = ButtonDefaults.buttonColors(containerColor = EnduPalette.Accent),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Explorar zonas para descargar", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(parsedDownloadedItems, key = { it.first }) { (packageKey, spec) ->
                            val estBytes = remember(packageKey) {
                                val bbox = MapBoundingBox(spec.preset.minLat, spec.preset.maxLat, spec.preset.minLon, spec.preset.maxLon)
                                val tiles = TileMath.countBoundingBoxTilesLong(bbox, spec.preset.minZoom, spec.maxZoom, spec.layer.maxNativeZoom)
                                tiles * spec.layer.estimatedTileBytes
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(EnduPalette.Background)
                                    .border(0.8.dp, EnduPalette.Success.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        CheckCircleIcon(color = EnduPalette.Success, size = 14.dp)
                                        Text(
                                            text = spec.preset.name,
                                            color = EnduPalette.TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(EnduPalette.Accent.copy(alpha = 0.18f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = spec.layer.shortBadge,
                                                color = EnduPalette.Accent,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Text(
                                            text = "Z${spec.preset.minZoom}–Z${spec.maxZoom} · ~${formatBytesCompact(estBytes)} · ${spec.preset.category}",
                                            color = EnduPalette.TextMuted,
                                            fontSize = 10.5.sp
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(EnduPalette.SurfaceHigh)
                                            .clickable { onDownloadRegion(spec.preset, spec.layer, spec.maxZoom) }
                                    ) {
                                        RefreshArrowsIcon(color = EnduPalette.Accent, size = 14.dp)
                                    }
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(EnduPalette.Danger.copy(alpha = 0.14f))
                                            .clickable {
                                                onRemovePackage(packageKey)
                                                storageRevision++
                                            }
                                    ) {
                                        TrashDeleteIcon(color = EnduPalette.Danger, size = 14.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineTabButton(
    label: String,
    isSelected: Boolean,
    badge: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) EnduPalette.Accent else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.White else EnduPalette.TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
        if (badge != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) Color.White.copy(alpha = 0.22f) else EnduPalette.SurfaceHigh)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = badge,
                    color = if (isSelected) Color.White else EnduPalette.TextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun formatTileCountCompact(count: Long): String {
    return when {
        count >= 1_000_000L -> "${((count / 100_000.0).roundToInt() / 10.0)}M"
        count >= 10_000L -> "${(count / 1000.0).roundToInt()}k"
        count >= 1_000L -> "${((count / 100.0).roundToInt() / 10.0)}k"
        else -> "$count"
    }
}

private fun formatBytesCompact(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1.0) {
        return "${((gb * 10.0).roundToInt() / 10.0)} GB"
    }
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 100.0) {
        "${mb.roundToInt()} MB"
    } else if (mb >= 0.1) {
        "${(mb * 10.0).roundToInt() / 10.0} MB"
    } else {
        val kb = (bytes / 1024.0).roundToInt().coerceAtLeast(1)
        "$kb KB"
    }
}
