package com.oscar.endulink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oscar.endulink.bike.BikeState
import com.oscar.endulink.group.GroupConnectionState
import com.oscar.endulink.group.GroupMember
import com.oscar.endulink.group.GroupTftLayout
import com.oscar.endulink.group.RiderStatus
import com.oscar.endulink.group.SharedGroupTrack
import com.oscar.endulink.platform.RiderTelemetry
import com.oscar.endulink.trail.AppDisplaySettings
import com.oscar.endulink.trail.CalculatedRoadRoute
import com.oscar.endulink.trail.GPXRoute
import com.oscar.endulink.trail.HudButtonSlot
import com.oscar.endulink.trail.HudButtonsLayout
import com.oscar.endulink.trail.RouteTerrainProfile
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
    recordingStats: Triple<Double, Double, Long> = Triple(0.0, 0.0, 0L),
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
    onOpenElevation: () -> Unit = {},
    onShareSelectedRoute: () -> Unit = {},
    onOpenSelectedRouteOptions: () -> Unit = {},
    isPickingOnMap: Boolean = false,
    isSearchingForStop: Boolean = false,
    mapPickTarget: MapPickTarget = MapPickTarget.DESTINATION,
    pickingCoordAddress: String? = null,
    selectedRouteProfile: RouteTerrainProfile = RouteTerrainProfile.ROAD_FAST,
    onSelectRouteProfile: (RouteTerrainProfile) -> Unit = {},
    onSaveCalculatedRouteAsTrack: () -> Unit = {},
    onOpenRouteGenerator: () -> Unit = {},
    hasPendingCrashLog: Boolean = false,
    onOpenBugReport: () -> Unit = {},
    onPickOnMap: () -> Unit = {},
    onConfirmPickOnMap: () -> Unit = {},
    onConfirmPickAsStop: () -> Unit = {},
    onCancelPickOnMap: () -> Unit = {},
    groupRoomCode: String? = null,
    groupMembers: List<GroupMember> = emptyList(),
    groupConnectionState: GroupConnectionState = GroupConnectionState.DISCONNECTED,
    groupSharedTrack: SharedGroupTrack? = null,
    onOpenGroupSheet: () -> Unit = {},
    onSelectAlertMember: (GroupMember) -> Unit = {},
    onNavigateToAlertMember: (GroupMember) -> Unit = {},
    onImportSharedTrack: () -> Unit = {},
    onDismissSharedTrack: () -> Unit = {},
    isOffGpxTrack: Boolean = false,
    showGpxOffTrackWarning: Boolean = true,
    onDisableGpxOffTrackWarning: () -> Unit = {},
    displaySettings: AppDisplaySettings = AppDisplaySettings(),
    hasUpdateAvailable: Boolean = false,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        val isTablet = maxWidth >= 600.dp
        val isCompactHeight = maxHeight < 500.dp
        val fontScale = displaySettings.hudFontScale.coerceIn(0.9f, 1.8f)
        val cardFontScale = fontScale.coerceAtMost(1.45f)
        val bannerFontScale = fontScale.coerceAtMost(1.5f)
        val overlayMaxWidth = if (isTablet) 680.dp else Dp.Unspecified
        val sideBtnSize = if (isTablet) 44.dp else 38.dp
        val zoomBtnHeight = if (isTablet) 38.dp else 34.dp
        val sideBtnSpacing = if (isCompactHeight) 5.dp else 8.dp
        val sideBottomPadding = when {
            isCompactHeight -> 0.dp
            isTablet -> 20.dp
            else -> 60.dp
        }

        // ==========================================
        // 1. ZONA SUPERIOR: Buscador + Capas + HUD Telemetría
        // ==========================================
        HudTopSection(
            rider = rider,
            trackProgress = trackProgress,
            isNavigating = isNavigating,
            isRecording = isRecording,
            recordingStats = recordingStats,
            searchPin = searchPin,
            roadOptions = roadOptions,
            selectedRoadIndex = selectedRoadIndex,
            statusBannerMessage = statusBannerMessage,
            selectedRoute = selectedRoute,
            approachRoute = approachRoute,
            isPickingOnMap = isPickingOnMap,
            groupMembers = groupMembers,
            groupSharedTrack = groupSharedTrack,
            isOffGpxTrack = isOffGpxTrack,
            showGpxOffTrackWarning = showGpxOffTrackWarning,
            displaySettings = displaySettings,
            hasUpdateAvailable = hasUpdateAvailable,
            fontScale = fontScale,
            cardFontScale = cardFontScale,
            bannerFontScale = bannerFontScale,
            onOpenSearch = onOpenSearch,
            onClearDestination = onClearDestination,
            onPickOnMap = onPickOnMap,
            onOpenLayers = onOpenLayers,
            onOpenOffline = onOpenOffline,
            onOpenSettings = onOpenSettings,
            onDismissBanner = onDismissBanner,
            onRequestApproach = onRequestApproach,
            onDisableGpxOffTrackWarning = onDisableGpxOffTrackWarning,
            onSelectAlertMember = onSelectAlertMember,
            onNavigateToAlertMember = onNavigateToAlertMember,
            onImportSharedTrack = onImportSharedTrack,
            onDismissSharedTrack = onDismissSharedTrack,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = overlayMaxWidth)
                .fillMaxWidth()
        )

        // ==========================================
        // 2. COLUMNAS LATERALES DINÁMICAS (IZQUIERDA Y DERECHA SEGÚN GUÍAS DE AJUSTES)
        // ==========================================
        val hudLayout = displaySettings.hudLayout.normalized()
        val showFit = roadOptions.isNotEmpty() || selectedRoute != null
        val bottomClearance = if (isCompactHeight) 0.dp else sideBottomPadding

        HudSideControlsColumn(
            slot = HudButtonSlot.LEFT,
            layout = hudLayout,
            followMode = followMode,
            isRecording = isRecording,
            bikeState = bikeState,
            groupRoomCode = groupRoomCode,
            groupMembers = groupMembers,
            hasPendingCrashLog = hasPendingCrashLog,
            isTablet = isTablet,
            sideBtnSize = sideBtnSize,
            zoomBtnHeight = zoomBtnHeight,
            sideBtnSpacing = sideBtnSpacing,
            showFitRoute = showFit,
            onToggleOrientation = onToggleOrientation,
            onZoomIn = onZoomIn,
            onZoomOut = onZoomOut,
            onFitMap = onFitMap,
            onToggleRecording = onToggleRecording,
            onOpenRoutes = onOpenRoutes,
            onOpenRouteGenerator = onOpenRouteGenerator,
            onOpenQuickMarker = onOpenQuickMarker,
            onOpenWaypoints = onOpenWaypoints,
            onOpenGroupSheet = onOpenGroupSheet,
            onOpenBikeSheet = onOpenBikeSheet,
            onOpenBugReport = onOpenBugReport,
            onToggleFollowMode = onToggleFollowMode,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(bottom = bottomClearance)
        )

        HudSideControlsColumn(
            slot = HudButtonSlot.RIGHT,
            layout = hudLayout,
            followMode = followMode,
            isRecording = isRecording,
            bikeState = bikeState,
            groupRoomCode = groupRoomCode,
            groupMembers = groupMembers,
            hasPendingCrashLog = hasPendingCrashLog,
            isTablet = isTablet,
            sideBtnSize = sideBtnSize,
            zoomBtnHeight = zoomBtnHeight,
            sideBtnSpacing = sideBtnSpacing,
            showFitRoute = showFit,
            onToggleOrientation = onToggleOrientation,
            onZoomIn = onZoomIn,
            onZoomOut = onZoomOut,
            onFitMap = onFitMap,
            onToggleRecording = onToggleRecording,
            onOpenRoutes = onOpenRoutes,
            onOpenRouteGenerator = onOpenRouteGenerator,
            onOpenQuickMarker = onOpenQuickMarker,
            onOpenWaypoints = onOpenWaypoints,
            onOpenGroupSheet = onOpenGroupSheet,
            onOpenBikeSheet = onOpenBikeSheet,
            onOpenBugReport = onOpenBugReport,
            onToggleFollowMode = onToggleFollowMode,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(bottom = bottomClearance)
        )

        // ==========================================
        // 3. ZONA INFERIOR: Tarjeta de Track/Destino + Barra de Control
        // ==========================================
        HudBottomSection(
            trackProgress = trackProgress,
            bikeState = bikeState,
            isNavigating = isNavigating,
            isRecording = isRecording,
            searchPin = searchPin,
            droppedPin = droppedPin,
            intermediateWaypoints = intermediateWaypoints,
            roadOptions = roadOptions,
            selectedRoadIndex = selectedRoadIndex,
            isCalculatingRoute = isCalculatingRoute,
            selectedRoute = selectedRoute,
            approachRoute = approachRoute,
            isPickingOnMap = isPickingOnMap,
            isSearchingForStop = isSearchingForStop,
            mapPickTarget = mapPickTarget,
            pickingCoordAddress = pickingCoordAddress,
            selectedRouteProfile = selectedRouteProfile,
            hudLayout = hudLayout,
            onSelectRouteProfile = onSelectRouteProfile,
            onSaveCalculatedRouteAsTrack = onSaveCalculatedRouteAsTrack,
            onOpenRouteGenerator = onOpenRouteGenerator,
            groupRoomCode = groupRoomCode,
            groupMembers = groupMembers,
            cardFontScale = cardFontScale,
            onClearDestination = onClearDestination,
            onClearDroppedPin = onClearDroppedPin,
            onSetDroppedPinAsDestination = onSetDroppedPinAsDestination,
            onAddDroppedPinAsStop = onAddDroppedPinAsStop,
            onRemoveIntermediateWaypoint = onRemoveIntermediateWaypoint,
            onOpenAddStopSearch = onOpenAddStopSearch,
            onDeselectRoute = onDeselectRoute,
            onSelectRoadAlternative = onSelectRoadAlternative,
            onRequestStartNavigation = onRequestStartNavigation,
            onStopNavigation = onStopNavigation,
            onRequestApproach = onRequestApproach,
            onClearApproach = onClearApproach,
            onToggleRecording = onToggleRecording,
            onOpenRoutes = onOpenRoutes,
            onOpenBikeSheet = onOpenBikeSheet,
            onSaveDroppedPinAsWaypoint = onSaveDroppedPinAsWaypoint,
            onOpenQuickMarker = onOpenQuickMarker,
            onOpenWaypoints = onOpenWaypoints,
            hasPendingCrashLog = hasPendingCrashLog,
            onOpenBugReport = onOpenBugReport,
            onOpenElevation = onOpenElevation,
            onShareSelectedRoute = onShareSelectedRoute,
            onOpenSelectedRouteOptions = onOpenSelectedRouteOptions,
            onConfirmPickOnMap = onConfirmPickOnMap,
            onConfirmPickAsStop = onConfirmPickAsStop,
            onCancelPickOnMap = onCancelPickOnMap,
            onOpenGroupSheet = onOpenGroupSheet,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = overlayMaxWidth)
                .fillMaxWidth()
        )
    }
}

