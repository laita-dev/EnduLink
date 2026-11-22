package com.oscar.endulink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import com.oscar.endulink.group.GroupMember
import com.oscar.endulink.platform.PlatformBackHandler
import com.oscar.endulink.platform.PlatformToastCenter
import com.oscar.endulink.platform.closeApp
import com.oscar.endulink.platform.rememberPlatformSafeAreaPadding
import com.oscar.endulink.platform.showPlatformToast
import com.oscar.endulink.trail.*
import kotlinx.datetime.Clock

@Composable
fun App() {
    val controller = remember { EnduController.shared }

    // Iniciar geolocalización al abrir (SIN ruta activa inicial por petición del usuario)
    LaunchedEffect(Unit) {
        controller.start()
    }

    // Selector de archivos GPX nativo del sistema
    val pickGPX = rememberGPXFilePicker { name, content ->
        controller.importGPXContent(name, content)
    }

    // Estados reactivos
    val rider by controller.riderTelemetry.collectAsState()
    val followMode by controller.followMode.collectAsState()
    val layer by controller.selectedLayer.collectAsState()
    val zoom by controller.mapZoom.collectAsState()
    val center by controller.mapCenter.collectAsState()
    val heading by controller.mapHeading.collectAsState()

    val isNavigating by controller.isNavigating.collectAsState()
    val searchPin by controller.searchPin.collectAsState()
    val droppedPin by controller.droppedPin.collectAsState()
    val intermediateWaypoints by controller.intermediateWaypoints.collectAsState()
    val roadOptions by controller.destinationRouteOptions.collectAsState()
    val selectedRoadIdx by controller.selectedDestinationOptionIndex.collectAsState()
    val isCalculatingRoute by controller.isCalculatingDestinationRoute.collectAsState()
    val statusBanner by controller.statusBannerMessage.collectAsState()
    val approachRoute by controller.approachRoadRoute.collectAsState()
    val crowFliesActive by controller.crowFliesModeActive.collectAsState()

    val routes by controller.routes.collectAsState()
    val activeRoute by controller.activeRoute.collectAsState()
    val trackProgress by controller.trackProgress.collectAsState()
    val quickMarkers by controller.quickMarkers.collectAsState()

    val isRecording by controller.isRecording.collectAsState()
    val recordedCoords by controller.recordedCoords.collectAsState()
    val recordingStats by controller.recordingStats.collectAsState()

    val bikeState by controller.bikeState.collectAsState()
    val bikeLogs by controller.bikeLogs.collectAsState()
    val bikeQr by controller.bikeQr.collectAsState()
    val encoderStats by controller.encoderStats.collectAsState()
    val bikeMapConfig by controller.bikeMapConfig.collectAsState()
    val livePreviewBitmap by controller.livePreviewBitmap.collectAsState()
    val allowLocalLanTesting by controller.allowLocalLanTesting.collectAsState()
    val wifiSnapshot by controller.wifiSnapshot.collectAsState()
    val bikeSearchRemainingSeconds by controller.bikeSearchRemainingSeconds.collectAsState()
    val offlineProgress by controller.offlineProgress.collectAsState()
    val downloadedPackages by controller.tileStore.downloadedPackages.collectAsState()
    val tileRevision by controller.tileRevision.collectAsState()
    val inAppToast by PlatformToastCenter.toast.collectAsState()

    val groupRoomCode by controller.groupRoomCode.collectAsState()
    val groupConnectionState by controller.groupConnectionState.collectAsState()
    val groupOnlineConnected by controller.groupOnlineConnected.collectAsState()
    val groupLanActive by controller.groupLanActive.collectAsState()
    val groupMembers by controller.groupMembers.collectAsState()
    val groupMyStatus by controller.groupMyStatus.collectAsState()
    val groupMyStatusMessage by controller.groupMyStatusMessage.collectAsState()
    val groupConfig by controller.groupConfig.collectAsState()
    val groupSharedTrack by controller.groupSharedTrack.collectAsState()
    val groupSharedTrackBannerVisible by controller.groupSharedTrackBannerVisible.collectAsState()
    val pendingGroupJoinCode by controller.pendingGroupJoinCode.collectAsState()

    LaunchedEffect(inAppToast?.id) {
        val current = inAppToast ?: return@LaunchedEffect
        kotlinx.coroutines.delay(2200L)
        PlatformToastCenter.clearIfMatches(current.id)
    }

    // Control de ventanas modales
    var showLayersPanel by remember { mutableStateOf(false) }
    var showRoutesSheet by remember { mutableStateOf(false) }
    var showOfflineSheet by remember { mutableStateOf(false) }
    var showBikeSheet by remember { mutableStateOf(false) }
    var showQuickMarkerSheet by remember { mutableStateOf(false) }
    var showWaypointsSheet by remember { mutableStateOf(false) }
    var selectedWaypointForDetail by remember { mutableStateOf<GPXWaypoint?>(null) }
    var showSaveWaypointDialog by remember { mutableStateOf(false) }
    var saveWaypointInitialCoord by remember { mutableStateOf<GeoCoord?>(null) }
    var saveWaypointInitialName by remember { mutableStateOf("") }
    var saveWaypointInitialSymbol by remember { mutableStateOf("flag") }
    var waypointToEdit by remember { mutableStateOf<GPXWaypoint?>(null) }
    var showSearchSheet by remember { mutableStateOf(false) }
    var isSearchingForStop by remember { mutableStateOf(false) }
    var isPickingOnMap by remember { mutableStateOf(false) }
    var pickingAddress by remember { mutableStateOf<String?>(null) }
    var showElevationSheet by remember { mutableStateOf(false) }
    var showSaveRecordingDialog by remember { mutableStateOf(false) }
    var pendingRecordingStats by remember { mutableStateOf(Triple(0.0, 0.0, 0L)) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var routeForDetailId by remember { mutableStateOf<String?>(null) }
    var routeToEditId by remember { mutableStateOf<String?>(null) }
    var showGroupSheet by remember { mutableStateOf(false) }
    var selectedGroupMemberId by remember { mutableStateOf<String?>(null) }

    val selectedGroupMember: GroupMember? = selectedGroupMemberId?.let { id ->
        groupMembers.firstOrNull { it.id == id }
    }

    LaunchedEffect(pendingGroupJoinCode) {
        if (pendingGroupJoinCode != null) {
            showGroupSheet = true
        }
    }

    LaunchedEffect(selectedGroupMemberId, selectedGroupMember) {
        if (selectedGroupMemberId != null && selectedGroupMember == null) {
            selectedGroupMemberId = null
        }
    }

    // Geocodificación inversa continua del centro de la pantalla en modo selección en el mapa
    LaunchedEffect(isPickingOnMap, center) {
        if (!isPickingOnMap) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        val resolved = PlaceSearchEngine.reverseGeocode(center, rider.coordinate)
        pickingAddress = resolved.title
    }

    // Diálogo de enlace por carretera al seleccionar track lejano
    var pendingApproachRoute by remember { mutableStateOf<GPXRoute?>(null) }
    var pendingApproachDistance by remember { mutableStateOf(0.0) }

    fun activateRouteWithApproachPrompt(route: GPXRoute) {
        val rPos = rider.coordinate
        val startPt = route.points.firstOrNull()?.coordinate
        if (rPos != null && startPt != null) {
            val dist = rPos.distanceTo(startPt)
            if (dist > 300.0) {
                pendingApproachRoute = route
                pendingApproachDistance = dist
                return
            }
        }
        controller.selectRoute(route)
    }

    // Control del botón atrás físico de Android con doble pulsación para salir
    var lastBackPressTime by remember { mutableStateOf(0L) }
    PlatformBackHandler(enabled = true) {
        when {
            showUpdateDialog -> showUpdateDialog = false
            showLayersPanel -> showLayersPanel = false
            showSearchSheet -> {
                isSearchingForStop = false
                showSearchSheet = false
            }
            isPickingOnMap -> isPickingOnMap = false
            showSaveWaypointDialog -> {
                showSaveWaypointDialog = false
                waypointToEdit = null
            }
            selectedGroupMemberId != null -> selectedGroupMemberId = null
            showGroupSheet -> showGroupSheet = false
            routeToEditId != null -> routeToEditId = null
            routeForDetailId != null -> routeForDetailId = null
            selectedWaypointForDetail != null -> selectedWaypointForDetail = null
            showWaypointsSheet -> showWaypointsSheet = false
            droppedPin != null -> controller.clearDroppedPin()
            showElevationSheet -> {
                controller.setScrubbedPoint(null)
                showElevationSheet = false
            }
            showRoutesSheet -> showRoutesSheet = false
            showOfflineSheet -> showOfflineSheet = false
            showBikeSheet -> showBikeSheet = false
            showQuickMarkerSheet -> showQuickMarkerSheet = false
            showSaveRecordingDialog -> showSaveRecordingDialog = false
            pendingApproachRoute != null -> pendingApproachRoute = null
            searchPin != null -> controller.clearSearchAndDestinationRoute()
            isNavigating -> controller.stopNavigation()
            activeRoute != null -> controller.deselectRoute()
            else -> {
                val now = Clock.System.now().toEpochMilliseconds()
                if (now - lastBackPressTime < 2000L) {
                    closeApp()
                } else {
                    lastBackPressTime = now
                    showPlatformToast("Pulsa de nuevo para salir de EnduLink")
                }
            }
        }
    }

    EnduLinkTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = EnduPalette.Background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {

                // 1. MAPA INTERACTIVO A PANTALLA COMPLETA (borde a borde)
                InteractiveMap(
                    controller = controller,
                    layer = layer,
                    zoom = zoom,
                    center = center,
                    heading = heading,
                    rider = rider,
                    activeRoute = activeRoute,
                    secondaryRoutes = routes,
                    recordedCoords = recordedCoords,
                    quickMarkers = quickMarkers,
                    trackProgress = trackProgress,
                    groupMembers = groupMembers,
                    isPickingOnMap = isPickingOnMap,
                    onSelectWaypoint = { wpt ->
                        selectedGroupMemberId = null
                        routeForDetailId = null
                        selectedWaypointForDetail = wpt
                    },
                    onSelectRoute = { tappedRoute ->
                        selectedGroupMemberId = null
                        selectedWaypointForDetail = null
                        routeForDetailId = tappedRoute.id
                    },
                    onSelectGroupMember = { member ->
                        selectedWaypointForDetail = null
                        routeForDetailId = null
                        selectedGroupMemberId = member.id
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 2. CAPA DE INTERFAZ CON SAFE AREA DINÁMICA (respeta Dynamic Island, Notch, barra de estado, Home Indicator y bordes curvos en cualquier modelo)
                val safeAreaPadding = rememberPlatformSafeAreaPadding()
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(safeAreaPadding)
                ) {
                val isTabletLayout = maxWidth >= 600.dp
                val largeSheetModifier = if (isTabletLayout) {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp)
                        .widthIn(max = 640.dp)
                } else {
                    Modifier.align(Alignment.BottomCenter)
                }
                val compactSheetModifier = if (isTabletLayout) {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp)
                        .widthIn(max = 560.dp)
                } else {
                    Modifier.align(Alignment.BottomCenter)
                }
                HudOverlay(
                    rider = rider,
                    trackProgress = trackProgress,
                    bikeState = bikeState,
                    followMode = followMode,
                    isNavigating = isNavigating,
                    isRecording = isRecording,
                    recordingStats = recordingStats,
                    heading = heading,
                    searchPin = searchPin,
                    droppedPin = droppedPin,
                    intermediateWaypoints = intermediateWaypoints,
                    roadOptions = roadOptions,
                    selectedRoadIndex = selectedRoadIdx,
                    isCalculatingRoute = isCalculatingRoute,
                    statusBannerMessage = statusBanner,
                    selectedRoute = activeRoute,
                    approachRoute = approachRoute,
                    crowFliesActive = crowFliesActive,
                    onOpenSearch = {
                        isSearchingForStop = false
                        showSearchSheet = true
                    },
                    onClearDestination = { controller.clearSearchAndDestinationRoute() },
                    onClearDroppedPin = { controller.clearDroppedPin() },
                    onSetDroppedPinAsDestination = { controller.setDroppedPinAsDestination() },
                    onAddDroppedPinAsStop = { controller.addDroppedPinAsStop() },
                    onRemoveIntermediateWaypoint = { idx -> controller.removeIntermediateWaypoint(idx) },
                    onOpenAddStopSearch = {
                        isSearchingForStop = true
                        showSearchSheet = true
                    },
                    onDeselectRoute = { controller.deselectRoute() },
                    onSelectRoadAlternative = { idx -> controller.selectDestinationAlternative(idx) },
                    onRequestStartNavigation = { controller.requestStartNavigation() },
                    onStopNavigation = { controller.stopNavigation() },
                    onRequestApproach = {
                        controller.calculateApproachToGPX(toNearestPoint = true, autoStart = false)
                    },
                    onClearApproach = { controller.clearApproachRoute() },
                    onDismissBanner = { controller.dismissBanner() },
                    onZoomIn = { controller.zoomIn() },
                    onZoomOut = { controller.zoomOut() },
                    onFitMap = { controller.fitActiveContentOnMap() },
                    onToggleFollowMode = {
                        controller.centerOnUserLocation()
                    },
                    onToggleOrientation = { controller.toggleOrientation() },
                    onToggleCrowFlies = { controller.toggleCrowFlies() },
                    onToggleRecording = {
                        if (isRecording) {
                            pendingRecordingStats = controller.getRecordingStats()
                            showSaveRecordingDialog = true
                        } else {
                            controller.startRecording()
                        }
                    },
                    onOpenLayers = { showLayersPanel = !showLayersPanel },
                    onOpenOffline = { showOfflineSheet = true },
                    onOpenSettings = { showUpdateDialog = true },
                    onOpenRoutes = { showRoutesSheet = true },
                    onOpenBikeSheet = { showBikeSheet = true },
                    onOpenQuickMarker = { showQuickMarkerSheet = true },
                    onSaveDroppedPinAsWaypoint = {
                        val pin = droppedPin
                        if (pin != null) {
                            saveWaypointInitialCoord = pin.coordinate
                            saveWaypointInitialName = pin.title
                            saveWaypointInitialSymbol = "flag"
                            waypointToEdit = null
                            showSaveWaypointDialog = true
                        }
                    },
                    onOpenWaypoints = { showWaypointsSheet = true },
                    onOpenElevation = { showElevationSheet = true },
                    onShareSelectedRoute = {
                        activeRoute?.let { controller.shareRoute(it) }
                    },
                    onOpenSelectedRouteOptions = {
                        activeRoute?.let {
                            selectedWaypointForDetail = null
                            routeForDetailId = it.id
                        }
                    },
                    isPickingOnMap = isPickingOnMap,
                    isSearchingForStop = isSearchingForStop,
                    pickingCoordAddress = pickingAddress,
                    onPickOnMap = {
                        isPickingOnMap = true
                    },
                    onConfirmPickOnMap = {
                        val pos = rider.coordinate
                        val dist = pos?.let { it.distanceTo(center) / 1000.0 }
                        val item = SearchResultItem(
                            title = pickingAddress ?: "Ubicación en el mapa",
                            subtitle = PlaceSearchEngine.formatCoord(center),
                            coordinate = center,
                            distanceKm = dist
                        )
                        if (isSearchingForStop) {
                            controller.addIntermediateWaypoint(item)
                        } else {
                            controller.selectSearchResult(item)
                        }
                        isPickingOnMap = false
                        isSearchingForStop = false
                    },
                    onConfirmPickAsStop = {
                        val pos = rider.coordinate
                        val dist = pos?.let { it.distanceTo(center) / 1000.0 }
                        val item = SearchResultItem(
                            title = pickingAddress ?: "Parada en el mapa",
                            subtitle = PlaceSearchEngine.formatCoord(center),
                            coordinate = center,
                            distanceKm = dist
                        )
                        controller.addIntermediateWaypoint(item)
                        isPickingOnMap = false
                        isSearchingForStop = false
                    },
                    onCancelPickOnMap = {
                        isPickingOnMap = false
                        isSearchingForStop = false
                    },
                    groupRoomCode = groupRoomCode,
                    groupMembers = groupMembers,
                    groupConnectionState = groupConnectionState,
                    groupSharedTrack = if (groupSharedTrackBannerVisible) groupSharedTrack else null,
                    onOpenGroupSheet = { showGroupSheet = true },
                    onSelectAlertMember = { member ->
                        selectedWaypointForDetail = null
                        routeForDetailId = null
                        selectedGroupMemberId = member.id
                    },
                    onNavigateToAlertMember = { member ->
                        controller.navigateToGroupMember(member)
                    },
                    onImportSharedTrack = {
                        controller.importSharedGroupTrack()
                    },
                    onDismissSharedTrack = {
                        controller.dismissSharedGroupTrackBanner()
                    }
                )

                // Panel flotante selector de Capas (Carreteras, Satélite, Trail OSM, IGN Topo)
                if (showLayersPanel) {
                    LayersFloatingCard(
                        currentLayer = layer,
                        onSelectLayer = { lay ->
                            controller.setLayer(lay)
                            showLayersPanel = false
                        },
                        onClose = { showLayersPanel = false },
                        modifier = if (isTabletLayout) {
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 56.dp, start = 12.dp, end = 12.dp)
                                .widthIn(max = 460.dp)
                        } else {
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 52.dp, start = 12.dp, end = 12.dp)
                        }
                    )
                }

                // 3. DIÁLOGO DE ENLACE POR CARRETERA AL TRACK GPX
                if (pendingApproachRoute != null) {
                    val appRoute = pendingApproachRoute!!
                    ApproachPromptDialog(
                        route = appRoute,
                        distanceMeters = pendingApproachDistance,
                        onApproachToStart = {
                            pendingApproachRoute = null
                            controller.selectRoute(appRoute)
                            controller.calculateApproachToGPX(target = ApproachTarget.START, autoStart = true)
                        },
                        onApproachToFinish = {
                            pendingApproachRoute = null
                            controller.selectRoute(appRoute)
                            controller.calculateApproachToGPX(target = ApproachTarget.FINISH, autoStart = true)
                        },
                        onApproachToNearest = {
                            pendingApproachRoute = null
                            controller.selectRoute(appRoute)
                            controller.calculateApproachToGPX(target = ApproachTarget.NEAREST, autoStart = true)
                        },
                        onStartWithoutApproach = {
                            pendingApproachRoute = null
                            controller.selectRoute(appRoute)
                            controller.startNavigation()
                        },
                        onDismiss = {
                            pendingApproachRoute = null
                        }
                    )
                }

                // 4. VENTANAS MODALES COMPLETAS

                // Búsqueda de Destino o Parada Intermedia
                if (showSearchSheet) {
                    PlaceSearchSheet(
                        riderCoord = rider.coordinate ?: center,
                        routes = routes,
                        waypoints = quickMarkers,
                        title = if (isSearchingForStop) "Añadir Parada Intermedia" else "Buscar Destino",
                        hasActiveRoute = (searchPin != null || activeRoute != null),
                        isSearchingForStop = isSearchingForStop,
                        onSelectDestination = { item ->
                            controller.selectSearchResult(item)
                            isSearchingForStop = false
                            showSearchSheet = false
                        },
                        onAddIntermediateStop = { item ->
                            controller.addIntermediateWaypoint(item)
                            isSearchingForStop = false
                            showSearchSheet = false
                        },
                        onPickOnMap = {
                            showSearchSheet = false
                            isPickingOnMap = true
                        },
                        onClose = {
                            isSearchingForStop = false
                            showSearchSheet = false
                        }
                    )
                }

                if (showElevationSheet && activeRoute != null) {
                    ElevationProfileSheet(
                        route = activeRoute!!,
                        onScrubPoint = { pt -> controller.setScrubbedPoint(pt) },
                        onClose = {
                            controller.setScrubbedPoint(null)
                            showElevationSheet = false
                        },
                        modifier = compactSheetModifier
                    )
                }

                if (showSaveRecordingDialog) {
                    SaveTrackDialog(
                        distanceKm = pendingRecordingStats.first,
                        elevationGainM = pendingRecordingStats.second,
                        durationSeconds = pendingRecordingStats.third,
                        onSave = { name ->
                            controller.saveRecordedTrack(name)
                            showSaveRecordingDialog = false
                        },
                        onDiscard = {
                            controller.discardRecordedTrack()
                            showSaveRecordingDialog = false
                        },
                        onContinue = {
                            showSaveRecordingDialog = false
                        }
                    )
                }

                if (showRoutesSheet) {
                    RoutesDrawer(
                        routes = routes,
                        activeRouteId = activeRoute?.id,
                        onSelectRoute = { route ->
                            showRoutesSheet = false
                            activateRouteWithApproachPrompt(route)
                        },
                        onToggleVisibility = { controller.toggleRouteVisibility(it) },
                        onInvertRoute = { controller.invertRoute(it) },
                        onCycleColor = { controller.cycleRouteColor(it) },
                        onOpenElevation = { route ->
                            controller.selectRoute(route)
                            showRoutesSheet = false
                            showElevationSheet = true
                        },
                        onEditRoute = { route ->
                            showRoutesSheet = false
                            routeToEditId = route.id
                        },
                        onDownloadCorridor = { route ->
                            controller.downloadRouteCorridor(route)
                            showRoutesSheet = false
                        },
                        onShareRoute = { controller.shareRoute(it) },
                        onDeleteRoute = { controller.deleteRoute(it) },
                        onImportGPX = {
                            pickGPX()
                        },
                        onLoadSample = {
                            val sample = """
                                <?xml version="1.0" encoding="UTF-8"?>
                                <gpx version="1.1" creator="EnduLink">
                                  <trk><name>Paso de Montaña</name><trkseg>
                                    <trkpt lat="40.7500" lon="-4.0200"><ele>1100.0</ele></trkpt>
                                    <trkpt lat="40.7600" lon="-4.0150"><ele>1250.0</ele></trkpt>
                                    <trkpt lat="40.7700" lon="-4.0100"><ele>1380.0</ele></trkpt>
                                  </trkseg></trk>
                                </gpx>
                            """.trimIndent()
                            controller.loadSampleRoute("Paso de Montaña", sample)
                        },
                        onClose = { showRoutesSheet = false },
                        modifier = largeSheetModifier
                    )
                }

                if (showOfflineSheet) {
                    OfflineMapsSheet(
                        selectedLayer = layer,
                        onSelectLayer = { controller.setLayer(it) },
                        downloadedPackages = downloadedPackages,
                        progress = offlineProgress,
                        onDownloadRegion = { preset, targetLayer, customMaxZoom ->
                            controller.downloadRegion(preset, targetLayer, customMaxZoom)
                        },
                        onRemovePackage = { key -> controller.tileStore.removePackage(key) },
                        onClearLayerCache = { targetLayer -> controller.tileStore.clearLayerCache(targetLayer) },
                        getCacheSizeBytes = { targetLayer -> controller.tileStore.getCacheSizeBytes(targetLayer) },
                        onCancelDownload = { controller.tileStore.cancelDownload() },
                        onClearCache = { controller.tileStore.clearDiskCache() },
                        onClose = { showOfflineSheet = false },
                        modifier = largeSheetModifier
                    )
                }

                LaunchedEffect(showBikeSheet) {
                    controller.setBikeSheetOpen(showBikeSheet)
                }

                if (showBikeSheet) {
                    LaunchedEffect(layer, bikeMapConfig, activeRoute, quickMarkers, intermediateWaypoints, groupRoomCode, groupMembers) {
                        while (true) {
                            controller.refreshStaticPreview()
                            kotlinx.coroutines.delay(1000)
                        }
                    }
                    LaunchedEffect(tileRevision) {
                        if (tileRevision > 0) {
                            kotlinx.coroutines.delay(120)
                            controller.refreshStaticPreview()
                        }
                    }
                    BikeConnectionSheet(
                        state = bikeState,
                        bikeQr = bikeQr,
                        stats = encoderStats,
                        logs = bikeLogs,
                        config = bikeMapConfig,
                        phoneLayer = layer,
                        livePreviewBitmap = livePreviewBitmap,
                        allowLocalLanTesting = allowLocalLanTesting,
                        wifiSnapshot = wifiSnapshot,
                        onSetZoom = { controller.setBikeZoom(it) },
                        onSetOrientation = { controller.setBikeOrientation(it) },
                        onSetTileMagnification = { controller.setBikeTileMagnification(it) },
                        onSetAutoZoomBySpeed = { controller.setBikeAutoZoomBySpeed(it) },
                        onSetAutoZoomConfig = { lowSpd, midSpd, lowZ, midZ, highZ ->
                            controller.setBikeAutoZoomConfig(lowSpd, midSpd, lowZ, midZ, highZ)
                        },
                        onSetBitrate = { controller.setBikeBitrate(it) },
                        onSetBikeMapLayer = { controller.setBikeMapLayer(it) },
                        onSetBikeGroupDisplayConfig = { showMembers, showNames, showEdgeRadar, showHudBadge, showAlerts ->
                            controller.setBikeGroupDisplayConfig(showMembers, showNames, showEdgeRadar, showHudBadge, showAlerts)
                        },
                        onSetBikePhoneNotificationsConfig = { enabled, durationSeconds ->
                            controller.setBikePhoneNotificationsConfig(enabled, durationSeconds)
                        },
                        onTriggerTestTftNotification = {
                            controller.triggerTestTftNotification()
                        },
                        onSetAllowLocalLanTesting = { controller.setAllowLocalLanTesting(it) },
                        onSaveQr = { controller.saveScannedQr(it) },
                        onClearQr = { controller.clearScannedQr() },
                        onConnectBikeWifi = { controller.connectToSavedBikeWifi() },
                        searchRemainingSeconds = bikeSearchRemainingSeconds,
                        onStartStream = { controller.startBikeStream() },
                        onStopStream = { controller.stopBikeStream() },
                        onClearLogs = { controller.clearBikeLogs() },
                        onClose = { showBikeSheet = false },
                        modifier = largeSheetModifier
                    )
                }

                if (showQuickMarkerSheet) {
                    QuickWaypointSheet(
                        onAddMarker = { name, symbol -> controller.addQuickMarker(name, symbol) },
                        onOpenWaypointsManager = {
                            showQuickMarkerSheet = false
                            showWaypointsSheet = true
                        },
                        onClose = { showQuickMarkerSheet = false },
                        modifier = compactSheetModifier
                    )
                }

                // Tarjeta inferior de Detalle de Waypoint al tocarlo en el mapa
                selectedWaypointForDetail?.let { wpt ->
                    WaypointDetailSheet(
                        waypoint = wpt,
                        riderCoord = rider.coordinate,
                        onNavigate = {
                            controller.navigateToWaypoint(wpt)
                            selectedWaypointForDetail = null
                        },
                        onAddStop = {
                            controller.addWaypointAsStop(wpt)
                            selectedWaypointForDetail = null
                        },
                        onEdit = {
                            waypointToEdit = wpt
                            saveWaypointInitialCoord = wpt.coordinate
                            saveWaypointInitialName = wpt.name
                            saveWaypointInitialSymbol = wpt.symbol ?: "flag"
                            selectedWaypointForDetail = null
                            showSaveWaypointDialog = true
                        },
                        onDelete = {
                            controller.deleteWaypoint(wpt.id)
                            selectedWaypointForDetail = null
                        },
                        onClose = {
                            selectedWaypointForDetail = null
                        },
                        modifier = compactSheetModifier
                    )
                }

                // Tarjeta inferior de Opciones del track GPX seleccionado en el mapa o HUD
                val selectedRouteForDetail = routeForDetailId?.let { id -> routes.firstOrNull { it.id == id } }
                if (selectedRouteForDetail != null) {
                    RouteDetailSheet(
                        route = selectedRouteForDetail,
                        isActive = selectedRouteForDetail.id == activeRoute?.id,
                        riderCoord = rider.coordinate,
                        onStartNavigation = { route ->
                            routeForDetailId = null
                            activateRouteWithApproachPrompt(route)
                        },
                        onCenterOnMap = { route ->
                            controller.focusRouteOnMap(route)
                            routeForDetailId = null
                        },
                        onOpenElevation = { route ->
                            controller.selectRoute(route, fitToBounds = false)
                            routeForDetailId = null
                            showElevationSheet = true
                        },
                        onSelectColor = { route, color ->
                            controller.setRouteColor(route, color)
                        },
                        onEditDetails = { route ->
                            routeToEditId = route.id
                        },
                        onInvertRoute = { route ->
                            controller.invertRoute(route)
                        },
                        onToggleVisibility = { route ->
                            controller.toggleRouteVisibility(route)
                        },
                        onDownloadCorridor = { route ->
                            controller.downloadRouteCorridor(route)
                            routeForDetailId = null
                        },
                        onShareRoute = { route ->
                            controller.shareRoute(route)
                        },
                        onDeleteRoute = { route ->
                            controller.deleteRoute(route)
                            routeForDetailId = null
                        },
                        onClose = {
                            routeForDetailId = null
                        },
                        modifier = compactSheetModifier
                    )
                }

                // Gestor y Lista completa de Waypoints
                if (showWaypointsSheet) {
                    WaypointsSheet(
                        waypoints = quickMarkers,
                        riderCoord = rider.coordinate,
                        onNavigateToWaypoint = { wpt ->
                            controller.navigateToWaypoint(wpt)
                            showWaypointsSheet = false
                        },
                        onCenterOnWaypoint = { wpt ->
                            controller.centerOnWaypoint(wpt)
                            showWaypointsSheet = false
                        },
                        onEditWaypoint = { wpt ->
                            waypointToEdit = wpt
                            saveWaypointInitialCoord = wpt.coordinate
                            saveWaypointInitialName = wpt.name
                            saveWaypointInitialSymbol = wpt.symbol ?: "flag"
                            showWaypointsSheet = false
                            showSaveWaypointDialog = true
                        },
                        onDeleteWaypoint = { wpt ->
                            controller.deleteWaypoint(wpt.id)
                        },
                        onAddNewWaypoint = {
                            val coord = rider.coordinate ?: center
                            saveWaypointInitialCoord = coord
                            saveWaypointInitialName = ""
                            saveWaypointInitialSymbol = "flag"
                            waypointToEdit = null
                            showWaypointsSheet = false
                            showSaveWaypointDialog = true
                        },
                        onClose = { showWaypointsSheet = false },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                // Diálogo para guardar/editar Waypoint con categoría
                if (showSaveWaypointDialog && saveWaypointInitialCoord != null) {
                    Dialog(
                        onDismissRequest = {
                            showSaveWaypointDialog = false
                            waypointToEdit = null
                        }
                    ) {
                        SaveWaypointDialog(
                            initialName = saveWaypointInitialName,
                            initialSymbol = saveWaypointInitialSymbol,
                            coordinate = saveWaypointInitialCoord!!,
                            onSave = { name, symbol ->
                                val editWpt = waypointToEdit
                                if (editWpt != null) {
                                    controller.updateWaypoint(
                                        id = editWpt.id,
                                        newName = name,
                                        newSymbol = symbol
                                    )
                                } else {
                                    controller.addWaypoint(
                                        name = name,
                                        symbol = symbol,
                                        coord = saveWaypointInitialCoord
                                    )
                                }
                                showSaveWaypointDialog = false
                                waypointToEdit = null
                            },
                            onDismiss = {
                                showSaveWaypointDialog = false
                                waypointToEdit = null
                            }
                        )
                    }
                }

                // Diálogo para editar propiedades de un track GPX (nombre, color, notas, sentido)
                val routeToEdit = routeToEditId?.let { id -> routes.firstOrNull { it.id == id } }
                if (routeToEdit != null) {
                    Dialog(
                        onDismissRequest = { routeToEditId = null }
                    ) {
                        EditRouteDialog(
                            route = routeToEdit,
                            onSave = { newName, newColor, newNotes, newIsVisible, toggleInvert ->
                                controller.updateRouteDetails(
                                    routeId = routeToEdit.id,
                                    newName = newName,
                                    newColor = newColor,
                                    newNotes = newNotes,
                                    newIsVisible = newIsVisible
                                )
                                if (toggleInvert) {
                                    controller.invertRoute(routeToEdit)
                                }
                                routeToEditId = null
                            },
                            onDismiss = { routeToEditId = null }
                        )
                    }
                }

                // Tarjeta inferior de Detalle de Compañero del Grupo
                selectedGroupMember?.let { member ->
                    GroupMemberDetailSheet(
                        member = member,
                        riderCoord = rider.coordinate,
                        hasActiveRoute = (searchPin != null || activeRoute != null),
                        onNavigate = {
                            controller.navigateToGroupMember(member)
                            selectedGroupMemberId = null
                        },
                        onCenter = {
                            controller.centerOnGroupMember(member)
                            selectedGroupMemberId = null
                        },
                        onAddStop = {
                            controller.addGroupMemberAsStop(member)
                            selectedGroupMemberId = null
                        },
                        onSaveAsWaypoint = {
                            saveWaypointInitialCoord = member.coordinate
                            saveWaypointInitialName = member.name
                            saveWaypointInitialSymbol = if (member.status.isAlert) "danger" else "camp"
                            waypointToEdit = null
                            selectedGroupMemberId = null
                            showSaveWaypointDialog = true
                        },
                        onClose = {
                            selectedGroupMemberId = null
                        },
                        modifier = compactSheetModifier
                    )
                }

                // Hoja de Grupo en Tiempo Real
                if (showGroupSheet) {
                    val shareableName = activeRoute?.name ?: controller.destinationRoadRoute?.destinationName
                    GroupSheet(
                        roomCode = groupRoomCode,
                        connectionState = groupConnectionState,
                        onlineConnected = groupOnlineConnected,
                        lanActive = groupLanActive,
                        members = groupMembers,
                        myStatus = groupMyStatus,
                        myStatusMessage = groupMyStatusMessage,
                        config = groupConfig,
                        sharedTrack = groupSharedTrack,
                        pendingJoinCode = pendingGroupJoinCode,
                        activeShareableRouteName = shareableName,
                        bikeMapConfig = bikeMapConfig,
                        riderCoord = rider.coordinate,
                        onCreateGroup = { controller.createGroup() },
                        onJoinGroup = { controller.joinGroup(it) },
                        onLeaveGroup = { controller.leaveGroup() },
                        onConsumePendingJoinCode = { controller.consumePendingGroupJoinCode() },
                        onUpdateProfile = { nick, clr -> controller.updateGroupProfile(nick, clr) },
                        onUpdateNetworkSettings = { saver, online, lan, broker ->
                            controller.updateGroupNetworkSettings(saver, online, lan, broker)
                        },
                        onSetMyStatus = { st, msg -> controller.setGroupRiderStatus(st, msg) },
                        onShareActiveRoute = { controller.shareActiveRouteWithGroup() },
                        onImportSharedTrack = { controller.importSharedGroupTrack() },
                        onDismissSharedTrack = { controller.dismissSharedGroupTrack() },
                        onCenterMember = { controller.centerOnGroupMember(it) },
                        onNavigateToMember = { controller.navigateToGroupMember(it) },
                        onFitGroupOnMap = { controller.fitGroupOnMap() },
                        onSetBikeGroupDisplayConfig = { showMembers, showNames, showEdgeRadar, showHudBadge, showAlerts ->
                            controller.setBikeGroupDisplayConfig(showMembers, showNames, showEdgeRadar, showHudBadge, showAlerts)
                        },
                        onClose = { showGroupSheet = false },
                        modifier = largeSheetModifier
                    )
                }

                // Diálogo de comprobación y descarga de actualizaciones
                if (showUpdateDialog) {
                    AppUpdateDialog(
                        onDismiss = { showUpdateDialog = false }
                    )
                }

                // Aviso flotante multiplataforma (usado en iOS cuando showPlatformToast se dispara)
                AnimatedVisibility(
                    visible = inAppToast != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp, vertical = 22.dp)
                        .zIndex(50f)
                ) {
                    val toastMsg = inAppToast?.message.orEmpty()
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xEE181D26))
                            .border(1.dp, EnduPalette.Accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = toastMsg,
                            color = Color.White,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                }
            }
        }
    }
}
