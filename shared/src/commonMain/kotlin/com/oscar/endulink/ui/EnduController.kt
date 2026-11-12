package com.oscar.endulink.ui

import com.oscar.endulink.bike.*
import com.oscar.endulink.platform.*
import com.oscar.endulink.tiles.*
import com.oscar.endulink.trail.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlin.math.roundToInt

enum class MapFollowMode {
    FREE,
    FOLLOW,
    FOLLOW_HEADING
}

enum class ApproachTarget {
    START,
    NEAREST,
    FINISH
}

class EnduController {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Telemetría GPS
    private val locationProvider = LocationProvider()
    val riderTelemetry: StateFlow<RiderTelemetry> = locationProvider.telemetryFlow

    // Estado del Mapa
    private val _followMode = MutableStateFlow(MapFollowMode.FOLLOW)
    val followMode: StateFlow<MapFollowMode> = _followMode.asStateFlow()

    // Almacenamiento y Renderizado de Teselas
    val tileStore = TileStore()
    private val _tileRevision = MutableStateFlow(0)
    val tileRevision: StateFlow<Int> = _tileRevision.asStateFlow()

    private val _selectedLayer = MutableStateFlow(MapLayer.CARRETERAS)
    val selectedLayer: StateFlow<MapLayer> = _selectedLayer.asStateFlow()

    private val _mapZoom = MutableStateFlow(15.0)
    val mapZoom: StateFlow<Double> = _mapZoom.asStateFlow()

    private val _mapCenter = MutableStateFlow(GeoCoord(40.4168, -3.7038)) // Madrid por defecto
    val mapCenter: StateFlow<GeoCoord> = _mapCenter.asStateFlow()

    private val _mapHeading = MutableStateFlow(0.0)
    val mapHeading: StateFlow<Double> = _mapHeading.asStateFlow()

    // Estado de Navegación Activa
    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

    // Destino Buscado y Opciones de Ruta por Carretera (OSRM)
    // NO se guardan en el gestor de tracks GPX
    private val _searchPin = MutableStateFlow<SearchResultItem?>(null)
    val searchPin: StateFlow<SearchResultItem?> = _searchPin.asStateFlow()

    // Chincheta / punto seleccionado en el mapa mediante pulsación larga
    private val _droppedPin = MutableStateFlow<SearchResultItem?>(null)
    val droppedPin: StateFlow<SearchResultItem?> = _droppedPin.asStateFlow()

    // Paradas intermedias en la ruta por carretera (estilo Google Maps multi-waypoint)
    private val _intermediateWaypoints = MutableStateFlow<List<SearchResultItem>>(emptyList())
    val intermediateWaypoints: StateFlow<List<SearchResultItem>> = _intermediateWaypoints.asStateFlow()

    private val _destinationRouteOptions = MutableStateFlow<List<CalculatedRoadRoute>>(emptyList())
    val destinationRouteOptions: StateFlow<List<CalculatedRoadRoute>> = _destinationRouteOptions.asStateFlow()

    private val _selectedDestinationOptionIndex = MutableStateFlow(0)
    val selectedDestinationOptionIndex: StateFlow<Int> = _selectedDestinationOptionIndex.asStateFlow()

    private val _isCalculatingDestinationRoute = MutableStateFlow(false)
    val isCalculatingDestinationRoute: StateFlow<Boolean> = _isCalculatingDestinationRoute.asStateFlow()

    val destinationRoadRoute: CalculatedRoadRoute?
        get() {
            val list = _destinationRouteOptions.value
            val idx = _selectedDestinationOptionIndex.value
            return if (idx in list.indices) list[idx] else list.firstOrNull()
        }

    // Enlace por carretera al track GPX
    private val _approachRoadRoute = MutableStateFlow<CalculatedRoadRoute?>(null)
    val approachRoadRoute: StateFlow<CalculatedRoadRoute?> = _approachRoadRoute.asStateFlow()

    private val _isCalculatingApproach = MutableStateFlow(false)
    val isCalculatingApproach: StateFlow<Boolean> = _isCalculatingApproach.asStateFlow()

    // Mensaje de estado flotante
    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    // Modo Rumbo Directo («As the Crow Flies»)
    private val _crowFliesModeActive = MutableStateFlow(false)
    val crowFliesModeActive: StateFlow<Boolean> = _crowFliesModeActive.asStateFlow()

    // Rutas GPX en biblioteca
    private val _routes = MutableStateFlow<List<GPXRoute>>(emptyList())
    val routes: StateFlow<List<GPXRoute>> = _routes.asStateFlow()

    private val _selectedRouteId = MutableStateFlow<String?>(null)
    val selectedRouteId: StateFlow<String?> = _selectedRouteId.asStateFlow()

    val selectedRoute: GPXRoute?
        get() {
            val id = _selectedRouteId.value ?: return null
            return _routes.value.firstOrNull { it.id == id }
        }

    val activeRoute: StateFlow<GPXRoute?>
        get() = _activeRouteFlow

    private val _activeRouteFlow = MutableStateFlow<GPXRoute?>(null)

    // Cursor del perfil de altitud
    private val _scrubbedPoint = MutableStateFlow<GPXPoint?>(null)
    val scrubbedPoint: StateFlow<GPXPoint?> = _scrubbedPoint.asStateFlow()

    // Progreso de navegación (DMD2)
    private val _trackProgress = MutableStateFlow<TrackProgress?>(null)
    val trackProgress: StateFlow<TrackProgress?> = _trackProgress.asStateFlow()