@Composable
private fun HudTopSection(
    rider: RiderTelemetry,
    trackProgress: TrackProgress?,
    isNavigating: Boolean,
    isRecording: Boolean,
    recordingStats: Triple<Double, Double, Long>,
    searchPin: SearchResultItem?,
    roadOptions: List<CalculatedRoadRoute>,
    selectedRoadIndex: Int,
    statusBannerMessage: String?,
    selectedRoute: GPXRoute?,
    approachRoute: CalculatedRoadRoute?,
    isPickingOnMap: Boolean,
    groupMembers: List<GroupMember>,
    groupSharedTrack: SharedGroupTrack?,
    isOffGpxTrack: Boolean,
    showGpxOffTrackWarning: Boolean,
    displaySettings: AppDisplaySettings,
    hasUpdateAvailable: Boolean,
    fontScale: Float,
    cardFontScale: Float,
    bannerFontScale: Float,
    onOpenSearch: () -> Unit,
    onClearDestination: () -> Unit,
    onPickOnMap: () -> Unit,
    onOpenLayers: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissBanner: () -> Unit,
    onRequestApproach: () -> Unit,
    onDisableGpxOffTrackWarning: () -> Unit,
    onSelectAlertMember: (GroupMember) -> Unit,
    onNavigateToAlertMember: (GroupMember) -> Unit,
    onImportSharedTrack: () -> Unit,
    onDismissSharedTrack: () -> Unit,
    modifier: Modifier = Modifier
) {
        Column(
            modifier = modifier,
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
                    MapPinTargetIcon(color = EnduPalette.Accent, size = 16.dp)
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
                            SearchLensIcon(color = EnduPalette.TextSecondary, size = 15.dp)
                            Spacer(modifier = Modifier.width(7.dp))
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
                                MapPinTargetIcon(color = EnduPalette.Accent, size = 15.dp)
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
                        LayersIcon(color = EnduPalette.TextPrimary, size = 19.dp)
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
                        DownloadTrayIcon(color = EnduPalette.TextPrimary, size = 19.dp)
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
                        SettingsGearIcon(color = EnduPalette.TextPrimary, size = 19.dp)
                        if (hasUpdateAvailable) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(EnduPalette.Success)
                            )
                        }
                    }
                }
            }

            // Tira de Telemetría (Durante Navegación o siempre visible según ajuste)
            if (isNavigating || displaySettings.alwaysShowTelemetryBar) {
                val activeRoad = roadOptions.getOrNull(selectedRoadIndex) ?: approachRoute
                val remKm = trackProgress?.remainingKm ?: activeRoad?.distanceKm ?: selectedRoute?.totalDistanceKm

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
                            unit = "km/h",
                            fontScale = fontScale
                        )

                        if (isNavigating || remKm != null) {
                            val showKm = remKm ?: 0.0
                            TelemetryItem(
                                label = "RESTAN",
                                value = "${(showKm * 10.0).roundToInt() / 10.0}",
                                unit = "km",
                                color = EnduPalette.Success,
                                fontScale = fontScale
                            )
                        }

                        if (isNavigating && activeRoad != null) {
                            val showKm = remKm ?: activeRoad.distanceKm
                            val ratio = (showKm / activeRoad.distanceKm.coerceAtLeast(0.1)).coerceIn(0.0, 1.0)
                            val remMinutes = if (showKm <= 0.05) 0 else maxOf(1, (activeRoad.expectedMinutes * ratio).roundToInt())
                            TelemetryItem(
                                label = "TIEMPO",
                                value = "~$remMinutes",
                                unit = "min",
                                color = EnduPalette.Accent,
                                fontScale = fontScale
                            )
                        } else {
                            TelemetryItem(
                                label = "ALT",
                                value = "${rider.altitudeM.roundToInt()}",
                                unit = "m",
                                fontScale = fontScale
                            )
                        }
                    }

                    if (isNavigating) {
                        val step = activeRoad?.currentInstruction(rider.coordinate)
                        if (!step.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "↳ $step",
                                color = EnduPalette.Accent,
                                fontSize = (11f * bannerFontScale).sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Banner en vivo durante Grabación REC
            if (isRecording) {
                val recKm = (recordingStats.first * 100.0).roundToInt() / 100.0
                val recElev = recordingStats.second.roundToInt()
                val totalSec = recordingStats.third
                val hrs = totalSec / 3600
                val mins = (totalSec % 3600) / 60
                val secs = totalSec % 60
                val timeStr = if (hrs > 0) {
                    "${hrs}:${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
                } else {
                    "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2B1216).copy(alpha = 0.94f))
                        .border(1.dp, EnduPalette.Danger.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(EnduPalette.Danger)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "REC EN VIVO",
                            color = EnduPalette.Danger,
                            fontSize = (11f * cardFontScale).sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Text(
                        text = "$recKm km · +$recElev m · $timeStr",
                        color = EnduPalette.TextPrimary,
                        fontSize = (12f * bannerFontScale).sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Banner flotante de avisos
            if (statusBannerMessage != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(EnduPalette.Surface)
                        .border(1.dp, EnduPalette.Outline, RoundedCornerShape(8.dp))
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

            // Advertencia configurable de salida del track GPX activo (Móvil)
            if (isOffGpxTrack && showGpxOffTrackWarning && selectedRoute != null && approachRoute == null && roadOptions.isEmpty() && trackProgress != null) {
                val offDist = GroupTftLayout.formatCompactDistance(trackProgress.offTrackMeters)
                val dirs = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
                val cardinal = dirs[((trackProgress.bearingToNearestDeg + 22.5) / 45.0).toInt() % 8]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color(0xFF3E220D).copy(alpha = 0.96f))
                        .border(1.5.dp, EnduPalette.Warning, RoundedCornerShape(9.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WarningTriangleIcon(color = EnduPalette.Warning, size = 13.dp)
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "TE HAS SALIDO DEL TRACK GPX",
                                color = EnduPalette.Warning,
                                fontSize = (11.5f * cardFontScale).sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "A $offDist · Línea recta rumbo $cardinal al punto más cercano",
                            color = Color.White,
                            fontSize = (10.5f * cardFontScale).sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(EnduPalette.Warning)
                                .clickable { onRequestApproach() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Enlace", color = Color.Black, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(EnduPalette.SurfaceHigh)
                                .clickable { onDisableGpxOffTrackWarning() }
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Text("Silenciar", color = EnduPalette.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Alerta rápida de compañero de grupo (SOS / Avería / Esperadme)
            val topAlertMember = groupMembers
                .filter { it.status.isAlert }
                .maxByOrNull { it.status.alertPriority }
            if (topAlertMember != null) {
                val (alertColor, alertBgColor) = when (topAlertMember.status) {
                    RiderStatus.SOS -> EnduPalette.Danger to Color(0xFF451218)
                    RiderStatus.MECHANICAL -> EnduPalette.Accent to Color(0xFF3E220D)
                    else -> EnduPalette.Warning to Color(0xFF3A2D0B)
                }
                val distTxt = rider.coordinate?.let {
                    " · a ${GroupTftLayout.formatCompactDistance(it.distanceTo(topAlertMember.coordinate))}"
                } ?: ""
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(alertBgColor)
                        .border(1.5.dp, alertColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${topAlertMember.status.shortLabel.uppercase()}: ${topAlertMember.name}$distTxt",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (topAlertMember.statusMessage.isNotBlank()) {
                            Text(
                                text = topAlertMember.statusMessage,
                                color = EnduPalette.TextPrimary,
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(EnduPalette.Surface)
                                .clickable { onSelectAlertMember(topAlertMember) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Ver", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(alertColor)
                                .clickable { onNavigateToAlertMember(topAlertMember) }
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Text("Ir", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Notificación de track GPX o dirección recibida del grupo
            if (groupSharedTrack != null) {
                val prefix = if (groupSharedTrack.isDestination) "Dirección grupo" else "Track grupo"
                val actionLabel = if (groupSharedTrack.isDestination) "Ir ahí" else "Cargar"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(EnduPalette.Surface)
                        .border(1.dp, EnduPalette.AccentBlue, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$prefix (${groupSharedTrack.senderName}): ${groupSharedTrack.name}",
                        color = EnduPalette.TextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(EnduPalette.AccentBlue)
                            .clickable { onImportSharedTrack() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(actionLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "✕",
                        color = EnduPalette.TextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { onDismissSharedTrack() }
                            .padding(4.dp)
                    )
                }
            }
        }
}

@Composable
private fun HudSideControlsColumn(
    slot: HudButtonSlot,
    layout: HudButtonsLayout,
    followMode: MapFollowMode,
    isRecording: Boolean,
    bikeState: BikeState,
    groupRoomCode: String?,
    groupMembers: List<GroupMember>,
    hasPendingCrashLog: Boolean,
    isTablet: Boolean,
    sideBtnSize: Dp,
    zoomBtnHeight: Dp,
    sideBtnSpacing: Dp,
    showFitRoute: Boolean,
    onToggleOrientation: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFitMap: () -> Unit,
    onToggleRecording: () -> Unit,
    onOpenRoutes: () -> Unit,
    onOpenRouteGenerator: () -> Unit,
    onOpenQuickMarker: () -> Unit,
    onOpenWaypoints: () -> Unit,
    onOpenGroupSheet: () -> Unit,
    onOpenBikeSheet: () -> Unit,
    onOpenBugReport: () -> Unit,
    onToggleFollowMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasItems = (layout.compassSlot == slot) ||
        (layout.zoomSlot == slot) ||
        (showFitRoute && layout.gpsSide == slot) ||
        (layout.recSlot == slot) ||
        (layout.routesSlot == slot) ||
        (layout.routeGeneratorSlot == slot) ||
        (layout.quickPinSlot == slot) ||
        (layout.waypointsSlot == slot) ||
        (layout.groupSlot == slot) ||
        (layout.bikeSlot == slot) ||
        (layout.bugReportSlot == slot) ||
        (layout.gpsSide == slot)
    if (!hasItems) return

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(sideBtnSpacing),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (layout.compassSlot == slot) {
            MapCircleButton(
                isActive = followMode == MapFollowMode.FOLLOW_HEADING,
                activeBgColor = EnduPalette.Accent.copy(alpha = 0.28f),
                buttonSize = sideBtnSize,
                onClick = onToggleOrientation
            ) {
                CompassNorthIcon(
                    isHeadingMode = (followMode == MapFollowMode.FOLLOW_HEADING),
                    color = if (followMode == MapFollowMode.FOLLOW_HEADING) EnduPalette.Accent else Color(0xFFFF5252),
                    size = if (isTablet) 17.dp else 15.dp
                )
            }
        }

        if (layout.zoomSlot == slot) {
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
                        .size(sideBtnSize, zoomBtnHeight)
                        .clickable { onZoomIn() }
                ) {
                    ZoomPlusIcon(color = EnduPalette.TextPrimary)
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
                        .size(sideBtnSize, zoomBtnHeight)
                        .clickable { onZoomOut() }
                ) {
                    ZoomMinusIcon(color = EnduPalette.TextPrimary)
                }
            }
        }

        if (showFitRoute && layout.gpsSide == slot) {
            MapCircleButton(buttonSize = sideBtnSize, onClick = onFitMap) {
                FitRouteBoundsIcon(color = EnduPalette.Accent)
            }
        }

        if (layout.recSlot == slot) {
            HudSideRecButton(
                isRecording = isRecording,
                buttonSize = sideBtnSize,
                onClick = onToggleRecording
            )
        }

        if (layout.routesSlot == slot) {
            MapCircleButton(buttonSize = sideBtnSize, onClick = onOpenRoutes) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RoutePathIcon(color = EnduPalette.Accent, size = 14.dp)
                    Text("Tracks", color = EnduPalette.TextPrimary, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (layout.routeGeneratorSlot == slot) {
            MapCircleButton(buttonSize = sideBtnSize, onClick = onOpenRouteGenerator) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LoopRouteIcon(color = EnduPalette.Accent, size = 14.dp)
                    Text("Crear", color = EnduPalette.TextPrimary, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (layout.quickPinSlot == slot) {
            MapCircleButton(buttonSize = sideBtnSize, onClick = onOpenQuickMarker) {
                QuickPinIcon(color = Color(0xFF00E5FF))
            }
        }

        if (layout.waypointsSlot == slot) {
            MapCircleButton(buttonSize = sideBtnSize, onClick = onOpenWaypoints) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ListLinesIcon(color = Color(0xFF00E5FF), size = 13.dp)
                    Text("Puntos", color = EnduPalette.TextPrimary, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (layout.groupSlot == slot) {
            val isGroupActive = groupRoomCode != null
            val hasAlert = groupMembers.any { it.status.isAlert }
            val tint = when {
                hasAlert -> EnduPalette.Danger
                isGroupActive -> EnduPalette.Success
                else -> EnduPalette.TextPrimary
            }
            MapCircleButton(
                isActive = isGroupActive || hasAlert,
                activeBgColor = tint.copy(alpha = 0.24f),
                buttonSize = sideBtnSize,
                onClick = onOpenGroupSheet
            ) {
                GroupRidersIcon(color = tint, size = 16.dp)
            }
        }

        if (layout.bikeSlot == slot) {
            val tint = when (bikeState) {
                BikeState.SHOWING -> EnduPalette.Success
                BikeState.GREETED -> EnduPalette.AccentBlue
                BikeState.LISTENING -> EnduPalette.Warning
                BikeState.CLOSED -> EnduPalette.TextSecondary
            }
            MapCircleButton(
                isActive = bikeState == BikeState.SHOWING,
                activeBgColor = EnduPalette.Success.copy(alpha = 0.24f),
                buttonSize = sideBtnSize,
                onClick = onOpenBikeSheet
            ) {
                MotorcycleSilhouetteIcon(color = tint, size = 16.dp)
            }
        }

        if (layout.bugReportSlot == slot) {
            Box(contentAlignment = Alignment.Center) {
                MapCircleButton(
                    isActive = hasPendingCrashLog,
                    activeBgColor = EnduPalette.Danger.copy(alpha = 0.24f),
                    buttonSize = sideBtnSize,
                    onClick = onOpenBugReport
                ) {
                    BugReportIcon(
                        color = if (hasPendingCrashLog) EnduPalette.Danger else EnduPalette.TextPrimary,
                        size = 16.dp
                    )
                }
                if (hasPendingCrashLog) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(EnduPalette.Danger)
                    )
                }
            }
        }

        if (layout.gpsSide == slot) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(sideBtnSize)
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
    }
}

@Composable
private fun HudSideRecButton(
    isRecording: Boolean,
    buttonSize: Dp,
    onClick: () -> Unit
) {
    val bg = if (isRecording) Color(0xFF3E1216) else EnduPalette.Surface.copy(alpha = 0.92f)
    val borderColor = if (isRecording) EnduPalette.Danger else EnduPalette.Outline
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(bg)
            .border(width = 1.2.dp, color = borderColor, shape = CircleShape)
            .clickable { onClick() }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (isRecording) 9.dp else 7.dp)
                    .clip(CircleShape)
                    .background(EnduPalette.Danger)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "REC",
                color = if (isRecording) EnduPalette.Danger else EnduPalette.TextPrimary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 9.sp
            )
        }
    }
}

@Composable
private fun HudBottomSection(
    trackProgress: TrackProgress?,
    bikeState: BikeState,
    isNavigating: Boolean,
    isRecording: Boolean,
    searchPin: SearchResultItem?,
    droppedPin: SearchResultItem?,
    intermediateWaypoints: List<SearchResultItem>,
    roadOptions: List<CalculatedRoadRoute>,
    selectedRoadIndex: Int,
    isCalculatingRoute: Boolean,
    selectedRoute: GPXRoute?,
    approachRoute: CalculatedRoadRoute?,
    isPickingOnMap: Boolean,
    isSearchingForStop: Boolean,
    mapPickTarget: MapPickTarget,
    pickingCoordAddress: String?,
    selectedRouteProfile: RouteTerrainProfile,
    hudLayout: HudButtonsLayout,
    onSelectRouteProfile: (RouteTerrainProfile) -> Unit,
    onSaveCalculatedRouteAsTrack: () -> Unit,
    onOpenRouteGenerator: () -> Unit,
    groupRoomCode: String?,
    groupMembers: List<GroupMember>,
    cardFontScale: Float,
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
    onToggleRecording: () -> Unit,
    onOpenRoutes: () -> Unit,
    onOpenBikeSheet: () -> Unit,
    onSaveDroppedPinAsWaypoint: () -> Unit,
    onOpenQuickMarker: () -> Unit,
    onOpenWaypoints: () -> Unit,
    hasPendingCrashLog: Boolean,
    onOpenBugReport: () -> Unit,
    onOpenElevation: () -> Unit,
    onShareSelectedRoute: () -> Unit,
    onOpenSelectedRouteOptions: () -> Unit,
    onConfirmPickOnMap: () -> Unit,
    onConfirmPickAsStop: () -> Unit,
    onCancelPickOnMap: () -> Unit,
    onOpenGroupSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
        Column(
            modifier = modifier,
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
                        MapPinTargetIcon(color = EnduPalette.Accent, size = 16.dp)
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
                                text = when {
                                    mapPickTarget == MapPickTarget.GENERATOR_ORIGIN -> "Origen (Punto A) fijado en el centro"
                                    mapPickTarget == MapPickTarget.GENERATOR_DESTINATION -> "Destino (Punto B) fijado en el centro"
                                    mapPickTarget == MapPickTarget.BUG_REPORT_LOCATION -> "Punto del fallo fijado en el centro"
                                    isSearchingForStop -> "Parada intermedia fijada en el centro"
                                    else -> "Punto fijado en el centro de la pantalla"
                                },
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

                        val isGeneratorPick = mapPickTarget == MapPickTarget.GENERATOR_ORIGIN ||
                            mapPickTarget == MapPickTarget.GENERATOR_DESTINATION ||
                            mapPickTarget == MapPickTarget.BUG_REPORT_LOCATION
                        val hasActiveRoute = (searchPin != null || selectedRoute != null)
                        if (isSearchingForStop) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .weight(1.5f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF00D4FF))
                                    .clickable { onConfirmPickAsStop() }
                            ) {
                                CheckMarkIcon(color = Color.Black, size = 14.dp)
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Añadir parada",
                                    color = Color.Black,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else if (hasActiveRoute && !isGeneratorPick) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF00D4FF).copy(alpha = 0.22f))
                                    .border(1.dp, Color(0xFF00D4FF), RoundedCornerShape(8.dp))
                                    .clickable { onConfirmPickAsStop() }
                            ) {
                                PlusAddIcon(color = Color(0xFF00D4FF), size = 12.dp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Parada",
                                    color = Color(0xFF00D4FF),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(EnduPalette.Accent)
                                    .clickable { onConfirmPickOnMap() }
                            ) {
                                FlagDestinationIcon(color = Color.White, size = 12.dp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Destino",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .weight(1.5f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(EnduPalette.Accent)
                                    .clickable { onConfirmPickOnMap() }
                            ) {
                                CheckMarkIcon(color = Color.White, size = 14.dp)
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Confirmar punto",
                                    color = Color.White,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            } else {
                // ----------------------------------------------------
                // Tarjeta de Chincheta Marcada por Pulsación Larga (Dropped Pin)
                // ----------------------------------------------------
                if (droppedPin != null) {
                    DroppedPinCard(
                        droppedPin = droppedPin,
                        hasSearchOrRoute = (searchPin != null || selectedRoute != null),
                        hasSearchPin = (searchPin != null),
                        selectedProfile = selectedRouteProfile,
                        onSelectProfile = onSelectRouteProfile,
                        onClearDroppedPin = onClearDroppedPin,
                        onAddDroppedPinAsStop = onAddDroppedPinAsStop,
                        onSetDroppedPinAsDestination = onSetDroppedPinAsDestination,
                        onSaveDroppedPinAsWaypoint = onSaveDroppedPinAsWaypoint
                    )
                }

                // ----------------------------------------------------
                // Tarjeta de Destino Buscado / Ruta Generada con Terreno, Paradas y Alternativas
                // ----------------------------------------------------
                if (searchPin != null) {
                    DestinationRouteCard(
                        searchPin = searchPin,
                        intermediateWaypoints = intermediateWaypoints,
                        roadOptions = roadOptions,
                        selectedRoadIndex = selectedRoadIndex,
                        isCalculatingRoute = isCalculatingRoute,
                        selectedProfile = selectedRouteProfile,
                        cardFontScale = cardFontScale,
                        onSelectProfile = onSelectRouteProfile,
                        onClearDestination = onClearDestination,
                        onRemoveIntermediateWaypoint = onRemoveIntermediateWaypoint,
                        onOpenAddStopSearch = onOpenAddStopSearch,
                        onSaveAsTrack = onSaveCalculatedRouteAsTrack,
                        onSelectRoadAlternative = onSelectRoadAlternative
                    )
                }

            // Tarjeta informativa de Track GPX Seleccionado (con paradas y botón ✕ para deseleccionar)
            if (selectedRoute != null && searchPin == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(EnduPalette.Surface.copy(alpha = 0.95f))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onOpenSelectedRouteOptions() }
                                .padding(vertical = 2.dp, horizontal = 2.dp)
                        ) {
                            Text(
                                text = "Track: ${selectedRoute.name}",
                                color = EnduPalette.TextPrimary,
                                fontSize = (12.5f * cardFontScale).sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val gpxRemText = if (approachRoute == null && trackProgress != null) {
                                " · Restan ${(trackProgress.remainingKm * 10.0).roundToInt() / 10.0} km"
                            } else {
                                ""
                            }
                            Text(
                                text = "${(selectedRoute.totalDistanceKm * 10.0).roundToInt() / 10.0} km · +${selectedRoute.elevationGainM.roundToInt()} m$gpxRemText",
                                color = EnduPalette.TextMuted,
                                fontSize = (10.5f * cardFontScale).sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Botón para abrir opciones / editar el track activo
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(EnduPalette.SurfaceHigh)
                                    .clickable { onOpenSelectedRouteOptions() }
                                    .padding(horizontal = 7.dp, vertical = 4.dp)
                            ) {
                                EditPencilIcon(
                                    color = EnduPalette.TextPrimary,
                                    size = 13.dp
                                )
                            }

                            Spacer(modifier = Modifier.width(5.dp))

                            // Botón para compartir / exportar el archivo .GPX
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(EnduPalette.SurfaceHigh)
                                    .clickable { onShareSelectedRoute() }
                                    .padding(horizontal = 7.dp, vertical = 4.dp)
                            ) {
                                ShareUploadIcon(
                                    color = EnduPalette.TextPrimary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(5.dp))

                            // Botón para ver el perfil de altitud del track GPX
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(EnduPalette.SurfaceHigh)
                                    .clickable { onOpenElevation() }
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                ElevationChartIcon(
                                    color = EnduPalette.TextPrimary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Perfil",
                                    color = EnduPalette.TextPrimary,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Botón para añadir parada intermedia hacia el track
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF00D4FF).copy(alpha = 0.18f))
                                    .border(1.dp, Color(0xFF00D4FF).copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                    .clickable { onOpenAddStopSearch() }
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                PlusAddIcon(color = Color(0xFF00D4FF), size = 10.dp)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Parada",
                                    color = Color(0xFF00D4FF),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Botón para deseleccionar track y quitar "Iniciar ruta"
                            if (!isNavigating) {
                                Text(
                                    text = "✕ Quitar",
                                    color = EnduPalette.Accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .clickable { onDeselectRoute() }
                                        .padding(horizontal = 4.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    // Lista de paradas intermedias hacia el track
                    if (intermediateWaypoints.isNotEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            intermediateWaypoints.forEachIndexed { idx, wpt ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(EnduPalette.SurfaceHigh.copy(alpha = 0.7f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(15.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF00D4FF))
                                    ) {
                                        Text(
                                            text = "${idx + 1}",
                                            color = Color.Black,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = wpt.title,
                                        color = EnduPalette.TextPrimary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "✕",
                                        color = EnduPalette.TextMuted,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable { onRemoveIntermediateWaypoint(idx) }
                                            .padding(2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Barra inferior configurable según guías de Ajustes
            HudBottomBar(
                layout = hudLayout,
                bikeState = bikeState,
                isNavigating = isNavigating,
                isRecording = isRecording,
                searchPin = searchPin,
                roadOptions = roadOptions,
                selectedRoute = selectedRoute,
                approachRoute = approachRoute,
                groupRoomCode = groupRoomCode,
                groupMembers = groupMembers,
                onRequestStartNavigation = onRequestStartNavigation,
                onStopNavigation = onStopNavigation,
                onRequestApproach = onRequestApproach,
                onClearApproach = onClearApproach,
                onToggleRecording = onToggleRecording,
                onOpenRoutes = onOpenRoutes,
                onOpenRouteGenerator = onOpenRouteGenerator,
                onOpenQuickMarker = onOpenQuickMarker,
                onOpenWaypoints = onOpenWaypoints,
                onOpenGroupSheet = onOpenGroupSheet,
                onOpenBikeSheet = onOpenBikeSheet,
                hasPendingCrashLog = hasPendingCrashLog,
                onOpenBugReport = onOpenBugReport
            )
            }
        }
}

@Composable
private fun HudBottomBar(
    layout: HudButtonsLayout,
    bikeState: BikeState,
    isNavigating: Boolean,
    isRecording: Boolean,
    searchPin: SearchResultItem?,
    roadOptions: List<CalculatedRoadRoute>,
    selectedRoute: GPXRoute?,
    approachRoute: CalculatedRoadRoute?,
    groupRoomCode: String?,
    groupMembers: List<GroupMember>,
    onRequestStartNavigation: () -> Unit,
    onStopNavigation: () -> Unit,
    onRequestApproach: () -> Unit,
    onClearApproach: () -> Unit,
    onToggleRecording: () -> Unit,
    onOpenRoutes: () -> Unit,
    onOpenRouteGenerator: () -> Unit,
    onOpenQuickMarker: () -> Unit,
    onOpenWaypoints: () -> Unit,
    onOpenGroupSheet: () -> Unit,
    onOpenBikeSheet: () -> Unit,
    hasPendingCrashLog: Boolean,
    onOpenBugReport: () -> Unit
) {
    val hasTarget = (roadOptions.isNotEmpty() || (selectedRoute?.isVisible == true))
    val showNavBtn = isNavigating || hasTarget
    val showApproachBtn = selectedRoute != null && searchPin == null
    val showLeftItems = showNavBtn || showApproachBtn ||
        layout.recSlot == HudButtonSlot.BOTTOM ||
        layout.routesSlot == HudButtonSlot.BOTTOM ||
        layout.routeGeneratorSlot == HudButtonSlot.BOTTOM ||
        layout.quickPinSlot == HudButtonSlot.BOTTOM ||
        layout.waypointsSlot == HudButtonSlot.BOTTOM ||
        layout.bugReportSlot == HudButtonSlot.BOTTOM ||
        layout.groupSlot == HudButtonSlot.BOTTOM
    val showBikeBtn = layout.bikeSlot == HudButtonSlot.BOTTOM

    if (!showLeftItems && !showBikeBtn) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(EnduPalette.Surface.copy(alpha = 0.95f))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showLeftItems) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showNavBtn) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isNavigating) EnduPalette.Danger else EnduPalette.Success)
                            .clickable {
                                if (isNavigating) onStopNavigation() else onRequestStartNavigation()
                            }
                            .padding(horizontal = 9.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isNavigating) {
                                StopSquareIcon(color = Color.White)
                            } else {
                                NavArrowIcon(color = Color.White)
                            }
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (isNavigating) "Parar" else "Iniciar ruta",
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (showApproachBtn) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (approachRoute != null) EnduPalette.Accent.copy(alpha = 0.22f) else EnduPalette.SurfaceHigh)
                            .clickable { if (approachRoute != null) onClearApproach() else onRequestApproach() }
                            .padding(horizontal = 8.dp)
                    ) {
                        if (approachRoute != null) {
                            CloseCrossIcon(color = EnduPalette.Accent, size = 10.dp)
                        } else {
                            RoutePathIcon(color = EnduPalette.TextPrimary, size = 13.dp)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Enlace",
                            color = if (approachRoute != null) EnduPalette.Accent else EnduPalette.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (layout.recSlot == HudButtonSlot.BOTTOM) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isRecording) EnduPalette.Danger.copy(alpha = 0.24f) else EnduPalette.SurfaceHigh)
                            .border(
                                width = if (isRecording) 1.dp else 0.dp,
                                color = if (isRecording) EnduPalette.Danger else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onToggleRecording() }
                            .padding(horizontal = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(EnduPalette.Danger)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "REC",
                            color = if (isRecording) EnduPalette.Danger else EnduPalette.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                if (layout.routesSlot == HudButtonSlot.BOTTOM) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(EnduPalette.SurfaceHigh)
                            .clickable { onOpenRoutes() }
                            .padding(horizontal = 11.dp)
                    ) {
                        Text(
                            text = "Tracks",
                            color = EnduPalette.Accent,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (layout.routeGeneratorSlot == HudButtonSlot.BOTTOM) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(EnduPalette.SurfaceHigh)
                            .clickable { onOpenRouteGenerator() }
                            .padding(horizontal = 9.dp)
                    ) {
                        Text(
                            text = "Crear",
                            color = EnduPalette.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (layout.quickPinSlot == HudButtonSlot.BOTTOM) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(EnduPalette.SurfaceHigh)
                            .clickable { onOpenQuickMarker() }
                            .padding(horizontal = 8.dp)
                    ) {
                        QuickPinIcon(color = Color(0xFF00E5FF), size = 13.dp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Pin",
                            color = EnduPalette.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (layout.waypointsSlot == HudButtonSlot.BOTTOM) {
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
                }

                if (layout.bugReportSlot == HudButtonSlot.BOTTOM) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (hasPendingCrashLog) EnduPalette.Danger.copy(alpha = 0.22f) else EnduPalette.SurfaceHigh)
                            .clickable { onOpenBugReport() }
                            .padding(horizontal = 8.dp)
                    ) {
                        BugReportIcon(
                            color = if (hasPendingCrashLog) EnduPalette.Danger else EnduPalette.TextPrimary,
                            size = 13.dp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Bug",
                            color = if (hasPendingCrashLog) EnduPalette.Danger else EnduPalette.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (layout.groupSlot == HudButtonSlot.BOTTOM) {
                    val isGroupActive = groupRoomCode != null
                    val alertRider = groupMembers.firstOrNull { it.status.isAlert }
                    val groupBg = when {
                        alertRider != null -> EnduPalette.Danger.copy(alpha = 0.28f)
                        isGroupActive -> EnduPalette.Success.copy(alpha = 0.22f)
                        else -> EnduPalette.SurfaceHigh
                    }
                    val groupTint = when {
                        alertRider != null -> EnduPalette.Danger
                        isGroupActive -> EnduPalette.Success
                        else -> EnduPalette.TextPrimary
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(groupBg)
                            .clickable { onOpenGroupSheet() }
                            .padding(horizontal = 8.dp)
                    ) {
                        GroupRidersIcon(color = groupTint, size = 14.dp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = when {
                                alertRider != null -> "${alertRider.status.tftTag.ifBlank { "AVISO" }} · ${alertRider.name.take(6)}"
                                isGroupActive -> "Grupo (${groupMembers.size + 1})"
                                else -> "Grupo"
                            },
                            color = groupTint,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        if (showBikeBtn) {
            if (showLeftItems) Spacer(modifier = Modifier.width(6.dp))
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
                    .padding(horizontal = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MotorcycleSilhouetteIcon(
                        color = when (bikeState) {
                            BikeState.SHOWING -> EnduPalette.Success
                            BikeState.GREETED -> EnduPalette.AccentBlue
                            BikeState.LISTENING -> EnduPalette.Warning
                            BikeState.CLOSED -> EnduPalette.TextSecondary
                        },
                        size = 15.dp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .size(5.dp)
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
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (bikeState == BikeState.SHOWING) "En moto" else "Moto",
                        color = EnduPalette.TextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun DroppedPinCard(
    droppedPin: SearchResultItem,
    hasSearchOrRoute: Boolean,
    hasSearchPin: Boolean,
    selectedProfile: RouteTerrainProfile,
    onSelectProfile: (RouteTerrainProfile) -> Unit,
    onClearDroppedPin: () -> Unit,
    onAddDroppedPinAsStop: () -> Unit,
    onSetDroppedPinAsDestination: () -> Unit,
    onSaveDroppedPinAsWaypoint: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(EnduPalette.Surface.copy(alpha = 0.96f))
            .border(1.dp, EnduPalette.Accent.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QuickPinIcon(color = EnduPalette.Accent, size = 14.dp)
                    Spacer(modifier = Modifier.width(5.dp))
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

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clickable { onClearDroppedPin() }
                    .padding(6.dp)
            ) {
                CloseCrossIcon(color = EnduPalette.TextMuted, size = 13.dp)
            }
        }

        TerrainProfileSelectorRow(
            selectedProfile = selectedProfile,
            onSelectProfile = onSelectProfile
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (hasSearchOrRoute) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color(0xFF00D4FF).copy(alpha = 0.22f))
                        .border(1.dp, Color(0xFF00D4FF), RoundedCornerShape(7.dp))
                        .clickable { onAddDroppedPinAsStop() }
                        .padding(horizontal = 6.dp)
                ) {
                    PlusAddIcon(color = Color(0xFF00D4FF), size = 11.dp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Parada",
                        color = Color(0xFF00D4FF),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(EnduPalette.Accent)
                    .clickable { onSetDroppedPinAsDestination() }
                    .padding(horizontal = 6.dp)
            ) {
                if (hasSearchPin) {
                    FlagDestinationIcon(color = Color.White, size = 12.dp)
                } else {
                    NavArrowIcon(color = Color.White, size = 11.dp)
                }
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (hasSearchPin) "Cambiar" else "Ir",
                    color = Color.White,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF00E5FF).copy(alpha = 0.22f))
                    .border(1.dp, Color(0xFF00E5FF), RoundedCornerShape(7.dp))
                    .clickable { onSaveDroppedPinAsWaypoint() }
                    .padding(horizontal = 6.dp)
            ) {
                SaveBookmarkIcon(color = Color(0xFF00E5FF), size = 12.dp)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Guardar",
                    color = Color(0xFF00E5FF),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun DestinationRouteCard(
    searchPin: SearchResultItem,
    intermediateWaypoints: List<SearchResultItem>,
    roadOptions: List<CalculatedRoadRoute>,
    selectedRoadIndex: Int,
    isCalculatingRoute: Boolean,
    selectedProfile: RouteTerrainProfile,
    cardFontScale: Float,
    onSelectProfile: (RouteTerrainProfile) -> Unit,
    onClearDestination: () -> Unit,
    onRemoveIntermediateWaypoint: (Int) -> Unit,
    onOpenAddStopSearch: () -> Unit,
    onSaveAsTrack: () -> Unit,
    onSelectRoadAlternative: (Int) -> Unit
) {
    val activeOpt = roadOptions.getOrNull(selectedRoadIndex)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(EnduPalette.Surface.copy(alpha = 0.95f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
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
                    fontSize = (13f * cardFontScale).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCalculatingRoute) {
                    Text(
                        text = "Calculando ruta ${selectedProfile.shortLabel}…",
                        color = EnduPalette.TextMuted,
                        fontSize = (11f * cardFontScale).sp
                    )
                } else if (activeOpt != null) {
                    val stopCountText = if (intermediateWaypoints.isNotEmpty()) {
                        " · ${intermediateWaypoints.size} parada${if (intermediateWaypoints.size > 1) "s" else ""}"
                    } else ""
                    val elevText = if (activeOpt.elevationGainM > 5.0) {
                        " · +${activeOpt.elevationGainM.roundToInt()} m"
                    } else ""
                    Text(
                        text = "${activeOpt.expectedMinutes} min · ${(activeOpt.distanceKm * 10.0).roundToInt() / 10.0} km$elevText$stopCountText",
                        color = EnduPalette.Accent,
                        fontSize = (11f * cardFontScale).sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = searchPin.subtitle,
                        color = EnduPalette.TextMuted,
                        fontSize = (11f * cardFontScale).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

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

        TerrainProfileSelectorRow(
            selectedProfile = selectedProfile,
            onSelectProfile = onSelectProfile
        )

        if (intermediateWaypoints.isNotEmpty()) {
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF00D4FF).copy(alpha = 0.18f))
                    .border(1.dp, Color(0xFF00D4FF).copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .clickable { onOpenAddStopSearch() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                PlusAddIcon(color = Color(0xFF00D4FF), size = 10.dp)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Parada",
                    color = Color(0xFF00D4FF),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (activeOpt != null && !isCalculatingRoute) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(EnduPalette.Success.copy(alpha = 0.18f))
                        .border(1.dp, EnduPalette.Success.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                        .clickable { onSaveAsTrack() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    SaveBookmarkIcon(color = EnduPalette.Success, size = 11.dp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Guardar en Tracks",
                        color = EnduPalette.Success,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (roadOptions.size > 1) {
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
                            text = "${opt.routeLabel} · ${opt.expectedMinutes} min (${opt.distanceKm.roundToInt()} km)",
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

@Composable
private fun TelemetryItem(
    label: String,
    value: String,
    unit: String,
    color: Color = EnduPalette.TextPrimary,
    fontScale: Float = 1.0f
) {
    val labelScale = fontScale.coerceAtMost(1.45f)
    val unitScale = fontScale.coerceAtMost(1.5f)
    Column {
        Text(
            text = label,
            color = EnduPalette.TextMuted,
            fontSize = (9f * labelScale).sp,
            fontWeight = FontWeight.Bold
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = color,
                fontSize = (16f * fontScale).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = unit,
                color = EnduPalette.TextMuted,
                fontSize = (10f * unitScale).sp,
                modifier = Modifier.padding(bottom = 1.dp)
            )
        }
    }
}

@Composable
private fun MapCircleButton(
    isActive: Boolean = false,
    activeBgColor: Color = EnduPalette.Accent,
    defaultBgColor: Color = EnduPalette.Surface.copy(alpha = 0.92f),
    buttonSize: Dp = 38.dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(if (isActive) activeBgColor else defaultBgColor)
            .border(
                width = 1.dp,
                color = if (isActive) activeBgColor else EnduPalette.Outline,
                shape = CircleShape
            )
            .clickable { onClick() }
    ) {
        content()
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
