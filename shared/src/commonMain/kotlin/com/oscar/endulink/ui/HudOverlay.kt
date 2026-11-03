package com.oscar.endulink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oscar.endulink.bike.BikeState
import com.oscar.endulink.platform.RiderTelemetry
import com.oscar.endulink.trail.CalculatedRoadRoute
import com.oscar.endulink.trail.GPXRoute
import com.oscar.endulink.trail.TrackProgress
import kotlin.math.roundToInt

@Composable
fun HudOverlay(
    rider: RiderTelemetry,
    trackProgress: TrackProgress?,
    bikeState: BikeState,
    followMode: MapFollowMode,
    isNavigating: Boolean,
    isRecording: Boolean,
    heading: Double,
    searchPin: SearchResultItem?,
    droppedPin: SearchResultItem?,
    intermediateWaypoints: List<SearchResultItem>,
    roadOptions: List<CalculatedRoadRoute>,
    selectedRoadIndex: Int,
    isCalculatingRoute: Boolean,
    statusBannerMessage: String?,
    selectedRoute: GPXRoute?,
    approachRoute: CalculatedRoadRoute?,
    crowFliesActive: Boolean,
    onOpenSearch: () -> Unit,
    onClearDestination: () -> Unit,
    onClearDroppedPin: () -> Unit,
    onSetDroppedPinAsDestination: () -> Unit,
    onAddDroppedPinAsStop: () -> Unit,
    onRemoveIntermediateWaypoint: (Int) -> Unit,
    onOpenAddStopSearch: () -> Unit,
    onDeselectRoute: () -> Unit,
    onSelectRoadAlternative: (Int) -> Unit,
    onRequestStartNavigation: () -> Unit,
    onStopNavigation: () -> Unit,
    onRequestApproach: () -> Unit,
    onClearApproach: () -> Unit,
    onDismissBanner: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFitMap: () -> Unit,
    onToggleFollowMode: () -> Unit,
    onToggleOrientation: () -> Unit,
    onToggleCrowFlies: () -> Unit,
    onToggleRecording: () -> Unit,
    onOpenLayers: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenRoutes: () -> Unit,
    onOpenBikeSheet: () -> Unit,
    onOpenQuickMarker: () -> Unit,
    onSaveDroppedPinAsWaypoint: () -> Unit = {},
    onOpenWaypoints: () -> Unit = {},
    isPickingOnMap: Boolean = false,
    pickingCoordAddress: String? = null,
    onPickOnMap: () -> Unit = {},
    onConfirmPickOnMap: () -> Unit = {},
    onCancelPickOnMap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {

        // ==========================================
        // 1. ZONA SUPERIOR: Buscador + Capas + HUD Telemetría
        // ==========================================
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isPickingOnMap) {
                // Banner indicador de Modo Selección en Mapa
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(EnduPalette.Surface.copy(alpha = 0.96f))
                        .border(1.dp, EnduPalette.Accent.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "📍", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Mueve el mapa para situar el punto",
                        color = EnduPalette.TextPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                // Fila de Buscador + Capas + Mapas Offline
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Barra de Búsqueda estilo píldora MotIOS con botón de fijar en mapa
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(EnduPalette.Surface.copy(alpha = 0.94f))
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onOpenSearch() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "🔍", fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = searchPin?.title ?: "Buscar pueblo, dirección...",
                                color = if (searchPin != null) EnduPalette.TextPrimary else EnduPalette.TextMuted,
                                fontSize = 13.sp,
                                maxLines = 1
                            )
                        }
                        if (searchPin != null) {
                            Text(
                                text = "✕",
                                color = EnduPalette.TextMuted,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { onClearDestination() }
                                    .padding(horizontal = 6.dp)
                            )
                        } else {
                            // Botón de acceso directo para fijar punto en el mapa
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(EnduPalette.SurfaceHigh)
                                    .clickable { onPickOnMap() }
                            ) {
                                Text(text = "🎯", fontSize = 12.sp)
                            }
                        }
                    }

                    // Botón Capas del Mapa (Carreteras, Satélite, Trail)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(EnduPalette.Surface.copy(alpha = 0.94f))
                            .clickable { onOpenLayers() }
                    ) {
                        Text(text = "🗺️", fontSize = 16.sp)
                    }

                    // Botón Mapas Offline
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(EnduPalette.Surface.copy(alpha = 0.94f))
                            .clickable { onOpenOffline() }
                    ) {
                        Text(text = "⬇️", fontSize = 16.sp)
                    }

                    // Botón Ajustes y Actualizaciones
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(EnduPalette.Surface.copy(alpha = 0.94f))
                            .clickable { onOpenSettings() }
                    ) {
                        Text(text = "⚙️", fontSize = 16.sp)
                    }
                }
            }

            // Tira de Telemetría Activa (Durante Navegación)
            if (isNavigating) {
                val activeRoad = roadOptions.getOrNull(selectedRoadIndex) ?: approachRoute
                val remKm = trackProgress?.remainingKm ?: activeRoad?.distanceKm ?: selectedRoute?.totalDistanceKm ?: 0.0

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(EnduPalette.Surface.copy(alpha = 0.95f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TelemetryItem(
                            label = "VEL",
                            value = "${rider.speedKmh.roundToInt()}",
                            unit = "km/h"
                        )

                        TelemetryItem(
                            label = "RESTAN",
                            value = "${(remKm * 10.0).roundToInt() / 10.0}",
                            unit = "km",
                            color = EnduPalette.Success
                        )

                        if (activeRoad != null) {
                            TelemetryItem(
                                label = "TIEMPO",
                                value = "~${activeRoad.expectedMinutes}",
                                unit = "min",
                                color = EnduPalette.Accent
                            )
                        } else {
                            TelemetryItem(
                                label = "ALT",
                                value = "${rider.altitudeM.roundToInt()}",
                                unit = "m"
                            )
                        }
                    }

                    val step = activeRoad?.firstInstruction
                    if (!step.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "↳ $step",
                            color = EnduPalette.Accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }

            // Banner flotante de avisos
            if (statusBannerMessage != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(EnduPalette.Surface.copy(alpha = 0.96f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = statusBannerMessage,
                        color = EnduPalette.TextPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 2
                    )
                    Text(
                        text = "✕",
                        color = EnduPalette.TextMuted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { onDismissBanner() }
                            .padding(4.dp)
                    )
                }
            }
        }

        // ==========================================
        // 2. BOTONES FLOTANTES DERECHA (Estilo 1:1 MotIOS: botones circulares limpios)
        // ==========================================
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Orientación / Brújula (Norte arriba vs Rumbo de avance arriba)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (followMode == MapFollowMode.FOLLOW_HEADING) EnduPalette.Accent.copy(alpha = 0.28f) else EnduPalette.Surface.copy(alpha = 0.92f))
                    .border(
                        width = 1.dp,
                        color = if (followMode == MapFollowMode.FOLLOW_HEADING) EnduPalette.Accent else EnduPalette.Outline,
                        shape = CircleShape
                    )
                    .clickable { onToggleOrientation() }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (followMode == MapFollowMode.FOLLOW_HEADING) "▲" else "N",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        color = if (followMode == MapFollowMode.FOLLOW_HEADING) EnduPalette.Accent else Color(0xFFFF5252)
                    )
                    Text(
                        text = "${heading.roundToInt()}°",
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = EnduPalette.TextMuted
                    )
                }
            }

            // 2. Zoom + y -
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(EnduPalette.Surface.copy(alpha = 0.92f))
                    .border(1.dp, EnduPalette.Outline, RoundedCornerShape(10.dp)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp, 34.dp)
                        .clickable { onZoomIn() }
                ) {
                    Text(text = "➕", fontSize = 12.sp, color = EnduPalette.TextPrimary)
                }
                Box(
                    modifier = Modifier
                        .width(26.dp)
                        .height(1.dp)
                        .background(EnduPalette.Outline)
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp, 34.dp)
                        .clickable { onZoomOut() }
                ) {
                    Text(text = "➖", fontSize = 12.sp, color = EnduPalette.TextPrimary)
                }
            }

            // 3. Ver ruta completa en pantalla (solo si hay ruta activa)
            if (roadOptions.isNotEmpty() || selectedRoute != null) {
                MapCircleButton(
                    icon = "⤢",
                    defaultIconColor = EnduPalette.Accent,
                    onClick = onFitMap
                )
            }

            // 5. Marcador rápido
            MapCircleButton(
                icon = "📍",
                defaultIconColor = Color(0xFF00E5FF),
                onClick = onOpenQuickMarker
            )

            // 6. Centrar en mi ubicación GPS (con auto-zoom y sin emojis bugueados)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (followMode != MapFollowMode.FREE) EnduPalette.Accent else EnduPalette.Surface.copy(alpha = 0.92f))
                    .border(
                        width = 1.dp,
                        color = if (followMode != MapFollowMode.FREE) EnduPalette.Accent else EnduPalette.Outline,
                        shape = CircleShape
                    )
                    .clickable { onToggleFollowMode() }
            ) {
                LocationCrosshairIcon(
                    followMode = followMode,
                    color = if (followMode != MapFollowMode.FREE) Color.White else EnduPalette.TextPrimary
                )
            }
        }

        // ==========================================
        // 3. ZONA INFERIOR: Tarjeta de Track/Destino + Barra de Control
        // ==========================================
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isPickingOnMap) {
                // ----------------------------------------------------
                // Tarjeta de Confirmación de Selección en el Mapa
                // ----------------------------------------------------
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(EnduPalette.Surface.copy(alpha = 0.96f))
                        .border(1.dp, EnduPalette.Accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "🎯", fontSize = 15.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pickingCoordAddress ?: "Ubicación seleccionada",
                                color = EnduPalette.TextPrimary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = "Punto fijado en el centro de la pantalla",
                                color = EnduPalette.TextMuted,
                                fontSize = 10.5.sp
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(EnduPalette.SurfaceHigh)
                                .clickable { onCancelPickOnMap() }
                        ) {
                            Text(text = "Cancelar", color = EnduPalette.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1.5f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(EnduPalette.Accent)
                                .clickable { onConfirmPickOnMap() }
                        ) {
                            Text(text = "✓ Confirmar este punto", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // ----------------------------------------------------
                // Tarjeta de Chincheta Marcada por Pulsación Larga (Dropped Pin)
                // ----------------------------------------------------
                if (droppedPin != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(EnduPalette.Surface.copy(alpha = 0.96f))
                        .border(1.dp, EnduPalette.Accent.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "📍", fontSize = 13.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = droppedPin.title,
                                    color = EnduPalette.TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                            val distStr = droppedPin.distanceKm?.let { " · a ${(it * 10.0).roundToInt() / 10.0} km" } ?: ""
                            Text(
                                text = droppedPin.subtitle + distStr,
                                color = EnduPalette.TextMuted,
                                fontSize = 10.5.sp,
                                maxLines = 1
                            )
                        }

                        Text(
                            text = "✕",
                            color = EnduPalette.TextMuted,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { onClearDroppedPin() }
                                .padding(4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(7.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Si ya hay un destino o track seleccionado, permitir añadir como parada intermedia
                        if (searchPin != null || selectedRoute != null) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(Color(0xFF00D4FF).copy(alpha = 0.22f))
                                    .border(1.dp, Color(0xFF00D4FF), RoundedCornerShape(7.dp))
                                    .clickable { onAddDroppedPinAsStop() }
                                    .padding(horizontal = 6.dp)
                            ) {
                                Text(
                                    text = "➕ Parada",
                                    color = Color(0xFF00D4FF),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Ir / Iniciar ruta hacia este punto
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(EnduPalette.Accent)
                                .clickable { onSetDroppedPinAsDestination() }
                                .padding(horizontal = 6.dp)
                        ) {
                            Text(
                                text = if (searchPin != null) "🏁 Cambiar" else "🚗 Ir",
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Guardar como Waypoint permanente
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(Color(0xFF00E5FF).copy(alpha = 0.22f))
                                .border(1.dp, Color(0xFF00E5FF), RoundedCornerShape(7.dp))
                                .clickable { onSaveDroppedPinAsWaypoint() }
                                .padding(horizontal = 6.dp)
                        ) {
                            Text(
                                text = "📌 Guardar",
                                color = Color(0xFF00E5FF),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // ----------------------------------------------------
            // Tarjeta de Destino Buscado con Paradas y Rutas Alternativas
            // ----------------------------------------------------
            if (searchPin != null) {
                val activeOpt = roadOptions.getOrNull(selectedRoadIndex)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(EnduPalette.Surface.copy(alpha = 0.95f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = searchPin.title,
                                color = EnduPalette.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            if (isCalculatingRoute) {
                                Text(
                                    text = "Calculando cómo llegar…",
                                    color = EnduPalette.TextMuted,
                                    fontSize = 11.sp
                                )
                            } else if (activeOpt != null) {
                                val stopCountText = if (intermediateWaypoints.isNotEmpty()) {
                                    " · ${intermediateWaypoints.size} parada${if (intermediateWaypoints.size > 1) "s" else ""}"
                                } else ""
                                Text(
                                    text = "${activeOpt.expectedMinutes} min · ${(activeOpt.distanceKm * 10.0).roundToInt() / 10.0} km$stopCountText",
                                    color = EnduPalette.Accent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Text(
                                    text = searchPin.subtitle,
                                    color = EnduPalette.TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }

                        // Cerrar destino: quita la ruta y el pin de pantalla
                        Text(
                            text = "✕",
                            color = EnduPalette.TextMuted,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { onClearDestination() }
                                .padding(4.dp)
                        )
                    }

                    // Lista secuencial de paradas intermedias
                    if (intermediateWaypoints.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            intermediateWaypoints.forEachIndexed { idx, wpt ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(EnduPalette.SurfaceHigh.copy(alpha = 0.7f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF00D4FF))
                                    ) {
                                        Text(
                                            text = "${idx + 1}",
                                            color = Color.Black,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = wpt.title,
                                        color = EnduPalette.TextPrimary,
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    wpt.distanceKm?.let { d ->
                                        Text(
                                            text = "${(d * 10.0).roundToInt() / 10.0} km",
                                            color = EnduPalette.TextMuted,
                                            fontSize = 10.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "✕",
                                        color = EnduPalette.TextMuted,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable { onRemoveIntermediateWaypoint(idx) }
                                            .padding(2.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Botón para añadir parada intermedia
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF00D4FF).copy(alpha = 0.18f))
                                .border(1.dp, Color(0xFF00D4FF).copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                .clickable { onOpenAddStopSearch() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "➕", fontSize = 10.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Añadir parada",
                                    color = Color(0xFF00D4FF),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Píldoras de Rutas Alternativas
                        if (roadOptions.size > 1) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                roadOptions.forEachIndexed { idx, opt ->
                                    val isSelected = (idx == selectedRoadIndex)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isSelected) EnduPalette.Accent else EnduPalette.SurfaceHigh)
                                            .clickable { onSelectRoadAlternative(idx) }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "${opt.routeLabel} · ${opt.expectedMinutes} min (${(opt.distanceKm).roundToInt()} km)",
                                            color = if (isSelected) Color.White else EnduPalette.TextPrimary,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Tarjeta informativa de Track GPX Seleccionado (con botón ✕ para deseleccionar)
            if (selectedRoute != null && searchPin == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(EnduPalette.Surface.copy(alpha = 0.95f))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Track: ${selectedRoute.name}",
                            color = EnduPalette.TextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = "${(selectedRoute.totalDistanceKm * 10.0).roundToInt() / 10.0} km · +${selectedRoute.elevationGainM.roundToInt()} m",
                            color = EnduPalette.TextMuted,
                            fontSize = 10.5.sp
                        )
                    }

                    // Botón para deseleccionar track y quitar "Iniciar ruta"
                    if (!isNavigating) {
                        Text(
                            text = "✕ Quitar",
                            color = EnduPalette.Accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { onDeselectRoute() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Barra inferior compacta
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(EnduPalette.Surface.copy(alpha = 0.95f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Botón INICIAR RUTA / PARAR
                val hasTarget = (roadOptions.isNotEmpty() || (selectedRoute?.isVisible == true))
                if (isNavigating || hasTarget) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isNavigating) EnduPalette.Danger else EnduPalette.Success)
                            .clickable {
                                if (isNavigating) onStopNavigation() else onRequestStartNavigation()
                            }
                            .padding(horizontal = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isNavigating) "⏹️" else "🚀",
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isNavigating) "Parar" else "Iniciar ruta",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // 2. Enlace a ruta GPX
                if (selectedRoute != null && searchPin == null) {
                    if (approachRoute != null) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(EnduPalette.Accent.copy(alpha = 0.22f))
                                .clickable { onClearApproach() }
                                .padding(horizontal = 8.dp)
                        ) {
                            Text(
                                text = "✕ Enlace",
                                color = EnduPalette.Accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(EnduPalette.SurfaceHigh)
                                .clickable { onRequestApproach() }
                                .padding(horizontal = 8.dp)
                        ) {
                            Text(
                                text = "↗️ Enlace",
                                color = EnduPalette.TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // 3. REC (Grabación en vivo)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isRecording) EnduPalette.Danger.copy(alpha = 0.22f) else EnduPalette.SurfaceHigh)
                        .clickable { onToggleRecording() }
                        .padding(horizontal = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isRecording) EnduPalette.Danger else EnduPalette.TextMuted)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "REC",
                            color = if (isRecording) EnduPalette.Danger else EnduPalette.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 4. Gestor de Tracks GPX
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(EnduPalette.SurfaceHigh)
                        .clickable { onOpenRoutes() }
                        .padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = "Tracks",
                        color = EnduPalette.Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 5. Gestor de Waypoints
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(EnduPalette.SurfaceHigh)
                        .clickable { onOpenWaypoints() }
                        .padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = "Puntos",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // 5. Conexión Pantalla Moto
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (bikeState == BikeState.SHOWING) EnduPalette.Success.copy(alpha = 0.22f)
                            else EnduPalette.SurfaceHigh
                        )
                        .clickable { onOpenBikeSheet() }
                        .padding(horizontal = 9.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    when (bikeState) {
                                        BikeState.SHOWING -> EnduPalette.Success
                                        BikeState.GREETED -> EnduPalette.AccentBlue
                                        BikeState.LISTENING -> EnduPalette.Warning
                                        BikeState.CLOSED -> EnduPalette.TextMuted
                                    }
                                )
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (bikeState == BikeState.SHOWING) "En moto" else "Moto",
                            color = EnduPalette.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun TelemetryItem(
    label: String,
    value: String,
    unit: String,
    color: Color = EnduPalette.TextPrimary
) {
    Column {
        Text(
            text = label,
            color = EnduPalette.TextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = color,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = unit,
                color = EnduPalette.TextMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 1.dp)
            )
        }
    }
}

@Composable
private fun MapCircleButton(
    icon: String,
    isActive: Boolean = false,
    activeBgColor: Color = EnduPalette.Accent,
    activeIconColor: Color = Color.White,
    defaultBgColor: Color = EnduPalette.Surface.copy(alpha = 0.92f),
    defaultIconColor: Color = EnduPalette.TextPrimary,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (isActive) activeBgColor else defaultBgColor)
            .border(
                width = 1.dp,
                color = if (isActive) activeBgColor else EnduPalette.Outline,
                shape = CircleShape
            )
            .clickable { onClick() }
    ) {
        Text(
            text = icon,
            fontSize = 15.sp,
            color = if (isActive) activeIconColor else defaultIconColor
        )
    }
}

@Composable
private fun LocationCrosshairIcon(
    followMode: MapFollowMode,
    color: Color,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.Canvas(modifier = modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val c = Offset(w / 2f, h / 2f)
        val radius = w * 0.38f

        when (followMode) {
            MapFollowMode.FREE -> {
                // Mira GPS: círculo fino + 4 marcas en los ejes
                val strokeW = 1.8f.dp.toPx()
                val tickLen = 4f.dp.toPx()
                val inLen = 1f.dp.toPx()
                drawCircle(color = color, radius = radius, center = c, style = Stroke(width = strokeW))
                drawLine(color = color, start = Offset(c.x, c.y - radius - tickLen), end = Offset(c.x, c.y - radius + inLen), strokeWidth = strokeW)
                drawLine(color = color, start = Offset(c.x, c.y + radius - inLen), end = Offset(c.x, c.y + radius + tickLen), strokeWidth = strokeW)
                drawLine(color = color, start = Offset(c.x - radius - tickLen, c.y), end = Offset(c.x - radius + inLen, c.y), strokeWidth = strokeW)
                drawLine(color = color, start = Offset(c.x + radius - inLen, c.y), end = Offset(c.x + radius + tickLen, c.y), strokeWidth = strokeW)
            }
            MapFollowMode.FOLLOW -> {
                // Centrado activo: círculo con punto central sólido
                drawCircle(color = color, radius = radius, center = c, style = Stroke(width = 2f.dp.toPx()))
                drawCircle(color = color, radius = radius * 0.5f, center = c)
            }
            MapFollowMode.FOLLOW_HEADING -> {
                // Rumbo activo: flecha de navegación hacia arriba
                val tipY = c.y - radius - 2f.dp.toPx()
                val path = Path().apply {
                    moveTo(c.x, tipY)
                    lineTo(c.x + radius, c.y + radius)
                    lineTo(c.x, c.y + radius * 0.45f)
                    lineTo(c.x - radius, c.y + radius)
                    close()
                }
                drawPath(path = path, color = color)
            }
        }
    }
}
