package com.oscar.endulink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.oscar.endulink.tiles.MapLayer
import com.oscar.endulink.tiles.OfflineDownloadProgress
import com.oscar.endulink.tiles.OfflineRegionPreset

/**
 * Gestor de Descarga de Mapas Offline para EnduLink.
 * Organizado por Secciones: Comunidades de España (17 CC.AA.), Países y Zonas Trail.
 */
@Composable
fun OfflineMapsSheet(
    selectedLayer: MapLayer,
    onSelectLayer: (MapLayer) -> Unit,
    downloadedPackages: Set<String>,
    progress: OfflineDownloadProgress,
    onDownloadRegion: (OfflineRegionPreset, MapLayer) -> Unit,
    onRemovePackage: (String) -> Unit,
    onCancelDownload: () -> Unit,
    onClearCache: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var offlineTargetLayer by remember {
        mutableStateOf(
            if (selectedLayer == MapLayer.IGN_TOPO) MapLayer.IGN_TOPO else MapLayer.TRAIL_OSM
        )
    }

    var selectedCategory by remember { mutableStateOf("Comunidades España") }
    var filterQuery by remember { mutableStateOf("") }

    val categories = listOf("Comunidades España", "Países", "Zonas Trail", "Todas")

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
        modifier = modifier.fillMaxWidth().fillMaxHeight(0.88f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 1. Cabecera con título, contador y botón de cierre
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DownloadTrayIcon(color = EnduPalette.Accent, size = 20.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Mapas Offline",
                            color = EnduPalette.TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${downloadedPackages.size} zonas guardadas en el dispositivo",
                            color = if (downloadedPackages.isNotEmpty()) EnduPalette.Success else EnduPalette.TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                Text(
                    text = "✕",
                    color = EnduPalette.TextMuted,
                    fontSize = 18.sp,
                    modifier = Modifier.clickable { onClose() }.padding(4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Selector de Capa Offline (Trail OSM vs IGN Topográfico España)
            Text(
                text = "Cartografía de descarga:",
                color = EnduPalette.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Opción 1: Trail OSM
                val isTrail = (offlineTargetLayer == MapLayer.TRAIL_OSM)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isTrail) EnduPalette.Accent.copy(alpha = 0.22f) else EnduPalette.SurfaceHigh)
                        .border(
                            width = if (isTrail) 1.5.dp else 0.5.dp,
                            color = if (isTrail) EnduPalette.Accent else EnduPalette.Outline,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            offlineTargetLayer = MapLayer.TRAIL_OSM
                            onSelectLayer(MapLayer.TRAIL_OSM)
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = if (isTrail) "◉" else "○", color = if (isTrail) EnduPalette.Accent else EnduPalette.TextMuted, fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "🚵 Trail OSM",
                                color = if (isTrail) EnduPalette.Accent else EnduPalette.TextPrimary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Pistas, tierra y relieve",
                            color = EnduPalette.TextMuted,
                            fontSize = 10.sp
                        )
                    }
                }

                // Opción 2: IGN Topográfico España (MTN25)
                val isIgn = (offlineTargetLayer == MapLayer.IGN_TOPO)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isIgn) EnduPalette.Accent.copy(alpha = 0.22f) else EnduPalette.SurfaceHigh)
                        .border(
                            width = if (isIgn) 1.5.dp else 0.5.dp,
                            color = if (isIgn) EnduPalette.Accent else EnduPalette.Outline,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            offlineTargetLayer = MapLayer.IGN_TOPO
                            onSelectLayer(MapLayer.IGN_TOPO)
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = if (isIgn) "◉" else "○", color = if (isIgn) EnduPalette.Accent else EnduPalette.TextMuted, fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "⛰️ IGN Topo",
                                color = if (isIgn) EnduPalette.Accent else EnduPalette.TextPrimary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "MTN25 España oficial",
                            color = EnduPalette.TextMuted,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3. Progreso de Descarga en curso (si hay descarga activa)
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
                        Text(
                            text = "${progress.completed} / ${progress.total} teselas (${(pct * 100).toInt()}%)",
                            color = EnduPalette.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 4. Selector de Secciones / Categorías en scroll horizontal
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                categories.forEach { cat ->
                    val isSelected = (selectedCategory == cat)
                    val label = when (cat) {
                        "Comunidades España" -> "🇪🇸 Comunidades (17)"
                        "Países" -> "🌍 Países"
                        "Zonas Trail" -> "🏔️ Zonas Trail"
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
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 5. Barra de búsqueda rápida de regiones
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(EnduPalette.SurfaceHigh)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SearchLensIcon(color = EnduPalette.TextMuted, size = 13.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    BasicTextField(
                        value = filterQuery,
                        onValueChange = { filterQuery = it },
                        textStyle = TextStyle(color = EnduPalette.TextPrimary, fontSize = 12.sp),
                        cursorBrush = SolidColor(EnduPalette.Accent),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            if (filterQuery.isEmpty()) {
                                Text(
                                    text = "Buscar por nombre (ej. Valencia, Pirineos, Portugal...)",
                                    color = EnduPalette.TextMuted,
                                    fontSize = 11.5.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (filterQuery.isNotEmpty()) {
                        Text(
                            text = "✕",
                            color = EnduPalette.TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { filterQuery = "" }.padding(2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 6. Lista filtrada de regiones
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
                        val packageKey = "${preset.id}_${offlineTargetLayer.raw}"
                        val isDownloaded = packageKey in downloadedPackages

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(EnduPalette.Background)
                                .border(
                                    width = if (isDownloaded) 1.dp else 0.5.dp,
                                    color = if (isDownloaded) EnduPalette.Success.copy(alpha = 0.6f) else EnduPalette.Outline,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = preset.name,
                                        color = EnduPalette.TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (isDownloaded) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "✓ Guardado",
                                            color = EnduPalette.Success,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text(
                                    text = "${preset.subtitle} · ${preset.category}",
                                    color = EnduPalette.TextMuted,
                                    fontSize = 11.sp
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = { onDownloadRegion(preset, offlineTargetLayer) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isDownloaded) EnduPalette.SurfaceHigh else EnduPalette.Accent
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (isDownloaded) "🔄 Actualizar" else "⬇️ Descargar",
                                        color = if (isDownloaded) EnduPalette.TextPrimary else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                if (isDownloaded) {
                                    Text(
                                        text = "🗑️",
                                        fontSize = 13.sp,
                                        modifier = Modifier
                                            .clickable { onRemovePackage(packageKey) }
                                            .padding(4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 7. Botón vaciar almacenamiento offline
            TextButton(
                onClick = onClearCache,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(text = "🗑️ Vaciar todo el almacenamiento offline", color = EnduPalette.TextMuted, fontSize = 12.sp)
            }
        }
    }
}