    // Marcadores rápidos
    private val _quickMarkers = MutableStateFlow<List<GPXWaypoint>>(emptyList())
    val quickMarkers: StateFlow<List<GPXWaypoint>> = _quickMarkers.asStateFlow()

    // Grabación REC
    private val trackRecorder = TrackRecorder()
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordedCoords = MutableStateFlow<List<GeoCoord>>(emptyList())
    val recordedCoords: StateFlow<List<GeoCoord>> = _recordedCoords.asStateFlow()

    private val _recordingStats = MutableStateFlow(Triple(0.0, 0.0, 0L))
    val recordingStats: StateFlow<Triple<Double, Double, Long>> = _recordingStats.asStateFlow()

    // Conexión Pantalla Moto (EasyConn / Carbit)
    private val bikeCrypto = BikeCrypto()
    private val _bikeState = MutableStateFlow(BikeState.CLOSED)
    val bikeState: StateFlow<BikeState> = _bikeState.asStateFlow()

    private val _bikeQr = MutableStateFlow<BikeQr?>(null)
    val bikeQr: StateFlow<BikeQr?> = _bikeQr.asStateFlow()

    private val _bikeLogs = MutableStateFlow<List<String>>(emptyList())
    val bikeLogs: StateFlow<List<String>> = _bikeLogs.asStateFlow()

    private val _encoderStats = MutableStateFlow(EncoderStats())
    val encoderStats: StateFlow<EncoderStats> = _encoderStats.asStateFlow()

    private var bikeLink: BikeLink? = null
    private var encoder: PlatformH264Encoder? = null
    private val bikeMapRenderer = BikeMapRenderer(tileStore)

    // Descargas Offline
    private val _offlineProgress = MutableStateFlow(OfflineDownloadProgress())
    val offlineProgress: StateFlow<OfflineDownloadProgress> = _offlineProgress.asStateFlow()

    // Pantalla Moto y Ajustes Independientes (1:1 con MotIOS)
    private val _bikeMapConfig = MutableStateFlow(BikeMapConfig())
    val bikeMapConfig: StateFlow<BikeMapConfig> = _bikeMapConfig.asStateFlow()

    private val _livePreviewBitmap = MutableStateFlow<androidx.compose.ui.graphics.ImageBitmap?>(null)
    val livePreviewBitmap: StateFlow<androidx.compose.ui.graphics.ImageBitmap?> = _livePreviewBitmap.asStateFlow()

    private val _allowLocalLanTesting = MutableStateFlow(false)
    val allowLocalLanTesting: StateFlow<Boolean> = _allowLocalLanTesting.asStateFlow()

    private val _wifiSnapshot = MutableStateFlow<WifiSnapshot?>(null)
    val wifiSnapshot: StateFlow<WifiSnapshot?> = _wifiSnapshot.asStateFlow()

    private var previewDecimator = 0
    private var routeCalcJob: Job? = null
    private var approachCalcJob: Job? = null
    private var lastApproachTarget = ApproachTarget.NEAREST

    // Compatibilidad para búsqueda
    val destination: StateFlow<SearchResultItem?> get() = _searchPin

    init {
        // Cargar ajustes, waypoints y tracks GPX persistidos en almacenamiento local
        val savedSettings = SettingsStore.loadSettings()
        _selectedLayer.value = MapLayer.fromRaw(savedSettings.selectedLayerRaw)
        _bikeMapConfig.value = savedSettings.bikeMapConfig
        _quickMarkers.value = WaypointStore.loadWaypoints()
        _routes.value = RouteStore.loadRoutes()

        tileStore.onTileLoaded = {
            _tileRevision.value++
        }

        // Monitor periódico de la red Wi-Fi de la pantalla y cronómetro REC en vivo
        scope.launch {
            var tick = 0
            while (isActive) {
                if (tick % 2 == 0) {
                    _wifiSnapshot.value = getWifiSnapshot()
                }
                if (_isRecording.value) {
                    _recordingStats.value = getRecordingStats()
                }
                tick++
                delay(1000)
            }
        }

        // Observador continuo de ubicación GPS
        scope.launch {
            locationProvider.telemetryFlow.collect { telemetry ->
                telemetry.coordinate?.let { pos ->
                    if (_followMode.value != MapFollowMode.FREE) {
                        _mapCenter.value = pos
                    }
                    if (_followMode.value == MapFollowMode.FOLLOW_HEADING && telemetry.hasValidHeading) {
                        _mapHeading.value = telemetry.headingDegrees
                    }

                    // Grabación REC
                    if (trackRecorder.isRecording) {
                        if (trackRecorder.addPoint(pos.latitude, pos.longitude, telemetry.altitudeM, telemetry.speedKmh)) {
                            _recordedCoords.value = trackRecorder.recordedPoints.map { it.coordinate }
                            _recordingStats.value = getRecordingStats()
                        }
                    }

                    // Transición automática del enlace por carretera al track GPX al llegar al punto de enlace
                    val currentApproach = _approachRoadRoute.value
                    val gpxRoute = selectedRoute
                    if (_isNavigating.value && currentApproach != null && gpxRoute != null) {
                        val distToApproachEnd = pos.distanceTo(currentApproach.destinationCoordinate)
                        val distToTrack = gpxRoute.points.minOfOrNull { it.coordinate.distanceTo(pos) } ?: Double.MAX_VALUE
                        if (distToApproachEnd <= 60.0 || distToTrack <= 45.0) {
                            _approachRoadRoute.value = null
                            _trackProgress.value = null
                            _statusBannerMessage.value = "Enlace completado · Siguiendo track ${gpxRoute.name}"
                        }
                    }

                    // Progreso de navegación sobre la ruta activa (Destino buscado, Enlace o GPX)
                    val navRoute = activeNavigationRoute
                    if (navRoute != null) {
                        _trackProgress.value = RouteNavigator.calculateProgress(
                            route = navRoute,
                            coord = pos,
                            lastSegmentIndex = _trackProgress.value?.nearestIndex
                        )
                    } else {
                        _trackProgress.value = null
                    }
                }
            }
        }
    }

