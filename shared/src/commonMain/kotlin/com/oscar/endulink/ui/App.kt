package com.oscar.endulink.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.oscar.endulink.platform.PlatformBackHandler
import com.oscar.endulink.platform.closeApp
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
    val offlineProgress by controller.offlineProgress.collectAsState()
    val downloadedPackages by controller.tileStore.downloadedPackages.collectAsState()

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

                // 1. MAPA INTERACTIVO A PANTALLA COMPLETA
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
                    isPickingOnMap = isPickingOnMap,
                    onSelectWaypoint = { wpt ->
                        selectedWaypointForDetail = wpt
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 2. HUD SUPERIOR, CONTROLES DERECHA Y BARRA INFERIOR MOTIOS
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
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 52.dp, start = 12.dp, end = 12.dp)
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
                        modifier = Modifier.align(Alignment.BottomCenter)
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
                            val rPos = rider.coordinate
                            val startPt = route.points.firstOrNull()?.coordinate
                            if (rPos != null && startPt != null) {
                                val dist = rPos.distanceTo(startPt)
                                if (dist > 300.0) {
                                    pendingApproachRoute = route
                                    pendingApproachDistance = dist
                                    showRoutesSheet = false
                                    return@RoutesDrawer
                                }
                            }
                            controller.selectRoute(route)
                            showRoutesSheet = false
                        },
                        onToggleVisibility = { controller.toggleRouteVisibility(it) },
                        onInvertRoute = { controller.invertRoute(it) },
                        onCycleColor = { controller.cycleRouteColor(it) },
                        onOpenElevation = { route ->
                            controller.selectRoute(route)
                            showRoutesSheet = false
                            showElevationSheet = true
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
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                if (showOfflineSheet) {
                    OfflineMapsSheet(
                        selectedLayer = layer,
                        onSelectLayer = { controller.setLayer(it) },
                        downloadedPackages = downloadedPackages,
                        progress = offlineProgress,
                        onDownloadRegion = { preset, targetLayer -> controller.downloadRegion(preset, targetLayer) },
                        onRemovePackage = { key -> controller.tileStore.removePackage(key) },
                        onCancelDownload = { controller.tileStore.cancelDownload() },
                        onClearCache = { controller.tileStore.clearDiskCache() },
                        onClose = { showOfflineSheet = false },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                if (showBikeSheet) {
                    LaunchedEffect(Unit) {
                        controller.refreshStaticPreview()
                    }
                    BikeConnectionSheet(
                        state = bikeState,
                        bikeQr = bikeQr,
                        stats = encoderStats,
                        logs = bikeLogs,
                        config = bikeMapConfig,
                        livePreviewBitmap = livePreviewBitmap,
                        allowLocalLanTesting = allowLocalLanTesting,
                        wifiSnapshot = wifiSnapshot,
                        onSetZoom = { controller.setBikeZoom(it) },
                        onSetOrientation = { controller.setBikeOrientation(it) },
                        onSetTileMagnification = { controller.setBikeTileMagnification(it) },
                        onSetAutoZoomBySpeed = { controller.setBikeAutoZoomBySpeed(it) },
                        onSetBitrate = { controller.setBikeBitrate(it) },
                        onSetAllowLocalLanTesting = { controller.setAllowLocalLanTesting(it) },
                        onSaveQr = { controller.saveScannedQr(it) },
                        onStartStream = { controller.startBikeStream() },
                        onStopStream = { controller.stopBikeStream() },
                        onKnock = { ip -> controller.knockBike(ip) },
                        onClearLogs = { controller.clearBikeLogs() },
                        onClose = { showBikeSheet = false },
                        modifier = Modifier.align(Alignment.BottomCenter)
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
                        modifier = Modifier.align(Alignment.BottomCenter)
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
                        modifier = Modifier.align(Alignment.BottomCenter)
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

                // Diálogo de comprobación y descarga de actualizaciones
                if (showUpdateDialog) {
                    AppUpdateDialog(
                        onDismiss = { showUpdateDialog = false }
                    )
                }
            }
        }
    }
}
