package com.oscar.endulink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oscar.endulink.trail.GPXWaypoint
import com.oscar.endulink.trail.GeoCoord
import kotlin.math.roundToInt

@Composable
fun WaypointsSheet(
    waypoints: List<GPXWaypoint>,
    riderCoord: GeoCoord?,
    onNavigateToWaypoint: (GPXWaypoint) -> Unit,
    onCenterOnWaypoint: (GPXWaypoint) -> Unit,
    onEditWaypoint: (GPXWaypoint) -> Unit,
    onDeleteWaypoint: (GPXWaypoint) -> Unit,
    onAddNewWaypoint: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategoryFilter by remember { mutableStateOf<String?>(null) }

    // Ordenar por distancia si hay GPS, o por nombre
    val sortedAndFiltered = remember(waypoints, searchQuery, selectedCategoryFilter, riderCoord) {
        waypoints
            .filter { wpt ->
                val matchesSearch = searchQuery.isBlank() || wpt.name.contains(searchQuery, ignoreCase = true)
                val matchesCat = selectedCategoryFilter == null || wpt.symbol?.equals(selectedCategoryFilter, ignoreCase = true) == true
                matchesSearch && matchesCat
            }
            .sortedBy { wpt ->
                riderCoord?.let { it.distanceTo(wpt.coordinate) } ?: Double.MAX_VALUE
            }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = EnduPalette.Background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // Cabecera
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Mis Waypoints",
                        color = EnduPalette.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF00D4FF).copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${waypoints.size}",
                            color = Color(0xFF00D4FF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Botón Añadir
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(EnduPalette.Accent)
                            .clickable { onAddNewWaypoint() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "➕", fontSize = 11.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Nuevo",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = "Cerrar",
                        color = EnduPalette.TextMuted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable { onClose() }
                            .padding(4.dp)
                    )
                }
            }

            // Barra de búsqueda rápida
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(EnduPalette.Surface)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "🔍", fontSize = 12.sp)
                Spacer(modifier = Modifier.width(6.dp))
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filtrar waypoints...", color = EnduPalette.TextMuted, fontSize = 13.sp) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = EnduPalette.TextPrimary,
                        unfocusedTextColor = EnduPalette.TextPrimary
                    ),
                    modifier = Modifier.weight(1f)
                )
                if (searchQuery.isNotEmpty()) {
                    Text(
                        text = "✕",
                        color = EnduPalette.TextMuted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { searchQuery = "" }.padding(4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Filtros de categoría
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    val isAll = selectedCategoryFilter == null
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isAll) EnduPalette.Accent else EnduPalette.Surface)
                            .clickable { selectedCategoryFilter = null }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Todos",
                            color = if (isAll) Color.White else EnduPalette.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                items(WAYPOINT_CATEGORIES) { cat ->
                    val isSelected = selectedCategoryFilter == cat.id
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) cat.color.copy(alpha = 0.25f) else EnduPalette.Surface)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) cat.color else EnduPalette.Outline,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                selectedCategoryFilter = if (isSelected) null else cat.id
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = cat.icon, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = cat.title,
                            color = if (isSelected) cat.color else EnduPalette.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Lista de Waypoints
            if (sortedAndFiltered.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "📍", fontSize = 36.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (waypoints.isEmpty()) "No tienes waypoints guardados" else "No se encontraron coincidencias",
                            color = EnduPalette.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Mantén pulsado en cualquier punto del mapa para guardar un waypoint con su ubicación exacta.",
                            color = EnduPalette.TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 30.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sortedAndFiltered, key = { it.id }) { wpt ->
                        val (catTitle, icon, color) = when (wpt.symbol?.lowercase()) {
                            "gate" -> Triple("Cancela / Valla", "🚪", Color(0xFFFF9800))
                            "rock" -> Triple("Paso Difícil", "🪨", Color(0xFFFF5722))
                            "water" -> Triple("Fuente / Agua", "💧", Color(0xFF00E5FF))
                            "danger" -> Triple("Peligro", "⚠️", Color(0xFFFF1744))
                            "fuel" -> Triple("Gasolinera", "⛽", Color(0xFF00E676))
                            "camp" -> Triple("Mirador / Camp", "🏕️", Color(0xFFAB47BC))
                            else -> Triple("Punto", "📍", Color(0xFFFFC107))
                        }

                        val distanceStr = riderCoord?.let {
                            val distKm = (it.distanceTo(wpt.coordinate) / 100.0).roundToInt() / 10.0
                            " · a $distKm km"
                        } ?: ""

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(EnduPalette.Surface)
                                .border(1.dp, EnduPalette.Outline, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(color.copy(alpha = 0.2f))
                                        .border(1.dp, color, CircleShape)
                                ) {
                                    Text(text = icon, fontSize = 16.sp)
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = wpt.name,
                                        color = EnduPalette.TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    val latStr = (wpt.latitude * 10000.0).roundToInt() / 10000.0
                                    val lonStr = (wpt.longitude * 10000.0).roundToInt() / 10000.0
                                    Text(
                                        text = "$catTitle$distanceStr ($latStr, $lonStr)",
                                        color = EnduPalette.TextMuted,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Botones de acción rápida por waypoint
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // 1. Ver en el mapa
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(EnduPalette.SurfaceHigh)
                                        .clickable {
                                            onCenterOnWaypoint(wpt)
                                            onClose()
                                        }
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = "🗺️", fontSize = 11.sp)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(text = "Ver", color = EnduPalette.TextPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }

                                // 2. Ir / Navegar
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1.2f)
                                        .height(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(EnduPalette.Accent)
                                        .clickable {
                                            onNavigateToWaypoint(wpt)
                                            onClose()
                                        }
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = "🚗", fontSize = 11.sp)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(text = "Ir", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                // 3. Editar
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(EnduPalette.SurfaceHigh)
                                        .clickable { onEditWaypoint(wpt) }
                                ) {
                                    Text(text = "✏️", fontSize = 12.sp)
                                }

                                // 4. Borrar
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(EnduPalette.Danger.copy(alpha = 0.15f))
                                        .clickable { onDeleteWaypoint(wpt) }
                                ) {
                                    Text(text = "🗑️", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