    /** Ruta activa en navegación: Destino buscado > Enlace > GPX seleccionado */
    val activeNavigationRoute: GPXRoute?
        get() {
            destinationRoadRoute?.let { return it.asGPXRoute }
            _approachRoadRoute.value?.let { return it.asGPXRoute }
            return selectedRoute?.takeIf { it.isVisible }
        }

    val hasSelectableRouteToStart: Boolean
        get() = destinationRoadRoute != null || (selectedRoute?.isVisible == true)

    fun start() {
        locationProvider.startTracking()
        updateBackgroundKeepAlive()
    }

    private fun updateBackgroundKeepAlive() {
        val isBikeActive = _bikeState.value == BikeState.SHOWING || _bikeState.value == BikeState.GREETED
        val shouldKeepAlive = _isNavigating.value || _isRecording.value || isBikeActive
        if (shouldKeepAlive) {
            BackgroundServiceController.startKeepAlive()
        } else {
            BackgroundServiceController.stopKeepAlive()
        }
    }

    fun restartLocation() {
        locationProvider.stopTracking()
        locationProvider.startTracking()
    }

    fun stop() {
        locationProvider.stopTracking()
        stopBikeStream()
        BackgroundServiceController.stopKeepAlive()
    }

    fun zoomIn() {
        setZoom(_mapZoom.value + 1.0)
    }

    fun zoomOut() {
        setZoom(_mapZoom.value - 1.0)
    }

    fun setZoom(zoom: Double) {
        _mapZoom.value = minOf(maxOf(zoom, 3.0), 20.0)
    }

    fun setCenter(coord: GeoCoord) {
        _mapCenter.value = coord
    }

    fun setHeading(heading: Double) {
        _mapHeading.value = (heading + 360.0) % 360.0
    }

    fun resetNorth() {
        _mapHeading.value = 0.0
        if (_followMode.value == MapFollowMode.FOLLOW_HEADING) {
            _followMode.value = MapFollowMode.FOLLOW
        }
    }

    fun setFollowMode(mode: MapFollowMode) {
        _followMode.value = mode
        if (mode != MapFollowMode.FREE) {
            riderTelemetry.value.coordinate?.let { _mapCenter.value = it }
        }
        if (mode == MapFollowMode.FOLLOW_HEADING && riderTelemetry.value.hasValidHeading) {
            _mapHeading.value = riderTelemetry.value.headingDegrees
        }
    }

    fun toggleOrientation() {
        when (_followMode.value) {
            MapFollowMode.FREE, MapFollowMode.FOLLOW -> setFollowMode(MapFollowMode.FOLLOW_HEADING)
            MapFollowMode.FOLLOW_HEADING -> {
                resetNorth()
                setFollowMode(MapFollowMode.FOLLOW)
            }
        }
    }

    fun centerOnUserLocation() {
        val nextMode = when (_followMode.value) {
            MapFollowMode.FREE -> MapFollowMode.FOLLOW
            MapFollowMode.FOLLOW -> MapFollowMode.FOLLOW_HEADING
            MapFollowMode.FOLLOW_HEADING -> MapFollowMode.FOLLOW
        }
        _followMode.value = nextMode
        riderTelemetry.value.coordinate?.let {
            _mapCenter.value = it
        }
        if (nextMode == MapFollowMode.FOLLOW_HEADING && riderTelemetry.value.hasValidHeading) {
            _mapHeading.value = riderTelemetry.value.headingDegrees
        } else if (nextMode == MapFollowMode.FOLLOW) {
            _mapHeading.value = 0.0
        }
        // Auto-zoom suave al piloto si la vista estaba alejada (1:1 con MotIOS spanMeters: 420)
        if (_mapZoom.value < 15.0) {
            setZoom(15.5)
        }
    }

    fun setLayer(layer: MapLayer) {
        _selectedLayer.value = layer
        SettingsStore.saveSettings(layer, _bikeMapConfig.value)
    }

    fun toggleCrowFlies() {
        _crowFliesModeActive.value = !_crowFliesModeActive.value
    }

    fun dismissBanner() {
        _statusBannerMessage.value = null
    }

    // --- Búsqueda de Destinos y Rutas por Carretera (OSRM) ---

    fun selectSearchResult(item: SearchResultItem, keepWaypoints: Boolean = false) {
        // En MotIOS: seleccionar un destino desactiva el track GPX previo y calcula ruta
        _selectedRouteId.value = null
        _activeRouteFlow.value = null
        _approachRoadRoute.value = null
        _trackProgress.value = null
        _destinationRouteOptions.value = emptyList()
        _selectedDestinationOptionIndex.value = 0
        if (!keepWaypoints) {
            _intermediateWaypoints.value = emptyList()
        }
        _searchPin.value = item
        _followMode.value = MapFollowMode.FREE

        // Centrar temporalmente en el destino
        _mapCenter.value = item.coordinate

        calculateRouteToSearchPin(item)
    }

