package com.oscar.endulink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oscar.endulink.trail.GPXRoute
import com.oscar.endulink.trail.GeoCoord
import com.oscar.endulink.trail.TrackColor
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Panel inferior con información completa y todas las opciones del track GPX seleccionado en el mapa.
 */
@Composable
fun RouteDetailSheet(
    route: GPXRoute,
    isActive: Boolean,
    riderCoord: GeoCoord?,
    onStartNavigation: (GPXRoute) -> Unit,
    onCenterOnMap: (GPXRoute) -> Unit,
    onOpenElevation: (GPXRoute) -> Unit,
    onSelectColor: (GPXRoute, TrackColor) -> Unit,
    onEditDetails: (GPXRoute) -> Unit,
    onInvertRoute: (GPXRoute) -> Unit,
    onToggleVisibility: (GPXRoute) -> Unit,
    onDownloadCorridor: (GPXRoute) -> Unit,
    onShareRoute: (GPXRoute) -> Unit,
    onDeleteRoute: (GPXRoute) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmDelete by remember(route.id) { mutableStateOf(false) }
    LaunchedEffect(confirmDelete) {
        if (confirmDelete) {
            delay(3000L)
            confirmDelete = false
        }
    }

    val routeColor = Color(route.color.rgbHex)
    val distanceStartKm = riderCoord?.let {
        route.points.firstOrNull()?.coordinate?.let { start ->
            ((it.distanceTo(start) / 100.0).roundToInt() / 10.0)
        }
    }
    val elevations = remember(route.id, route.points.size) { route.points.mapNotNull { it.elevation } }
    val eleRangeText = if (elevations.isNotEmpty()) {
        "${elevations.min().roundToInt()}–${elevations.max().roundToInt()} m"
    } else {
        "${route.points.size} pts"
    }
    val distKmText = ((route.totalDistanceKm * 10.0).roundToInt() / 10.0).toString()

    Card(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Cabecera: Indicador de color, nombre completo del track y botón cerrar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(routeColor.copy(alpha = 0.22f))
                        .border(2.dp, routeColor, CircleShape)
                        .clickable { onEditDetails(route) }
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(routeColor)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = route.name + if (route.isInverted) " (Inv)" else "",
                        color = EnduPalette.TextPrimary,
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = buildString {
                        append(if (isActive) "Track activo" else "Track GPX")
                        if (!route.isVisible) append(" · Oculto")
                        if (distanceStartKm != null) append(" · a $distanceStartKm km")
                    }
                    Text(
                        text = subtitle,
                        color = if (isActive) EnduPalette.Accent else EnduPalette.TextMuted,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = "✕",
                    color = EnduPalette.TextMuted,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onClose() }
                        .padding(8.dp)
                )
            }

            // Notas del track si existen
            if (route.notes.isNotBlank()) {
                Text(
                    text = route.notes,
                    color = EnduPalette.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(EnduPalette.SurfaceHigh.copy(alpha = 0.65f))
                        .clickable { onEditDetails(route) }
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }

            // Barra de estadísticas del track
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(EnduPalette.SurfaceHigh)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RouteStatItem(label = "DISTANCIA", value = "$distKmText km")
                RouteStatItem(label = "DESNIVEL", value = "+${route.elevationGainM.roundToInt()} m")
                RouteStatItem(
                    label = if (elevations.isNotEmpty()) "ALTITUD" else "PUNTOS",
                    value = eleRangeText
                )
                if (route.waypoints.isNotEmpty()) {
                    RouteStatItem(label = "WPTS", value = "${route.waypoints.size}")
                }
            }

            // Selector rápido de color directo sobre el mapa
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "COLOR EN EL MAPA",
                    color = EnduPalette.TextMuted,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold
                )
                TrackColorPickerRow(
                    selected = route.color,
                    onSelect = { color -> onSelectColor(route, color) }
                )
            }

            // Acciones principales: Navegar / Perfil de Altitud / Centrar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1.3f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(EnduPalette.Accent)
                        .clickable {
                            onStartNavigation(route)
                            onClose()
                        }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NavArrowIcon(color = Color.White, size = 13.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Seguir track",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            softWrap = false
                        )
                    }
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF00D4FF).copy(alpha = 0.18f))
                        .border(1.dp, Color(0xFF00D4FF).copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                        .clickable {
                            onClose()
                            onOpenElevation(route)
                        }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ElevationChartIcon(color = Color(0xFF00D4FF), size = 14.dp)
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Perfil",
                            color = Color(0xFF00D4FF),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            softWrap = false
                        )
                    }
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(EnduPalette.SurfaceHigh)
                        .clickable { onCenterOnMap(route) }
                        .padding(horizontal = 12.dp)
                ) {
                    MapPinTargetIcon(color = EnduPalette.TextPrimary, size = 16.dp)
                }
            }

            // Opciones del track (2 filas de 3 botones vectoriales)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RouteDetailOptionButton(
                    label = "Editar",
                    onClick = { onEditDetails(route) },
                    modifier = Modifier.weight(1f)
                ) {
                    EditPencilIcon(color = EnduPalette.TextPrimary, size = 13.dp)
                }

                RouteDetailOptionButton(
                    label = if (route.isInverted) "Invertido" else "Invertir",
                    highlighted = route.isInverted,
                    onClick = { onInvertRoute(route) },
                    modifier = Modifier.weight(1f)
                ) {
                    InvertRouteArrowsIcon(
                        color = if (route.isInverted) EnduPalette.Accent else EnduPalette.TextPrimary,
                        size = 13.dp
                    )
                }

                RouteDetailOptionButton(
                    label = if (route.isVisible) "Ocultar" else "Mostrar",
                    onClick = { onToggleVisibility(route) },
                    modifier = Modifier.weight(1f)
                ) {
                    EyeVisibilityIcon(
                        visible = route.isVisible,
                        color = if (route.isVisible) EnduPalette.TextPrimary else EnduPalette.TextMuted,
                        size = 14.dp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RouteDetailOptionButton(
                    label = "Offline",
                    onClick = {
                        onDownloadCorridor(route)
                        onClose()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    DownloadTrayIcon(color = EnduPalette.TextPrimary, size = 13.dp)
                }

                RouteDetailOptionButton(
                    label = "Compartir",
                    onClick = { onShareRoute(route) },
                    modifier = Modifier.weight(1f)
                ) {
                    ShareUploadIcon(color = EnduPalette.TextPrimary, size = 13.dp)
                }

                RouteDetailOptionButton(
                    label = if (confirmDelete) "¿Confirmar?" else "Eliminar",
                    tint = EnduPalette.Danger,
                    containerColor = if (confirmDelete) EnduPalette.Danger.copy(alpha = 0.28f) else EnduPalette.Danger.copy(alpha = 0.14f),
                    onClick = {
                        if (confirmDelete) {
                            onDeleteRoute(route)
                            onClose()
                        } else {
                            confirmDelete = true
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    TrashDeleteIcon(color = EnduPalette.Danger, size = 13.dp)
                }
            }
        }
    }
}

@Composable
private fun RouteStatItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            color = EnduPalette.TextMuted,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            color = EnduPalette.TextPrimary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun RouteDetailOptionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    tint: Color = EnduPalette.TextPrimary,
    containerColor: Color = if (highlighted) EnduPalette.Accent.copy(alpha = 0.2f) else EnduPalette.SurfaceHigh,
    icon: @Composable () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .clickable { onClick() }
            .padding(horizontal = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            icon()
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                color = if (highlighted) EnduPalette.Accent else tint,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TrackColorPickerRow(
    selected: TrackColor,
    onSelect: (TrackColor) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(TrackColor.entries) { trackColor ->
            val active = trackColor == selected
            val c = Color(trackColor.rgbHex)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(c)
                    .border(
                        width = if (active) 2.5.dp else 1.dp,
                        color = if (active) Color.White else Color.White.copy(alpha = 0.28f),
                        shape = CircleShape
                    )
                    .clickable { onSelect(trackColor) }
            ) {
                if (active) {
                    Text(
                        text = "✓",
                        color = Color.Black,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

/**
 * Diálogo para editar nombre, color, notas, sentido y visibilidad de un track GPX.
 */
@Composable
fun EditRouteDialog(
    route: GPXRoute,
    onSave: (name: String, color: TrackColor, notes: String, isVisible: Boolean, toggleInvert: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(route.id) { mutableStateOf(route.name) }
    var selectedColor by remember(route.id) { mutableStateOf(route.color) }
    var notes by remember(route.id) { mutableStateOf(route.notes) }
    var isVisible by remember(route.id) { mutableStateOf(route.isVisible) }
    var toggleInvert by remember(route.id) { mutableStateOf(false) }

    val effectiveInverted = if (toggleInvert) !route.isInverted else route.isInverted

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Editar Track GPX",
                    color = EnduPalette.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "✕",
                    color = EnduPalette.TextMuted,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onDismiss() }.padding(4.dp)
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nombre del track") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = EnduPalette.Accent,
                    unfocusedBorderColor = EnduPalette.Outline,
                    focusedTextColor = EnduPalette.TextPrimary,
                    unfocusedTextColor = EnduPalette.TextPrimary,
                    focusedLabelColor = EnduPalette.Accent,
                    unfocusedLabelColor = EnduPalette.TextMuted
                )
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "COLOR · ${selectedColor.label.uppercase()}",
                    color = EnduPalette.TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                TrackColorPickerRow(
                    selected = selectedColor,
                    onSelect = { selectedColor = it }
                )
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notas / descripción (opcional)") },
                placeholder = { Text("Ej. Trialera rápida, fuente en km 22...", fontSize = 12.sp) },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = EnduPalette.Accent,
                    unfocusedBorderColor = EnduPalette.Outline,
                    focusedTextColor = EnduPalette.TextPrimary,
                    unfocusedTextColor = EnduPalette.TextPrimary,
                    focusedLabelColor = EnduPalette.Accent,
                    unfocusedLabelColor = EnduPalette.TextMuted
                )
            )

            // Visibilidad e inversión de sentido
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RouteDetailOptionButton(
                    label = if (isVisible) "Visible en mapa" else "Oculto en mapa",
                    highlighted = isVisible,
                    onClick = { isVisible = !isVisible },
                    modifier = Modifier.weight(1f)
                ) {
                    EyeVisibilityIcon(
                        visible = isVisible,
                        color = if (isVisible) EnduPalette.Accent else EnduPalette.TextMuted,
                        size = 13.dp
                    )
                }

                RouteDetailOptionButton(
                    label = if (effectiveInverted) "Sentido invertido" else "Sentido original",
                    highlighted = effectiveInverted,
                    onClick = { toggleInvert = !toggleInvert },
                    modifier = Modifier.weight(1f)
                ) {
                    InvertRouteArrowsIcon(
                        color = if (effectiveInverted) EnduPalette.Accent else EnduPalette.TextPrimary,
                        size = 13.dp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancelar", color = EnduPalette.TextMuted, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = {
                        onSave(
                            name.trim().ifEmpty { route.name },
                            selectedColor,
                            notes,
                            isVisible,
                            toggleInvert
                        )
                    },
                    enabled = name.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = EnduPalette.Accent),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Guardar", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