    /**
     * Marcado de un punto en el mapa tras una pulsación larga del usuario.
     */
    fun onMapLongPress(coord: GeoCoord) {
        val riderPos = riderTelemetry.value.coordinate
        val initialDist = riderPos?.let { it.distanceTo(coord) / 1000.0 }
        val initialItem = SearchResultItem(
            title = "Punto seleccionado",
            subtitle = PlaceSearchEngine.formatCoord(coord),
            coordinate = coord,
            distanceKm = initialDist
        )
        _droppedPin.value = initialItem

        // Geocodificación inversa en segundo plano
        scope.launch {
            val resolved = PlaceSearchEngine.reverseGeocode(coord, riderPos)
            if (_droppedPin.value?.coordinate == coord) {
                _droppedPin.value = resolved
            }
        }
    }

    fun clearDroppedPin() {
        _droppedPin.value = null
    }

    fun setDroppedPinAsDestination() {
        val pin = _droppedPin.value ?: return
        _droppedPin.value = null
        selectSearchResult(pin)
    }

    fun addDroppedPinAsStop() {
        val pin = _droppedPin.value ?: return
        _droppedPin.value = null
        addIntermediateWaypoint(pin)
    }

    /**
     * Añade una parada intermedia a la ruta activa (estilo Google Maps).
     */
    fun addIntermediateWaypoint(item: SearchResultItem) {
        val current = _intermediateWaypoints.value.toMutableList()
        current.add(item)
        _intermediateWaypoints.value = current

        val dest = _searchPin.value
        val route = selectedRoute
        if (dest != null) {
            calculateRouteToSearchPin(dest)
        } else if (route != null) {
            calculateApproachToGPX(target = lastApproachTarget, autoStart = false)
        } else {
            // Si aún no había destino ni track, este primer punto pasa a ser el destino
            selectSearchResult(item)
        }
    }

    fun removeIntermediateWaypoint(index: Int) {
        val current = _intermediateWaypoints.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _intermediateWaypoints.value = current
            val dest = _searchPin.value
            val route = selectedRoute
            if (dest != null) {
                calculateRouteToSearchPin(dest)
            } else if (route != null) {
                if (_intermediateWaypoints.value.isEmpty()) {
                    _approachRoadRoute.value = null
                } else {
                    calculateApproachToGPX(target = lastApproachTarget, autoStart = false)
                }
            }
        }
    }

    fun calculateRouteToSearchPin(dest: SearchResultItem) {
        routeCalcJob?.cancel()
        val origin = riderTelemetry.value.coordinate ?: _mapCenter.value
        val waypoints = _intermediateWaypoints.value.map { it.coordinate }
        _isCalculatingDestinationRoute.value = true

        routeCalcJob = scope.launch {
            try {
                val routes = RoadRouter.calculateMultiWaypointRoute(
                    origin = origin,
                    waypoints = waypoints,
                    destination = dest.coordinate,
                    destinationName = dest.title,
                    requestAlternatives = waypoints.isEmpty(), // Si hay paradas, calculamos la ruta precisa que las enlaza
                    color = TrackColor.CYAN
                )
                _destinationRouteOptions.value = routes
                _selectedDestinationOptionIndex.value = 0
                _trackProgress.value = null
                _isCalculatingDestinationRoute.value = false

                // Encuadrar la ruta en el mapa
                val first = routes.firstOrNull()
                if (first != null) {
                    fitCoordinates(first.coordinates)
                    val stopsMsg = if (waypoints.isNotEmpty()) " (${waypoints.size} parada${if (waypoints.size > 1) "s" else ""})" else ""
                    _statusBannerMessage.value = "Ruta a ${dest.title}$stopsMsg: ${(first.distanceKm * 10.0).roundToInt() / 10.0} km (~${first.expectedMinutes} min)"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _isCalculatingDestinationRoute.value = false
                _statusBannerMessage.value = "No se pudo calcular ruta: ${e.message}"
            }
        }
    }

    fun selectDestinationAlternative(index: Int) {
        if (index in _destinationRouteOptions.value.indices) {
            _selectedDestinationOptionIndex.value = index
            _trackProgress.value = null
        }
    }

    fun clearSearchAndDestinationRoute() {
        routeCalcJob?.cancel()
        _isCalculatingDestinationRoute.value = false
        _searchPin.value = null
        _droppedPin.value = null
        _intermediateWaypoints.value = emptyList()
        _destinationRouteOptions.value = emptyList()
        _selectedDestinationOptionIndex.value = 0
        _trackProgress.value = null
        if (_isNavigating.value && selectedRoute == null) {
            stopNavigation()
        }
    }

    fun clearDestination() {
        clearSearchAndDestinationRoute()
    }

    // --- Navegación Activa (Iniciar / Parar) ---

    fun requestStartNavigation() {
        if (destinationRoadRoute != null || _approachRoadRoute.value != null) {
            startNavigation()
            return
        }
        val route = selectedRoute
        val gps = riderTelemetry.value.coordinate
        if (route != null && route.isVisible && gps != null) {
            val offMeters = route.points.minOfOrNull { it.coordinate.distanceTo(gps) } ?: 0.0
            if (offMeters > 250.0) {
                // Ofrecer enlace si está lejos
                calculateApproachToGPX(toNearestPoint = true, autoStart = true)
                return
            }
        }
        startNavigation()
    }

    fun startNavigation() {
        _isNavigating.value = true
        updateBackgroundKeepAlive()
        setFollowMode(MapFollowMode.FOLLOW)
        riderTelemetry.value.coordinate?.let {
            _mapCenter.value = it
            setZoom(16.0)
        }
    }

    fun stopNavigation() {
        _isNavigating.value = false
        updateBackgroundKeepAlive()
        setFollowMode(MapFollowMode.FREE)
    }

    // --- Enlace a Track GPX ---

    fun calculateApproachToGPX(
        toNearestPoint: Boolean = true,
        autoStart: Boolean = false,
        target: ApproachTarget = if (toNearestPoint) ApproachTarget.NEAREST else ApproachTarget.START
    ) {
        val route = selectedRoute ?: return
        if (route.points.isEmpty()) return
        lastApproachTarget = target
        val gps = riderTelemetry.value.coordinate ?: _mapCenter.value
        val targetCoord = when (target) {
            ApproachTarget.START -> route.points.first().coordinate
            ApproachTarget.FINISH -> route.points.last().coordinate
            ApproachTarget.NEAREST -> route.points.minByOrNull { it.coordinate.distanceTo(gps) }?.coordinate ?: route.points.first().coordinate
        }
        val destLabel = when (target) {
            ApproachTarget.START -> "Inicio de ${route.name}"
            ApproachTarget.FINISH -> "Final de ${route.name}"
            ApproachTarget.NEAREST -> "Enlace a ${route.name}"
        }

        approachCalcJob?.cancel()
        _isCalculatingApproach.value = true
        approachCalcJob = scope.launch {
            try {
                val waypoints = _intermediateWaypoints.value.map { it.coordinate }
                val routes = RoadRouter.calculateMultiWaypointRoute(
                    origin = gps,
                    waypoints = waypoints,
                    destination = targetCoord,
                    destinationName = destLabel,
                    requestAlternatives = false,
                    color = TrackColor.CYAN,
                    customSingleLabel = "Enlace"
                )
                _approachRoadRoute.value = routes.firstOrNull()
                _trackProgress.value = null
                _isCalculatingApproach.value = false

                if (autoStart) {
                    startNavigation()
                } else {
                    val allCoords = (routes.firstOrNull()?.coordinates ?: emptyList()) + route.points.map { it.coordinate }
                    fitCoordinates(allCoords)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _isCalculatingApproach.value = false
                _statusBannerMessage.value = "Error al calcular enlace: ${e.message}"
                if (autoStart) startNavigation()
            }
        }
    }

    fun clearApproachRoute() {
        approachCalcJob?.cancel()
        _isCalculatingApproach.value = false
        _approachRoadRoute.value = null
        _trackProgress.value = null
    }

    // --- Rutas GPX ---

    fun addRoute(route: GPXRoute) {
        val current = _routes.value.toMutableList()
        current.add(route)
        _routes.value = current
        RouteStore.saveRoutes(current)
    }

    fun shareRoute(route: GPXRoute) {
        shareGPXFile(route.name, route.toGPXXml())
    }

    fun addAndActivateRoute(route: GPXRoute) {
        addRoute(route)
        selectRoute(route)
    }

    fun importGPXContent(name: String, content: String) {
        try {
            val route = GPXParser.parse(content, fallbackName = name)
            addRoute(route)
            selectRoute(route)
            fitCoordinates(route.points.map { it.coordinate })
            _statusBannerMessage.value = "Track importado: ${route.name} (${(route.totalDistanceKm * 10.0).roundToInt() / 10.0} km)"
        } catch (e: Exception) {
            _statusBannerMessage.value = "Error importando GPX: ${e.message}"
        }
    }

    fun loadSampleRoute(name: String, gpxXml: String) {
        try {
            val route = GPXParser.parse(gpxXml, fallbackName = "$name.gpx")
            addRoute(route)
            if (_selectedRouteId.value == null) {
                selectRoute(route)
            }
        } catch (_: Exception) {}
    }

    fun selectRoute(route: GPXRoute) {
        // Al seleccionar un GPX, limpiamos el destino de búsqueda
        clearSearchAndDestinationRoute()
        approachCalcJob?.cancel()
        _isCalculatingApproach.value = false
        _approachRoadRoute.value = null
        _trackProgress.value = null
        _selectedRouteId.value = route.id
        _activeRouteFlow.value = route
        fitCoordinates(route.points.map { it.coordinate })
    }

    fun deselectRoute() {
        approachCalcJob?.cancel()
        _isCalculatingApproach.value = false
        _selectedRouteId.value = null
        _activeRouteFlow.value = null
        _approachRoadRoute.value = null
        _trackProgress.value = null
        if (_isNavigating.value && destinationRoadRoute == null) {
            stopNavigation()
        }
    }

    fun toggleRouteVisibility(route: GPXRoute) {
        val updated = route.copy(isVisible = !route.isVisible)
        _routes.value = _routes.value.map {
            if (it.id == route.id) updated else it
        }
        RouteStore.saveRoutes(_routes.value)
        if (_selectedRouteId.value == route.id) {
            _activeRouteFlow.value = updated
            if (!updated.isVisible && _isNavigating.value && destinationRoadRoute == null && _approachRoadRoute.value == null) {
                stopNavigation()
            }
        }
    }

    fun invertRoute(route: GPXRoute) {
        val inverted = route.reversedRoute()
        _routes.value = _routes.value.map {
            if (it.id == route.id) inverted else it
        }
        RouteStore.saveRoutes(_routes.value)
        if (_selectedRouteId.value == route.id) {
            _activeRouteFlow.value = inverted
            _trackProgress.value = null
        }
    }

    fun cycleRouteColor(route: GPXRoute) {
        val nextCol = route.color.nextColor
        val updated = route.copy(color = nextCol)
        _routes.value = _routes.value.map {
            if (it.id == route.id) updated else it
        }
        RouteStore.saveRoutes(_routes.value)
        if (_selectedRouteId.value == route.id) {
            _activeRouteFlow.value = updated
        }
    }

    fun deleteRoute(route: GPXRoute) {
        if (_selectedRouteId.value == route.id) {
            deselectRoute()
        }
        _routes.value = _routes.value.filter { it.id != route.id }
        RouteStore.saveRoutes(_routes.value)
    }

    fun fitActiveContentOnMap() {
        val coords = mutableListOf<GeoCoord>()
        destinationRoadRoute?.let { coords.addAll(it.coordinates) }
        _approachRoadRoute.value?.let { coords.addAll(it.coordinates) }
        selectedRoute?.takeIf { it.isVisible }?.let { coords.addAll(it.points.map { p -> p.coordinate }) }
        if (coords.isEmpty()) {
            _routes.value.filter { it.isVisible }.forEach { r -> coords.addAll(r.points.map { p -> p.coordinate }) }
        }
        if (coords.isNotEmpty()) {
            fitCoordinates(coords)
        }
    }

    fun fitCoordinates(coords: List<GeoCoord>) {
        if (coords.isEmpty()) return
        val minLat = coords.minOf { it.latitude }
        val maxLat = coords.maxOf { it.latitude }
        val minLon = coords.minOf { it.longitude }
        val maxLon = coords.maxOf { it.longitude }

        _mapCenter.value = GeoCoord((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0)
        _followMode.value = MapFollowMode.FREE

        val dLat = maxOf(0.005, maxLat - minLat)
        val dLon = maxOf(0.005, maxLon - minLon)
        val maxSpan = maxOf(dLat, dLon)

        val targetZoom = when {
            maxSpan > 2.0 -> 8.0
            maxSpan > 1.0 -> 9.0
            maxSpan > 0.5 -> 10.5
            maxSpan > 0.2 -> 12.0
            maxSpan > 0.08 -> 13.5
            maxSpan > 0.03 -> 14.5
            else -> 15.5
        }
        setZoom(targetZoom)
    }

    fun setScrubbedPoint(point: GPXPoint?) {
        _scrubbedPoint.value = point
        if (point != null) {
            _mapCenter.value = point.coordinate
        }
    }

    // --- Grabación REC ---

    fun startRecording() {
        trackRecorder.start()
        _isRecording.value = true
        _recordedCoords.value = emptyList()
        _recordingStats.value = Triple(0.0, 0.0, 0L)
        updateBackgroundKeepAlive()
    }

    fun getRecordingStats(): Triple<Double, Double, Long> {
        return Triple(trackRecorder.totalDistanceKm, trackRecorder.elevationGainM, trackRecorder.durationSeconds)
    }

    fun saveRecordedTrack(customName: String) {
        val pts = trackRecorder.stop()
        _isRecording.value = false
        _recordingStats.value = Triple(0.0, 0.0, 0L)
        updateBackgroundKeepAlive()
        if (pts.size >= 2) {
            val gpxText = trackRecorder.toGPXString(customName)
            try {
                val route = GPXParser.parse(gpxText, fallbackName = "$customName.gpx")
                addRoute(route)
                selectRoute(route)
                _statusBannerMessage.value = "Track guardado: ${route.name} (${(route.totalDistanceKm * 10.0).roundToInt() / 10.0} km)"
            } catch (_: Exception) {}
        } else {
            _statusBannerMessage.value = "Grabación descartada: menos de 2 puntos GPS registrados"
        }
        _recordedCoords.value = emptyList()
    }

    fun discardRecordedTrack() {
        trackRecorder.reset()
        _isRecording.value = false
        _recordingStats.value = Triple(0.0, 0.0, 0L)
        updateBackgroundKeepAlive()
        _recordedCoords.value = emptyList()
    }

    // --- Gestión Integral de Waypoints ---

    fun addWaypoint(
        name: String,
        symbol: String,
        coord: GeoCoord? = null,
        elevation: Double? = null
    ): GPXWaypoint {
        val pos = coord ?: riderTelemetry.value.coordinate ?: _mapCenter.value
        val now = Clock.System.now().toEpochMilliseconds()
        val marker = GPXWaypoint(
            id = "wpt_${now}_${(100..999).random()}",
            name = name.ifBlank { "Punto ${_quickMarkers.value.size + 1}" },
            latitude = pos.latitude,
            longitude = pos.longitude,
            elevation = elevation ?: riderTelemetry.value.altitudeM,
            symbol = symbol,
            createdAtEpochMs = now
        )
        val current = _quickMarkers.value.toMutableList()
        current.add(marker)
        _quickMarkers.value = current
        WaypointStore.saveWaypoints(current)
        return marker
    }

    fun addQuickMarker(name: String, symbol: String) {
        addWaypoint(name, symbol)
    }

    fun updateWaypoint(id: String, newName: String, newSymbol: String) {
        val updated = _quickMarkers.value.map { wpt ->
            if (wpt.id == id) wpt.copy(name = newName, symbol = newSymbol) else wpt
        }
        _quickMarkers.value = updated
        WaypointStore.saveWaypoints(updated)
    }

    fun deleteWaypoint(id: String) {
        val updated = _quickMarkers.value.filter { it.id != id }
        _quickMarkers.value = updated
        WaypointStore.saveWaypoints(updated)
    }

    fun navigateToWaypoint(wpt: GPXWaypoint) {
        val riderPos = riderTelemetry.value.coordinate
        val dist = riderPos?.let { it.distanceTo(wpt.coordinate) / 1000.0 }
        val latStr = (wpt.latitude * 10000.0).roundToInt() / 10000.0
        val lonStr = (wpt.longitude * 10000.0).roundToInt() / 10000.0
        val item = SearchResultItem(
            title = wpt.name,
            subtitle = "$latStr, $lonStr",
            coordinate = wpt.coordinate,
            distanceKm = dist
        )
        selectSearchResult(item)
    }

    fun addWaypointAsStop(wpt: GPXWaypoint) {
        val riderPos = riderTelemetry.value.coordinate
        val dist = riderPos?.let { it.distanceTo(wpt.coordinate) / 1000.0 }
        val latStr = (wpt.latitude * 10000.0).roundToInt() / 10000.0
        val lonStr = (wpt.longitude * 10000.0).roundToInt() / 10000.0
        val item = SearchResultItem(
            title = wpt.name,
            subtitle = "$latStr, $lonStr",
            coordinate = wpt.coordinate,
            distanceKm = dist
        )
        addIntermediateWaypoint(item)
    }

    fun centerOnWaypoint(wpt: GPXWaypoint) {
        _mapCenter.value = wpt.coordinate
        _followMode.value = MapFollowMode.FREE
        setZoom(16.0)
    }

    // --- Pantalla Moto (Streaming H.264 / EasyConn) ---

    fun startBikeStream() {
        if (bikeLink?.isOpen == true) return

        val card = PhoneCard(
            uuid = bikeCrypto.uuid,
            brand = "Google",
            model = "Pixel",
            osVersion = "14",
            bluetoothName = "EnduLink",
            screenWidth = 1080,
            screenHeight = 2400,
            publicKey = bikeCrypto.publicKeyBase64,
            sign = { bikeCrypto.sign(it) }
        )

        val enc = PlatformH264Encoder(
            customBitrateKbps = { _bikeMapConfig.value.bitrateKbps },
            renderFrameRgba = { w, h -> renderTftFrame(w, h) },
            onStats = { stats -> _encoderStats.value = stats },
            log = { logBike(it) }
        )
        encoder = enc

        val events = object : BikeEvents {
            override fun log(line: String) = logBike(line)
            override fun state(state: BikeState) {
                _bikeState.value = state
                updateBackgroundKeepAlive()
            }
            override fun gone(why: String) {
                logBike("Sesión finalizada: $why")
                updateBackgroundKeepAlive()
            }
        }

        val link = BikeLink(card = card, picture = enc, events = events)
        bikeLink = link
        link.open()
        refreshStaticPreview()

        scope.launch {
            val snap = getWifiSnapshot()
            if (snap != null) {
                _wifiSnapshot.value = snap
            }
            val candidateIp = snap?.gatewayCandidateIP ?: "192.168.43.1"
            if (link.isOpen && _bikeState.value == BikeState.LISTENING) {
                logBike("Llamando a pantalla TFT (knock) en $candidateIp:10930...")
                link.knock(candidateIp)
                if (candidateIp != "192.168.43.1" && link.isOpen && _bikeState.value == BikeState.LISTENING) {
                    link.knock("192.168.43.1")
                }
            }
        }
    }

    fun stopBikeStream() {
        bikeLink?.close()
        bikeLink = null
        encoder?.stop()
        encoder = null
        _bikeState.value = BikeState.CLOSED
        updateBackgroundKeepAlive()
    }

    fun knockBike(bikeIP: String) {
        scope.launch {
            logBike("Llamando a la moto (knock) en $bikeIP:10930...")
            val ok = bikeLink?.knock(bikeIP) ?: false
            logBike(if (ok) "La moto ha aceptado la llamada" else "La llamada knock no tuvo respuesta")
        }
    }

    fun saveScannedQr(text: String): Boolean {
        val parsed = BikeQr.parse(text)
        if (parsed != null) {
            _bikeQr.value = parsed
            logBike("QR reconocido: ${parsed.name} (SSID: ${parsed.ssid})")
            _statusBannerMessage.value = "QR reconocido: Red «${parsed.ssid}»"
            return true
        }
        return false
    }

    fun connectViaQr() {
        startBikeStream()
        knockBike("192.168.43.1")
    }

    private fun renderTftFrame(width: Int, height: Int): ByteArray? {
        val activeRoad = destinationRoadRoute ?: _approachRoadRoute.value
        val remKm = _trackProgress.value?.remainingKm ?: activeRoad?.distanceKm ?: selectedRoute?.totalDistanceKm
        val nextStep = activeRoad?.currentInstruction(riderTelemetry.value.coordinate)
        val stops = _intermediateWaypoints.value.map { it.coordinate }
        val wpts = _quickMarkers.value

        // Actualizar preview en directo periódicamente (~1.5 veces por segundo)
        if (++previewDecimator % 10 == 0) {
            scope.launch(Dispatchers.Default) {
                val preview = bikeMapRenderer.renderPreview(
                    width = 296,
                    height = 408,
                    config = _bikeMapConfig.value,
                    rider = riderTelemetry.value,
                    layer = _selectedLayer.value,
                    activeRoute = selectedRoute,
                    roadRouteCoordinates = activeRoad?.coordinates,
                    isNavigating = _isNavigating.value,
                    remainingKm = remKm,
                    nextInstruction = nextStep,
                    waypoints = wpts,
                    intermediateStops = stops
                )
                if (preview != null) {
                    _livePreviewBitmap.value = preview
                }
            }
        }

        return bikeMapRenderer.renderRgba(
            width = width,
            height = height,
            config = _bikeMapConfig.value,
            rider = riderTelemetry.value,
            layer = _selectedLayer.value,
            activeRoute = selectedRoute,
            roadRouteCoordinates = activeRoad?.coordinates,
            isNavigating = _isNavigating.value,
            remainingKm = remKm,
            nextInstruction = nextStep,
            waypoints = wpts,
            intermediateStops = stops
        )
    }

    fun setBikeZoom(zoom: Int) {
        _bikeMapConfig.value = _bikeMapConfig.value.copy(zoom = zoom.coerceIn(8, 18))
        SettingsStore.saveSettings(_selectedLayer.value, _bikeMapConfig.value)
        refreshStaticPreview()
    }

    fun setBikeOrientation(orientation: String) {
        _bikeMapConfig.value = _bikeMapConfig.value.copy(orientation = orientation)
        SettingsStore.saveSettings(_selectedLayer.value, _bikeMapConfig.value)
        refreshStaticPreview()
    }

    fun setBikeTileMagnification(magnification: Double) {
        _bikeMapConfig.value = _bikeMapConfig.value.copy(tileMagnification = magnification)
        SettingsStore.saveSettings(_selectedLayer.value, _bikeMapConfig.value)
        refreshStaticPreview()
    }

    fun setBikeAutoZoomBySpeed(enabled: Boolean) {
        _bikeMapConfig.value = _bikeMapConfig.value.copy(autoZoomBySpeed = enabled)
        SettingsStore.saveSettings(_selectedLayer.value, _bikeMapConfig.value)
        refreshStaticPreview()
    }

    fun setBikeBitrate(bitrateKbps: Int) {
        _bikeMapConfig.value = _bikeMapConfig.value.copy(bitrateKbps = bitrateKbps)
        SettingsStore.saveSettings(_selectedLayer.value, _bikeMapConfig.value)
    }

    fun setAllowLocalLanTesting(allow: Boolean) {
        _allowLocalLanTesting.value = allow
    }

    fun refreshStaticPreview() {
        scope.launch(Dispatchers.Default) {
            val activeRoad = destinationRoadRoute ?: _approachRoadRoute.value
            val remKm = _trackProgress.value?.remainingKm ?: activeRoad?.distanceKm ?: selectedRoute?.totalDistanceKm
            val nextStep = activeRoad?.currentInstruction(riderTelemetry.value.coordinate)
            val stops = _intermediateWaypoints.value.map { it.coordinate }
            val wpts = _quickMarkers.value

            val preview = bikeMapRenderer.renderPreview(
                width = 296,
                height = 408,
                config = _bikeMapConfig.value,
                rider = riderTelemetry.value,
                layer = _selectedLayer.value,
                activeRoute = selectedRoute,
                roadRouteCoordinates = activeRoad?.coordinates,
                isNavigating = _isNavigating.value,
                remainingKm = remKm,
                nextInstruction = nextStep,
                waypoints = wpts,
                intermediateStops = stops
            )
            if (preview != null) {
                _livePreviewBitmap.value = preview
            }
        }
    }

    // --- Descarga Offline ---

    fun downloadRegion(preset: OfflineRegionPreset, layer: MapLayer) {
        val bbox = MapBoundingBox(preset.minLat, preset.maxLat, preset.minLon, preset.maxLon)
        val packageKey = "${preset.id}_${layer.raw}"
        tileStore.downloadBoundingBox(
            bbox = bbox,
            layer = layer,
            minZ = preset.minZoom,
            maxZ = preset.maxZoom,
            label = "${preset.name} (${layer.shortBadge})",
            packageKey = packageKey,
            onProgress = { _offlineProgress.value = it }
        )
    }

    fun downloadRouteCorridor(route: GPXRoute) {
        val targetLayer = if (_selectedLayer.value == MapLayer.IGN_TOPO) MapLayer.IGN_TOPO else MapLayer.TRAIL_OSM
        _statusBannerMessage.value = "Descargando mapa offline del track «${route.name}» (${targetLayer.shortBadge})..."
        tileStore.downloadCorridor(
            route = route,
            layer = targetLayer,
            onProgress = { prog ->
                _offlineProgress.value = prog
                if (!prog.isDownloading && prog.total > 0 && prog.completed > 0) {
                    _statusBannerMessage.value = "Mapa offline del track listo: ${route.name} (${prog.completed} teselas)"
                }
            }
        )
    }

    fun clearBikeLogs() {
        _bikeLogs.value = emptyList()
    }

    private fun logBike(line: String) {
        val current = _bikeLogs.value.toMutableList()
        current.add(line)
        if (current.size > 200) current.removeAt(0)
        _bikeLogs.value = current
    }

    companion object {
        val shared by lazy { EnduController() }
    }
}
