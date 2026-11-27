package com.oscar.endulink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oscar.endulink.group.GroupMember
import com.oscar.endulink.group.GroupTftLayout
import com.oscar.endulink.group.RiderStatus
import com.oscar.endulink.platform.RiderTelemetry
import com.oscar.endulink.tiles.*
import com.oscar.endulink.trail.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

@Composable
fun InteractiveMap(
    controller: EnduController,
    layer: MapLayer,
    zoom: Double,
    center: GeoCoord,
    heading: Double,
    rider: RiderTelemetry,
    activeRoute: GPXRoute?,
    secondaryRoutes: List<GPXRoute>,
    recordedCoords: List<GeoCoord>,
    quickMarkers: List<GPXWaypoint>,
    trackProgress: TrackProgress?,
    groupMembers: List<GroupMember> = emptyList(),
    isOffGpxTrack: Boolean = false,
    isPickingOnMap: Boolean = false,
    displaySettings: AppDisplaySettings = AppDisplaySettings(),
    onSelectWaypoint: (GPXWaypoint) -> Unit = {},
    onSelectRoute: (GPXRoute) -> Unit = {},
    onSelectGroupMember: (GroupMember) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Escuchar revisiones de teselas descargadas para repintado reactivo inmediato
    val tileRevision by controller.tileRevision.collectAsState()
    val searchPin by controller.searchPin.collectAsState()
    val droppedPin by controller.droppedPin.collectAsState()
    val intermediateWaypoints by controller.intermediateWaypoints.collectAsState()
    val roadOptions by controller.destinationRouteOptions.collectAsState()
    val selectedOptionIdx by controller.selectedDestinationOptionIndex.collectAsState()
    val approachRoad by controller.approachRoadRoute.collectAsState()
    val crowFliesActive by controller.crowFliesModeActive.collectAsState()
    val scrubbedPoint by controller.scrubbedPoint.collectAsState()
    val textMeasurer = rememberTextMeasurer()
    val mapFontScale = displaySettings.hudFontScale.coerceIn(0.9f, 1.8f)
    val pinScale = 1f + (mapFontScale - 1f) * 0.45f
    val waypointLabelStyle = remember(mapFontScale) {
        TextStyle(
            color = Color.White,
            fontSize = (10f * mapFontScale).sp,
            fontWeight = FontWeight.Bold
        )
    }
    val groupLabelStyle = remember(mapFontScale) {
        TextStyle(
            color = Color.White,
            fontSize = (10f * mapFontScale.coerceAtMost(1.45f)).sp,
            fontWeight = FontWeight.Bold
        )
    }
    val labelLayoutCache = remember(mapFontScale) { HashMap<String, TextLayoutResult>() }
    val groupLabelLayoutCache = remember(mapFontScale) { HashMap<String, TextLayoutResult>() }
    val routeDrawPath = remember { Path() }
    val chevronDrawPath = remember { Path() }
    val edgeHitTargets = remember { mutableListOf<MobileGroupEdgeHitTarget>() }
    val currentOnSelectWaypoint by rememberUpdatedState(onSelectWaypoint)
    val currentOnSelectRoute by rememberUpdatedState(onSelectRoute)
    val currentOnSelectGroupMember by rememberUpdatedState(onSelectGroupMember)
    val currentIsPickingOnMap by rememberUpdatedState(isPickingOnMap)
    val currentLayer by rememberUpdatedState(layer)
    val currentPinScale by rememberUpdatedState(pinScale)
    val useNativeBackdrop = isNativeAppleMapsSupported() && layer == MapLayer.CARRETERAS
    val density = LocalDensity.current.density.toDouble().coerceAtLeast(1.0)
    val baseTileDp = if (layer.isRetina512) 148.dp else 132.dp
    val baseTilePoints = with(LocalDensity.current) {
        maxOf(256.0, baseTileDp.toPx().toDouble()) / density
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (useNativeBackdrop) {
            NativeMapBackdrop(
                layer = layer,
                center = center,
                zoom = zoom,
                heading = heading,
                baseTilePoints = baseTilePoints,
                modifier = Modifier.fillMaxSize()
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                detectMapInteractions(
                    onTap = { tapOffset ->
                        if (currentIsPickingOnMap) return@detectMapInteractions
                        val screenCenterX = size.width / 2.0
                        val screenCenterY = size.height / 2.0
                        val curHeading = controller.mapHeading.value
                        val curZoom = controller.mapZoom.value
                        val curCenter = controller.mapCenter.value
                        val routeWpts = controller.activeRoute.value?.takeIf { it.isVisible }?.waypoints.orEmpty()
                        val curMarkers = routeWpts + controller.quickMarkers.value
                        val rad = -curHeading * PI / 180.0
                        val zInt = curZoom.toInt()
                        val baseTileDp = if (currentLayer.isRetina512) 148.dp else 132.dp
                        val baseTilePx = maxOf(256.0, baseTileDp.toPx().toDouble())
                        val tileSizePx = baseTilePx * (2.0.pow(curZoom - zInt))
                        val (centerFracX, centerFracY) = TileMath.latLonToFractionalTile(curCenter.latitude, curCenter.longitude, zInt)
                        val pinOffsetPx = (14f * currentPinScale).dp.toPx().toDouble()
                        val hitRadiusPx = (28f * currentPinScale).dp.toPx().toDouble()

                        // Comprobar si se ha pulsado sobre un indicador de borde (compañero fuera de pantalla)
                        val edgePadPx = 8.dp.toPx()
                        val tappedEdgeMember = edgeHitTargets.lastOrNull { hit ->
                            tapOffset.x in (hit.left - edgePadPx)..(hit.right + edgePadPx) &&
                                tapOffset.y in (hit.top - edgePadPx)..(hit.bottom + edgePadPx)
                        }?.member

                        // Comprobar si se ha pulsado sobre algún compañero del grupo visible en mapa
                        val tappedMember = tappedEdgeMember ?: controller.groupMembers.value.lastOrNull { member ->
                            val (ptFracX, ptFracY) = TileMath.latLonToFractionalTile(member.latitude, member.longitude, zInt)
                            val unrotX = (ptFracX - centerFracX) * tileSizePx
                            val unrotY = (ptFracY - centerFracY) * tileSizePx
                            val screenX = screenCenterX + (unrotX * cos(rad) - unrotY * sin(rad))
                            val screenY = screenCenterY + (unrotX * sin(rad) + unrotY * cos(rad))
                            val dx = tapOffset.x - screenX
                            val dy = tapOffset.y - screenY
                            sqrt(dx * dx + dy * dy) <= hitRadiusPx
                        }

                        if (tappedMember != null) {
                            currentOnSelectGroupMember(tappedMember)
                        } else {
                            // Comprobar si se ha pulsado sobre algún waypoint (área táctil adaptada a densidad de pantalla)
                            val tappedWpt = curMarkers.lastOrNull { wpt ->
                                val (ptFracX, ptFracY) = TileMath.latLonToFractionalTile(wpt.latitude, wpt.longitude, zInt)
                                val unrotX = (ptFracX - centerFracX) * tileSizePx
                                val unrotY = (ptFracY - centerFracY) * tileSizePx
                                val screenX = screenCenterX + (unrotX * cos(rad) - unrotY * sin(rad))
                                val screenY = screenCenterY + (unrotX * sin(rad) + unrotY * cos(rad))
                                val pinHeadY = screenY - pinOffsetPx
                                val dx = tapOffset.x - screenX
                                val dy = tapOffset.y - pinHeadY
                                sqrt(dx * dx + dy * dy) <= hitRadiusPx
                            }

                            if (tappedWpt != null) {
                                currentOnSelectWaypoint(tappedWpt)
                            } else {
                                val activeRoadActive = controller.destinationRouteOptions.value
                                    .getOrNull(controller.selectedDestinationOptionIndex.value) != null
                                val visibleTracks = controller.routes.value.filter {
                                    it.isVisible && it.points.isNotEmpty() && (!activeRoadActive || it.id != controller.activeRoute.value?.id)
                                }
                                val tappedRoute = findTappedRoute(
                                    routes = visibleTracks,
                                    activeRouteId = controller.activeRoute.value?.id,
                                    tapScreenX = tapOffset.x.toDouble(),
                                    tapScreenY = tapOffset.y.toDouble(),
                                    viewportWidthPx = size.width.toDouble(),
                                    viewportHeightPx = size.height.toDouble(),
                                    center = curCenter,
                                    zoom = curZoom,
                                    headingDeg = curHeading,
                                    baseTilePx = baseTilePx,
                                    hitRadiusPx = 24.dp.toPx().toDouble()
                                )
                                if (tappedRoute != null) {
                                    currentOnSelectRoute(tappedRoute)
                                } else if (controller.droppedPin.value != null) {
                                    controller.clearDroppedPin()
                                }
                            }
                        }
                    },
                    onLongPress = { offset ->
                        if (currentIsPickingOnMap) return@detectMapInteractions
                        // Pulsación larga: calcular coordenadas geográficas reales compensando centro, zoom y rumbo
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val baseTileDp = if (currentLayer.isRetina512) 148.dp else 132.dp
                        val baseTilePx = maxOf(256.0, baseTileDp.toPx().toDouble())
                        val coord = screenOffsetToGeoCoord(
                            screenX = offset.x,
                            screenY = offset.y,
                            width = w,
                            height = h,
                            center = controller.mapCenter.value,
                            zoom = controller.mapZoom.value,
                            heading = controller.mapHeading.value,
                            baseTilePx = baseTilePx
                        )
                        controller.onMapLongPress(coord)
                    },
                    onTransform = { pan, gestureZoom, gestureRotation ->
                        // 1. Al interactuar con el mapa, desbloqueamos inmediatamente el seguimiento GPS
                        if (pan.getDistance() > 0.5f || abs(gestureZoom - 1f) > 0.005f || abs(gestureRotation) > 0.3f) {
                            if (controller.followMode.value != MapFollowMode.FREE) {
                                controller.setFollowMode(MapFollowMode.FREE)
                            }
                        }

                        // 2. Zoom táctil
                        if (abs(gestureZoom - 1.0f) > 0.001f) {
                            val curZoom = controller.mapZoom.value
                            val newZoom = (curZoom + (ln(gestureZoom.toDouble()) / ln(2.0))).coerceIn(3.0, 20.0)
                            controller.setZoom(newZoom)
                        }

                        // 3. Rotación táctil (giro natural en la misma dirección que los dos dedos)
                        if (abs(gestureRotation) > 0.3f) {
                            val curHead = controller.mapHeading.value
                            // Restar gestureRotation porque Canvas rota la escena por -heading
                            val newHead = (curHead - gestureRotation.toDouble() + 360.0) % 360.0
                            controller.setHeading(newHead)
                        }

                        // 4. Paneo continuo con teselas fraccionarias
                        if (pan.x != 0f || pan.y != 0f) {
                            val curCenter = controller.mapCenter.value
                            val curZ = controller.mapZoom.value
                            val curHead = controller.mapHeading.value
                            val zInt = curZ.toInt()

                            val (fracX, fracY) = TileMath.latLonToFractionalTile(curCenter.latitude, curCenter.longitude, zInt)
                            val baseTileDp = if (currentLayer.isRetina512) 148.dp else 132.dp
                            val baseTilePx = maxOf(256.0, baseTileDp.toPx().toDouble())
                            val tileSizePx = baseTilePx * (2.0.pow(curZ - zInt))

                            // Compensar la rotación del mapa para que el arrastre siga el dedo exactamente en cualquier ángulo
                            val rad = curHead * PI / 180.0
                            val cosR = cos(rad)
                            val sinR = sin(rad)
                            val worldPanX = pan.x * cosR - pan.y * sinR
                            val worldPanY = pan.x * sinR + pan.y * cosR

                            val dTileX = -worldPanX / tileSizePx
                            val dTileY = -worldPanY / tileSizePx

                            val (newLat, newLon) = TileMath.fractionalTileToLatLon(fracX + dTileX, fracY + dTileY, zInt)
                            controller.setCenter(GeoCoord(newLat, newLon))
                        }
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val screenCenter = Offset(w / 2f, h / 2f)

        // Fondo oscuro base (transparente cuando iOS muestra Apple Maps nativo debajo)
        if (!useNativeBackdrop) {
            drawRect(color = EnduPalette.Background)
        }

        val zInt = zoom.toInt()
        val worldN = if (zInt in 0..30) (1 shl zInt).toDouble() else 1.0
        val baseTileDp = if (layer.isRetina512) 148.dp else 132.dp
        val baseTilePx = maxOf(256.0, baseTileDp.toPx().toDouble())
        val tileSizePx = (baseTilePx * (2.0.pow(zoom - zInt))).toFloat()
        val (centerFracX, centerFracY) = TileMath.latLonToFractionalTile(center.latitude, center.longitude, zInt)
        val halfDiag = hypot(w, h) * 0.5f + 160f
        val viewMinX = screenCenter.x - halfDiag
        val viewMaxX = screenCenter.x + halfDiag
        val viewMinY = screenCenter.y - halfDiag
        val viewMaxY = screenCenter.y + halfDiag

        fun geoToScreen(coord: GeoCoord): Offset {
            val (ptFracX, ptFracY) = TileMath.latLonToFractionalTile(coord.latitude, coord.longitude, zInt)
            val dx = ((ptFracX - centerFracX) * tileSizePx).toFloat()
            val dy = ((ptFracY - centerFracY) * tileSizePx).toFloat()
            return Offset(screenCenter.x + dx, screenCenter.y + dy)
        }

        // Rotar toda la escena según el rumbo del mapa
        rotate(degrees = -heading.toFloat(), pivot = screenCenter) {

            // 1. Teselas raster (solo cuando no hay mapa nativo de Apple debajo)
            if (!useNativeBackdrop) {
                drawTileGrid(
                    tileStore = controller.tileStore,
                    centerFracX = centerFracX,
                    centerFracY = centerFracY,
                    zoom = zInt,
                    tileSizePx = tileSizePx,
                    screenCenter = screenCenter,
                    layer = layer,
                    w = w,
                    h = h,
                    revision = tileRevision
                )
            }

            val trackStrokeScale = displaySettings.trackStrokeScale.coerceIn(0.75f, 1.5f)
            val showTrackArrows = displaySettings.showTrackDirectionArrows

            // 2. Rutas secundarias GPX
            for (sec in secondaryRoutes) {
                if (sec.isVisible && sec.id != activeRoute?.id) {
                    drawRoutePolyline(
                        coords = sec.cachedCoordinates,
                        normX = sec.normMercatorX,
                        normY = sec.normMercatorY,
                        worldN = worldN,
                        centerFracX = centerFracX,
                        centerFracY = centerFracY,
                        tileSizePx = tileSizePx,
                        screenCenter = screenCenter,
                        viewMinX = viewMinX,
                        viewMaxX = viewMaxX,
                        viewMinY = viewMinY,
                        viewMaxY = viewMaxY,
                        routePath = routeDrawPath,
                        chevronPath = chevronDrawPath,
                        baseColor = Color(sec.color.rgbHex),
                        geoToScreen = ::geoToScreen,
                        isSecondary = true,
                        showArrows = showTrackArrows,
                        strokeScale = trackStrokeScale
                    )
                }
            }

            // 3. Traza grabada en directo (REC)
            if (recordedCoords.size >= 2) {
                drawRoutePolyline(
                    coords = recordedCoords,
                    normX = null,
                    normY = null,
                    worldN = worldN,
                    centerFracX = centerFracX,
                    centerFracY = centerFracY,
                    tileSizePx = tileSizePx,
                    screenCenter = screenCenter,
                    viewMinX = viewMinX,
                    viewMaxX = viewMaxX,
                    viewMinY = viewMinY,
                    viewMaxY = viewMaxY,
                    routePath = routeDrawPath,
                    chevronPath = chevronDrawPath,
                    baseColor = EnduPalette.Danger,
                    geoToScreen = ::geoToScreen,
                    isSecondary = false,
                    showArrows = false,
                    drawEndpoints = false
                )
            }

            // 4. Rutas alternativas por carretera OSRM (en gris/azul tenue si hay varias)
            if (roadOptions.size > 1) {
                roadOptions.forEachIndexed { idx, opt ->
                    if (idx != selectedOptionIdx) {
                        drawRoutePolyline(
                            coords = opt.coordinates,
                            normX = opt.asGPXRoute.normMercatorX,
                            normY = opt.asGPXRoute.normMercatorY,
                            worldN = worldN,
                            centerFracX = centerFracX,
                            centerFracY = centerFracY,
                            tileSizePx = tileSizePx,
                            screenCenter = screenCenter,
                            viewMinX = viewMinX,
                            viewMaxX = viewMaxX,
                            viewMinY = viewMinY,
                            viewMaxY = viewMaxY,
                            routePath = routeDrawPath,
                            chevronPath = chevronDrawPath,
                            baseColor = Color(0xFF6A, 0x82, 0x9B),
                            geoToScreen = ::geoToScreen,
                            isSecondary = true
                        )
                    }
                }
            }

            // 5. Ruta por carretera activa OSRM (Destino buscado)
            val activeRoad = roadOptions.getOrNull(selectedOptionIdx)
            if (activeRoad != null) {
                drawRoutePolyline(
                    coords = activeRoad.coordinates,
                    normX = activeRoad.asGPXRoute.normMercatorX,
                    normY = activeRoad.asGPXRoute.normMercatorY,
                    worldN = worldN,
                    centerFracX = centerFracX,
                    centerFracY = centerFracY,
                    tileSizePx = tileSizePx,
                    screenCenter = screenCenter,
                    viewMinX = viewMinX,
                    viewMaxX = viewMaxX,
                    viewMinY = viewMinY,
                    viewMaxY = viewMaxY,
                    routePath = routeDrawPath,
                    chevronPath = chevronDrawPath,
                    baseColor = EnduPalette.AccentBlue,
                    geoToScreen = ::geoToScreen,
                    isSecondary = false
                )
            }

            // 6. Enlace activo por carretera al track GPX
            val curApproachRoad = approachRoad
            if (curApproachRoad != null) {
                drawRoutePolyline(
                    coords = curApproachRoad.coordinates,
                    normX = curApproachRoad.asGPXRoute.normMercatorX,
                    normY = curApproachRoad.asGPXRoute.normMercatorY,
                    worldN = worldN,
                    centerFracX = centerFracX,
                    centerFracY = centerFracY,
                    tileSizePx = tileSizePx,
                    screenCenter = screenCenter,
                    viewMinX = viewMinX,
                    viewMaxX = viewMaxX,
                    viewMinY = viewMinY,
                    viewMaxY = viewMaxY,
                    routePath = routeDrawPath,
                    chevronPath = chevronDrawPath,
                    baseColor = Color.Cyan,
                    geoToScreen = ::geoToScreen,
                    isSecondary = false
                )
            }

            // 7. Ruta GPX activa
            if (activeRoute != null && activeRoute.isVisible && activeRoad == null) {
                drawRoutePolyline(
                    coords = activeRoute.cachedCoordinates,
                    normX = activeRoute.normMercatorX,
                    normY = activeRoute.normMercatorY,
                    worldN = worldN,
                    centerFracX = centerFracX,
                    centerFracY = centerFracY,
                    tileSizePx = tileSizePx,
                    screenCenter = screenCenter,
                    viewMinX = viewMinX,
                    viewMaxX = viewMaxX,
                    viewMinY = viewMinY,
                    viewMaxY = viewMaxY,
                    routePath = routeDrawPath,
                    chevronPath = chevronDrawPath,
                    baseColor = Color(activeRoute.color.rgbHex),
                    geoToScreen = ::geoToScreen,
                    isSecondary = false,
                    showArrows = showTrackArrows,
                    strokeScale = trackStrokeScale
                )
            }

            // 8. Ruta en línea recta al punto más cercano del GPX o flecha DMD2 de reconexión
            if (trackProgress != null && rider.coordinate != null) {
                val gpxThresholdM = displaySettings.gpxOffTrackThresholdMeters.toDouble().coerceIn(20.0, 200.0)
                val isGpxOnly = activeRoute != null && activeRoute.isVisible && activeRoad == null && curApproachRoad == null
                if (isGpxOnly && (isOffGpxTrack || trackProgress.offTrackMeters >= gpxThresholdM)) {
                    val rPos = geoToScreen(rider.coordinate)
                    val targetPos = geoToScreen(trackProgress.projectedCoordinate)
                    drawLine(
                        color = Color(0xF00E1117),
                        start = rPos,
                        end = targetPos,
                        strokeWidth = 7.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color(0xFFFFD60A),
                        start = rPos,
                        end = targetPos,
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f))
                    )
                    drawCircle(color = Color.Black, radius = 8.5.dp.toPx(), center = targetPos)
                    drawCircle(color = Color(0xFFFFD60A), radius = 6.2.dp.toPx(), center = targetPos)
                    drawCircle(color = Color.White, radius = 2.6.dp.toPx(), center = targetPos)
                } else if (!isGpxOnly && trackProgress.offTrackMeters > 35.0) {
                    val rPos = geoToScreen(rider.coordinate)
                    val targetPos = geoToScreen(trackProgress.reconnectCoordinate)
                    drawLine(
                        color = EnduPalette.Warning,
                        start = rPos,
                        end = targetPos,
                        strokeWidth = 4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                    )
                    drawCircle(color = EnduPalette.Warning, radius = 6f, center = targetPos)
                }
            }

            // 9. Modo Rumbo Directo («As the Crow Flies»)
            if (crowFliesActive && rider.coordinate != null) {
                val target = activeRoad?.destinationCoordinate ?: searchPin?.coordinate ?: activeRoute?.points?.firstOrNull()?.coordinate
                if (target != null) {
                    val originPos = geoToScreen(rider.coordinate)
                    val targetPos = geoToScreen(target)
                    drawLine(
                        color = Color.Yellow,
                        start = originPos,
                        end = targetPos,
                        strokeWidth = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                    )
                }
            }

            // 10. Paradas intermedias de ruta por carretera (estilo Google Maps multi-waypoint)
            intermediateWaypoints.forEachIndexed { idx, wpt ->
                val pt = geoToScreen(wpt.coordinate)
                drawCircle(color = Color.Black, radius = 9.dp.toPx(), center = pt)
                drawCircle(color = Color(0xFF00D4FF), radius = 7.5.dp.toPx(), center = pt)
                drawCircle(color = Color.White, radius = 5.dp.toPx(), center = pt)
                drawCircle(color = Color(0xFF0D1117), radius = 2.8.dp.toPx(), center = pt)
            }

            // 11. Pin del Destino Final
            if (searchPin != null) {
                val destPos = geoToScreen(searchPin!!.coordinate)
                drawCircle(color = Color.Black, radius = 8.5.dp.toPx(), center = destPos)
                drawCircle(color = EnduPalette.Accent, radius = 6.8.dp.toPx(), center = destPos)
                drawCircle(color = Color.White, radius = 2.8.dp.toPx(), center = destPos)
            }

            // 12. Chincheta de punto seleccionado en el mapa mediante pulsación larga
            if (droppedPin != null) {
                val pinPos = geoToScreen(droppedPin!!.coordinate)
                // Halo de pulsación translúcido
                drawCircle(color = EnduPalette.Accent.copy(alpha = 0.35f), radius = 17.dp.toPx(), center = pinPos)
                drawCircle(color = Color.Black, radius = 8.5.dp.toPx(), center = pinPos)
                drawCircle(color = Color(0xFFFF5252), radius = 6.8.dp.toPx(), center = pinPos)
                drawCircle(color = Color.White, radius = 2.8.dp.toPx(), center = pinPos)
            }

            // 13. Cursor de inspección del perfil de altitud
            scrubbedPoint?.let { scrub ->
                val scrubPos = geoToScreen(scrub.coordinate)
                drawCircle(color = Color.White, radius = 6.5.dp.toPx(), center = scrubPos)
                drawCircle(color = EnduPalette.Accent, radius = 4.5.dp.toPx(), center = scrubPos)
            }

            // 14. Marcadores y Waypoints con culling de viewport y control de densidad de etiquetas (soporte fluido para GPX con 200+ waypoints como TET)
            val routeWaypoints = if (activeRoute != null && activeRoute.isVisible) activeRoute.waypoints else emptyList()
            val showRouteWptLabels = displaySettings.showWaypointLabelsOnMap && (zoom >= 10.0 || routeWaypoints.size <= 35)
            var drawnRouteWptLabels = 0
            val maxRouteWptLabels = 32
            for (wpt in routeWaypoints) {
                val pt = geoToScreen(wpt.coordinate)
                if (pt.x !in viewMinX..viewMaxX || pt.y !in viewMinY..viewMaxY) continue
                val drawLbl = showRouteWptLabels && drawnRouteWptLabels < maxRouteWptLabels
                if (drawLbl) drawnRouteWptLabels++
                drawWaypointMarker(
                    pos = pt,
                    wpt = wpt,
                    heading = heading,
                    textMeasurer = textMeasurer,
                    labelLayoutCache = labelLayoutCache,
                    labelStyle = waypointLabelStyle,
                    showLabel = drawLbl,
                    pinScale = pinScale
                )
            }
            for (wpt in quickMarkers) {
                val pt = geoToScreen(wpt.coordinate)
                if (pt.x !in viewMinX..viewMaxX || pt.y !in viewMinY..viewMaxY) continue
                drawWaypointMarker(
                    pos = pt,
                    wpt = wpt,
                    heading = heading,
                    textMeasurer = textMeasurer,
                    labelLayoutCache = labelLayoutCache,
                    labelStyle = waypointLabelStyle,
                    showLabel = displaySettings.showWaypointLabelsOnMap,
                    pinScale = pinScale
                )
            }

            // 14b. Compañeros del grupo en tiempo real
            if (groupMembers.isNotEmpty()) {
                val nowMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                for (member in groupMembers) {
                    val pt = geoToScreen(member.coordinate)
                    drawGroupMemberMarker(
                        pos = pt,
                        member = member,
                        heading = heading,
                        nowMs = nowMs,
                        textMeasurer = textMeasurer,
                        labelLayoutCache = groupLabelLayoutCache,
                        labelStyle = groupLabelStyle
                    )
                }
            }

            // 15. Icono del Piloto con precisión GPS y Rumbo
            rider.coordinate?.let { riderCoord ->
                val riderPos = geoToScreen(riderCoord)
                drawRiderMarker(riderPos, rider)
            }
        }

        // 15b. Radar de borde en el mapa del móvil para compañeros fuera de pantalla
        edgeHitTargets.clear()
        if (groupMembers.isNotEmpty() && !isPickingOnMap) {
            drawMobileGroupEdgeIndicators(
                groupMembers = groupMembers,
                referenceCoord = rider.coordinate ?: center,
                centerFracX = centerFracX,
                centerFracY = centerFracY,
                zInt = zInt,
                tileSizePx = tileSizePx,
                heading = heading,
                screenCenter = screenCenter,
                textMeasurer = textMeasurer,
                labelLayoutCache = groupLabelLayoutCache,
                labelStyle = groupLabelStyle,
                hitTargetsOut = edgeHitTargets
            )
        }

        // 16. Si estamos en modo selección en el mapa, pintar la mira fija en el centro de pantalla
        if (isPickingOnMap) {
            val pinTip = screenCenter
            // Halo de mira central
            drawCircle(color = EnduPalette.Accent.copy(alpha = 0.25f), radius = 16.dp.toPx(), center = pinTip)
            drawCircle(color = Color.Black, radius = 3.5.dp.toPx(), center = pinTip)
            drawCircle(color = Color.White, radius = 1.8.dp.toPx(), center = pinTip)

            // Chincheta flotante que apunta directamente al centro exacto
            val pinHeadY = pinTip.y - 20.dp.toPx()
            drawLine(color = Color.Black, start = Offset(pinTip.x, pinHeadY + 6.dp.toPx()), end = pinTip, strokeWidth = 3.5.dp.toPx())
            drawLine(color = EnduPalette.Accent, start = Offset(pinTip.x, pinHeadY + 6.dp.toPx()), end = pinTip, strokeWidth = 2.2.dp.toPx())

            drawCircle(color = Color.Black, radius = 8.5.dp.toPx(), center = Offset(pinTip.x, pinHeadY))
            drawCircle(color = EnduPalette.Accent, radius = 6.8.dp.toPx(), center = Offset(pinTip.x, pinHeadY))
            drawCircle(color = Color.White, radius = 2.8.dp.toPx(), center = Offset(pinTip.x, pinHeadY))
        }
    }
    }
}

private fun DrawScope.drawTileGrid(
    tileStore: TileStore,
    centerFracX: Double,
    centerFracY: Double,
    zoom: Int,
    tileSizePx: Float,
    screenCenter: Offset,
    layer: MapLayer,
    w: Float,
    h: Float,
    revision: Int
) {
    // Cálculo dinámico ajustado para cubrir toda la pantalla en diagonal y con rotación (25–49 teselas máx.)
    val diagonalPx = hypot(w.toDouble(), h.toDouble()) / 2.0
    val radius = (ceil(diagonalPx / tileSizePx).toInt() + 1).coerceIn(2, 4)

    val startX = floor(centerFracX).toInt() - radius
    val endX = floor(centerFracX).toInt() + radius
    val startY = floor(centerFracY).toInt() - radius
    val endY = floor(centerFracY).toInt() + radius

    val maxTileIndex = if (zoom in 0..30) (1 shl zoom) - 1 else 0

    for (x in startX..endX) {
        if (x < 0 || x > maxTileIndex) continue
        for (y in startY..endY) {
            if (y < 0 || y > maxTileIndex) continue
            val posX = screenCenter.x + ((x - centerFracX) * tileSizePx).toFloat()
            val posY = screenCenter.y + ((y - centerFracY) * tileSizePx).toFloat()
            val x0 = floor(posX).toInt()
            val y0 = floor(posY).toInt()
            val x1 = ceil(posX + tileSizePx).toInt() + 1
            val y1 = ceil(posY + tileSizePx).toInt() + 1
            val dstOffset = IntOffset(x0, y0)
            val dstSize = IntSize((x1 - x0).coerceAtLeast(1), (y1 - y0).coerceAtLeast(1))

            val tileCoord = TileCoord(layer, zoom, x, y)
            val bmp = tileStore.requestBitmap(tileCoord)

            if (bmp != null) {
                drawImage(
                    image = bmp,
                    dstOffset = dstOffset,
                    dstSize = dstSize,
                    filterQuality = FilterQuality.Low
                )
            } else {
                // Algoritmo Parent Overzoom: escala la tesela padre desde RAM/Disco para evitar cuadros negros al hacer zoom
                val oz = tileStore.getOverzoomBitmap(tileCoord)
                if (oz != null) {
                    val subW = oz.bitmap.width / oz.scale
                    val subH = oz.bitmap.height / oz.scale
                    drawImage(
                        image = oz.bitmap,
                        srcOffset = IntOffset(oz.subX * subW, oz.subY * subH),
                        srcSize = IntSize(subW, subH),
                        dstOffset = dstOffset,
                        dstSize = dstSize,
                        filterQuality = FilterQuality.Low
                    )
                }
            }
        }
    }
}

private val WaypointLabelTextStyle = TextStyle(
    color = Color.White,
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold
)

private fun DrawScope.drawRoutePolyline(
    coords: List<GeoCoord>,
    normX: FloatArray?,
    normY: FloatArray?,
    worldN: Double,
    centerFracX: Double,
    centerFracY: Double,
    tileSizePx: Float,
    screenCenter: Offset,
    viewMinX: Float,
    viewMaxX: Float,
    viewMinY: Float,
    viewMaxY: Float,
    routePath: Path,
    chevronPath: Path,
    baseColor: Color,
    geoToScreen: (GeoCoord) -> Offset,
    isSecondary: Boolean,
    showArrows: Boolean = !isSecondary,
    drawEndpoints: Boolean = !isSecondary,
    strokeScale: Float = 1.0f
) {
    val hasNorm = normX != null && normY != null && normX.size == coords.size && normY.size == coords.size
    val count = coords.size
    if (count < 2) return

    routePath.reset()
    chevronPath.reset()

    val spacingPx = if (isSecondary) 128.dp.toPx() else 88.dp.toPx()
    val arrowSizePx = if (isSecondary) 6.dp.toPx() else 7.5.dp.toPx()
    val arrowStrokePx = if (isSecondary) 1.8.dp.toPx() else 2.2.dp.toPx()
    val haloColor = if (isSecondary) Color(0xCC0E1117) else Color(0xFF0E, 0x11, 0x17)
    val arrowColor = if (isSecondary) Color.White.copy(alpha = 0.85f) else Color.White

    fun pointX(i: Int): Float = if (hasNorm) {
        screenCenter.x + ((normX!![i].toDouble() * worldN - centerFracX) * tileSizePx).toFloat()
    } else {
        geoToScreen(coords[i]).x
    }

    fun pointY(i: Int): Float = if (hasNorm) {
        screenCenter.y + ((normY!![i].toDouble() * worldN - centerFracY) * tileSizePx).toFloat()
    } else {
        geoToScreen(coords[i]).y
    }

    val startX = pointX(0)
    val startY = pointY(0)
    var prevX = startX
    var prevY = startY
    var isPenDown = false
    var hasChevrons = false
    var accum = spacingPx * 0.55f
    val wingAngleOffset = 2.42f
    val lastIdx = count - 1
    var endX = startX
    var endY = startY

    for (i in 1..lastIdx) {
        val bx = pointX(i)
        val by = pointY(i)
        if (i == lastIdx) {
            endX = bx
            endY = by
        }
        val dx = bx - prevX
        val dy = by - prevY
        val segLen = hypot(dx, dy)
        if (i < lastIdx && segLen < 1.5f) continue
        if (segLen < 0.01f) continue

        val overlaps = maxOf(prevX, bx) >= viewMinX && minOf(prevX, bx) <= viewMaxX &&
            maxOf(prevY, by) >= viewMinY && minOf(prevY, by) <= viewMaxY
        if (overlaps) {
            if (!isPenDown) {
                routePath.moveTo(prevX, prevY)
                isPenDown = true
            }
            routePath.lineTo(bx, by)

            if (showArrows) {
                var d = spacingPx - accum
                if (d <= segLen) {
                    val dirAngle = atan2(dy, dx)
                    val cosDir = cos(dirAngle)
                    val sinDir = sin(dirAngle)
                    val cosL = cos(dirAngle + wingAngleOffset) * arrowSizePx
                    val sinL = sin(dirAngle + wingAngleOffset) * arrowSizePx
                    val cosR = cos(dirAngle - wingAngleOffset) * arrowSizePx
                    val sinR = sin(dirAngle - wingAngleOffset) * arrowSizePx
                    while (d <= segLen) {
                        val t = d / segLen
                        val px = prevX + dx * t
                        val py = prevY + dy * t
                        if (px in viewMinX..viewMaxX && py in viewMinY..viewMaxY) {
                            val tipX = px + cosDir * arrowSizePx * 0.35f
                            val tipY = py + sinDir * arrowSizePx * 0.35f
                            chevronPath.moveTo(tipX + cosL, tipY + sinL)
                            chevronPath.lineTo(tipX, tipY)
                            chevronPath.lineTo(tipX + cosR, tipY + sinR)
                            hasChevrons = true
                        }
                        d += spacingPx
                    }
                }
            }
        } else {
            isPenDown = false
        }
        if (showArrows) {
            accum = (accum + segLen) % spacingPx
        }
        prevX = bx
        prevY = by
    }

    if (isSecondary) {
        drawPath(
            path = routePath,
            color = baseColor.copy(alpha = 0.55f),
            style = Stroke(width = (3.5f * strokeScale).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        if (hasChevrons) {
            drawPath(
                path = chevronPath,
                color = haloColor,
                style = Stroke(width = arrowStrokePx * 2.0f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                path = chevronPath,
                color = arrowColor,
                style = Stroke(width = arrowStrokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    } else {
        // Línea principal con contorno negro de alto contraste
        drawPath(
            path = routePath,
            color = haloColor,
            style = Stroke(width = (7f * strokeScale).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path = routePath,
            color = baseColor,
            style = Stroke(width = (4.5f * strokeScale).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        if (hasChevrons) {
            drawPath(
                path = chevronPath,
                color = haloColor,
                style = Stroke(width = arrowStrokePx * 2.0f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                path = chevronPath,
                color = arrowColor,
                style = Stroke(width = arrowStrokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        if (drawEndpoints) {
            // Inicio (Verde) y Fin (Rojo)
            val startPos = Offset(startX, startY)
            drawCircle(color = Color.Black, radius = 6.5.dp.toPx(), center = startPos)
            drawCircle(color = EnduPalette.Success, radius = 5.dp.toPx(), center = startPos)

            val endPos = Offset(endX, endY)
            drawCircle(color = Color.Black, radius = 6.5.dp.toPx(), center = endPos)
            drawCircle(color = EnduPalette.Danger, radius = 5.dp.toPx(), center = endPos)
        }
    }
}

private fun DrawScope.drawWaypointMarker(
    pos: Offset,
    wpt: GPXWaypoint,
    heading: Double,
    textMeasurer: TextMeasurer,
    labelLayoutCache: HashMap<String, TextLayoutResult>,
    labelStyle: TextStyle = WaypointLabelTextStyle,
    showLabel: Boolean = true,
    pinScale: Float = 1.0f
) {
    val markerColor = when (wpt.symbol?.lowercase()) {
        "gate" -> Color(0xFFFF9800)
        "rock" -> Color(0xFFFF5722)
        "water" -> Color(0xFF00E5FF)
        "danger" -> Color(0xFFFF1744)
        "fuel" -> Color(0xFF00E676)
        "camp" -> Color(0xFFAB47BC)
        else -> Color(0xFFFFC107)
    }

    // Contrarrotar respecto al heading para que la chincheta y el texto queden siempre verticales
    rotate(degrees = heading.toFloat(), pivot = pos) {
        // 1. Sombra en el suelo
        val sw = (10f * pinScale).dp.toPx()
        val sh = (4.5f * pinScale).dp.toPx()
        drawOval(
            color = Color.Black.copy(alpha = 0.35f),
            topLeft = Offset(pos.x - sw / 2f, pos.y - sh / 2f),
            size = Size(sw, sh)
        )

        // 2. Vástago/aguja de la chincheta
        val pinHeadY = pos.y - (14f * pinScale).dp.toPx()
        drawLine(
            color = Color.Black,
            start = Offset(pos.x, pinHeadY + (3f * pinScale).dp.toPx()),
            end = pos,
            strokeWidth = (3.2f * pinScale).dp.toPx()
        )
        drawLine(
            color = Color.White,
            start = Offset(pos.x, pinHeadY + (3f * pinScale).dp.toPx()),
            end = pos,
            strokeWidth = (1.8f * pinScale).dp.toPx()
        )

        // 3. Cabeza de la chincheta
        val pinCenter = Offset(pos.x, pinHeadY)
        drawCircle(color = Color.Black, radius = (9.5f * pinScale).dp.toPx(), center = pinCenter)
        drawCircle(color = markerColor, radius = (8f * pinScale).dp.toPx(), center = pinCenter)
        drawCircle(color = Color.White, radius = (4.2f * pinScale).dp.toPx(), center = pinCenter)
        drawCircle(color = Color(0xFF0F172A), radius = (2.4f * pinScale).dp.toPx(), center = pinCenter)

        // 4. Cartela flotante con el nombre del Waypoint (cacheada entre frames)
        if (showLabel && wpt.name.isNotBlank()) {
            val textLayout = labelLayoutCache[wpt.name] ?: textMeasurer.measure(
                text = wpt.name,
                style = labelStyle
            ).also {
                if (labelLayoutCache.size > 512) labelLayoutCache.clear()
                labelLayoutCache[wpt.name] = it
            }
            val padH = (5f * pinScale).dp.toPx()
            val padV = (2.5f * pinScale).dp.toPx()
            val badgeW = textLayout.size.width + padH * 2
            val badgeH = textLayout.size.height + padV * 2
            val badgeLeft = pos.x - badgeW / 2f
            val badgeTop = pinHeadY - (10f * pinScale).dp.toPx() - badgeH

            drawRoundRect(
                color = Color(0xEE0B111A),
                topLeft = Offset(badgeLeft, badgeTop),
                size = Size(badgeW, badgeH),
                cornerRadius = CornerRadius((4f * pinScale).dp.toPx(), (4f * pinScale).dp.toPx())
            )
            drawRoundRect(
                color = markerColor,
                topLeft = Offset(badgeLeft, badgeTop),
                size = Size(badgeW, badgeH),
                cornerRadius = CornerRadius((4f * pinScale).dp.toPx(), (4f * pinScale).dp.toPx()),
                style = Stroke(width = 1.dp.toPx())
            )
            drawText(
                textLayoutResult = textLayout,
                topLeft = Offset(badgeLeft + padH, badgeTop + padV)
            )
        }
    }
}

private fun DrawScope.drawGroupMemberMarker(
    pos: Offset,
    member: GroupMember,
    heading: Double,
    nowMs: Long,
    textMeasurer: TextMeasurer,
    labelLayoutCache: HashMap<String, TextLayoutResult>,
    labelStyle: TextStyle = WaypointLabelTextStyle
) {
    val memberColor = Color(member.color.argb)
    val stale = member.isStale(nowMs)
    val fillColor = if (stale) memberColor.copy(alpha = 0.55f) else memberColor

    if (member.status.isAlert) {
        val alertColor = when (member.status) {
            RiderStatus.SOS -> EnduPalette.Danger
            RiderStatus.MECHANICAL -> EnduPalette.Accent
            else -> EnduPalette.Warning
        }
        drawCircle(color = alertColor.copy(alpha = 0.28f), radius = 20.dp.toPx(), center = pos)
        drawCircle(color = alertColor, radius = 16.dp.toPx(), center = pos, style = Stroke(width = 2.dp.toPx()))
    }

    drawCircle(color = Color.Black, radius = 11.5.dp.toPx(), center = pos)
    drawCircle(color = fillColor, radius = 9.2.dp.toPx(), center = pos)

    if (member.speedKmh >= 3.0) {
        rotate(degrees = member.headingDegrees.toFloat(), pivot = pos) {
            val tip = 6.dp.toPx()
            val wing = 4.2.dp.toPx()
            val p = Path().apply {
                moveTo(pos.x, pos.y - tip)
                lineTo(pos.x + wing, pos.y + wing)
                lineTo(pos.x, pos.y + 2.dp.toPx())
                lineTo(pos.x - wing, pos.y + wing)
                close()
            }
            drawPath(path = p, color = Color.White)
        }
    } else {
        drawCircle(color = Color.White, radius = 3.2.dp.toPx(), center = pos)
    }

    rotate(degrees = heading.toFloat(), pivot = pos) {
        val tag = if (member.status.isAlert) " · ${member.status.tftTag}" else ""
        val label = member.name.take(14) + tag
        val textLayout = labelLayoutCache[label] ?: textMeasurer.measure(
            text = label,
            style = labelStyle
        ).also {
            if (labelLayoutCache.size > 128) labelLayoutCache.clear()
            labelLayoutCache[label] = it
        }
        val padH = 5.dp.toPx()
        val padV = 2.5.dp.toPx()
        val badgeW = textLayout.size.width + padH * 2
        val badgeH = textLayout.size.height + padV * 2
        val badgeLeft = pos.x - badgeW / 2f
        val badgeTop = pos.y - 14.dp.toPx() - badgeH

        val borderClr = if (member.status.isAlert) EnduPalette.Danger else fillColor
        drawRoundRect(
            color = Color(0xEE0B111A),
            topLeft = Offset(badgeLeft, badgeTop),
            size = Size(badgeW, badgeH),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
        drawRoundRect(
            color = borderClr,
            topLeft = Offset(badgeLeft, badgeTop),
            size = Size(badgeW, badgeH),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            style = Stroke(width = 1.dp.toPx())
        )
        drawText(
            textLayoutResult = textLayout,
            topLeft = Offset(badgeLeft + padH, badgeTop + padV)
        )
    }
}

private data class MobileGroupEdgeHitTarget(
    val member: GroupMember,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

private data class MobileEdgeCandidate(
    val member: GroupMember,
    val edgeX: Float,
    val edgeY: Float,
    val angleRad: Float,
    val distanceMeters: Double,
    val isStale: Boolean
)

private fun DrawScope.drawMobileGroupEdgeIndicators(
    groupMembers: List<GroupMember>,
    referenceCoord: GeoCoord,
    centerFracX: Double,
    centerFracY: Double,
    zInt: Int,
    tileSizePx: Float,
    heading: Double,
    screenCenter: Offset,
    textMeasurer: TextMeasurer,
    labelLayoutCache: HashMap<String, TextLayoutResult>,
    labelStyle: TextStyle = WaypointLabelTextStyle,
    hitTargetsOut: MutableList<MobileGroupEdgeHitTarget>
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    val rad = -heading * PI / 180.0
    val cosR = cos(rad)
    val sinR = sin(rad)
    val nowMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

    val visiblePadX = 16.dp.toPx()
    val visibleTop = 62.dp.toPx()
    val visibleBottom = h - 78.dp.toPx()

    // Márgenes seguros para no tapar buscador/alertas arriba, barra inferior ni botones flotantes a la derecha
    val safeLeft = 18.dp.toPx()
    val safeRight = (w - 58.dp.toPx()).coerceAtLeast(safeLeft + 40f)
    val safeTop = 108.dp.toPx()
    val safeBottom = (h - 90.dp.toPx()).coerceAtLeast(safeTop + 40f)

    val offScreen = ArrayList<MobileEdgeCandidate>()
    for (member in groupMembers) {
        val (ptFracX, ptFracY) = TileMath.latLonToFractionalTile(member.latitude, member.longitude, zInt)
        val unrotX = (ptFracX - centerFracX) * tileSizePx
        val unrotY = (ptFracY - centerFracY) * tileSizePx
        val sx = screenCenter.x + (unrotX * cosR - unrotY * sinR).toFloat()
        val sy = screenCenter.y + (unrotX * sinR + unrotY * cosR).toFloat()

        val onScreen = sx in visiblePadX..(w - visiblePadX) && sy in visibleTop..visibleBottom
        if (onScreen) continue

        val dx = sx - screenCenter.x
        val dy = sy - screenCenter.y
        var tMin = Float.MAX_VALUE
        if (dx > 1e-3f) {
            val t = (safeRight - screenCenter.x) / dx
            if (t > 0f && t < tMin) tMin = t
        } else if (dx < -1e-3f) {
            val t = (safeLeft - screenCenter.x) / dx
            if (t > 0f && t < tMin) tMin = t
        }
        if (dy > 1e-3f) {
            val t = (safeBottom - screenCenter.y) / dy
            if (t > 0f && t < tMin) tMin = t
        } else if (dy < -1e-3f) {
            val t = (safeTop - screenCenter.y) / dy
            if (t > 0f && t < tMin) tMin = t
        }
        if (tMin == Float.MAX_VALUE) tMin = 1f

        val ex = (screenCenter.x + dx * tMin).coerceIn(safeLeft, safeRight)
        val ey = (screenCenter.y + dy * tMin).coerceIn(safeTop, safeBottom)
        offScreen.add(
            MobileEdgeCandidate(
                member = member,
                edgeX = ex,
                edgeY = ey,
                angleRad = atan2(dy, dx),
                distanceMeters = referenceCoord.distanceTo(member.coordinate),
                isStale = member.isStale(nowMs)
            )
        )
    }

    if (offScreen.isEmpty()) return

    // Priorizar alertas y compañeros más cercanos (máximo 8 indicadores)
    val sorted = offScreen
        .sortedWith(
            compareByDescending<MobileEdgeCandidate> { it.member.status.alertPriority }
                .thenBy { it.distanceMeters }
        )
        .take(8)
        .toMutableList()

    // Separar verticalmente indicadores muy próximos en el mismo borde
    val minGapY = 30.dp.toPx()
    val minGapX = 76.dp.toPx()
    for (i in sorted.indices) {
        for (j in 0 until i) {
            val a = sorted[j]
            val b = sorted[i]
            if (abs(a.edgeX - b.edgeX) < minGapX && abs(a.edgeY - b.edgeY) < minGapY) {
                val shiftedY = if (b.edgeY + minGapY <= safeBottom) {
                    b.edgeY + minGapY
                } else {
                    (b.edgeY - minGapY).coerceAtLeast(safeTop)
                }
                sorted[i] = b.copy(edgeY = shiftedY)
            }
        }
    }

    val arrowTipDist = 11.dp.toPx()
    val arrowBaseDist = 3.dp.toPx()
    val arrowHalfW = 6.dp.toPx()
    val padH = 7.dp.toPx()
    val padV = 4.dp.toPx()
    val dotR = 3.5.dp.toPx()
    val dotGap = 5.dp.toPx()
    val cornerR = 7.dp.toPx()
    val badgeGap = 8.dp.toPx()
    val arrowPath = Path()

    for (ind in sorted) {
        val m = ind.member
        val baseColor = when (m.status) {
            RiderStatus.SOS -> EnduPalette.Danger
            RiderStatus.MECHANICAL -> EnduPalette.Accent
            RiderStatus.STOPPED_WAIT -> EnduPalette.Warning
            else -> Color(m.color.argb)
        }
        val accentColor = if (ind.isStale && !m.status.isAlert) baseColor.copy(alpha = 0.65f) else baseColor
        val bgColor = when (m.status) {
            RiderStatus.SOS -> Color(0xFF451218)
            RiderStatus.MECHANICAL -> Color(0xFF3E220D)
            RiderStatus.STOPPED_WAIT -> Color(0xFF3A2D0B)
            else -> Color(0xFF0E141D)
        }

        val distStr = GroupTftLayout.formatCompactDistance(ind.distanceMeters)
        val label = if (m.status.isAlert) {
            "${m.status.tftTag} ${m.name.take(8)} · $distStr"
        } else {
            "${m.name.take(9)} · $distStr"
        }

        val textLayout = labelLayoutCache[label] ?: textMeasurer.measure(
            text = label,
            style = labelStyle
        ).also {
            if (labelLayoutCache.size > 128) labelLayoutCache.clear()
            labelLayoutCache[label] = it
        }

        // 1. Flecha direccional apuntando hacia la ubicación real del compañero
        val cosA = cos(ind.angleRad)
        val sinA = sin(ind.angleRad)
        val tipX = ind.edgeX + cosA * arrowTipDist
        val tipY = ind.edgeY + sinA * arrowTipDist
        val baseX = ind.edgeX - cosA * arrowBaseDist
        val baseY = ind.edgeY - sinA * arrowBaseDist
        val leftX = baseX - sinA * arrowHalfW
        val leftY = baseY + cosA * arrowHalfW
        val rightX = baseX + sinA * arrowHalfW
        val rightY = baseY - cosA * arrowHalfW

        arrowPath.reset()
        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(leftX, leftY)
        arrowPath.lineTo(rightX, rightY)
        arrowPath.close()

        drawPath(path = arrowPath, color = Color.Black, style = Stroke(width = 3.dp.toPx()))
        drawPath(path = arrowPath, color = accentColor)

        // 2. Pastilla opaca desplazada hacia el interior del mapa respecto a la flecha
        val badgeW = padH + dotR * 2f + dotGap + textLayout.size.width + padH
        val badgeH = textLayout.size.height + padV * 2f

        val rawCenterX = ind.edgeX - cosA * (badgeW * 0.5f + badgeGap)
        val rawCenterY = ind.edgeY - sinA * (badgeH * 0.5f + badgeGap)

        val minLeft = 8.dp.toPx()
        val maxLeft = (safeRight - badgeW).coerceAtLeast(minLeft)
        val minTop = safeTop - 10.dp.toPx()
        val maxTop = (safeBottom - badgeH + 10.dp.toPx()).coerceAtLeast(minTop)

        val badgeLeft = (rawCenterX - badgeW * 0.5f).coerceIn(minLeft, maxLeft)
        val badgeTop = (rawCenterY - badgeH * 0.5f).coerceIn(minTop, maxTop)

        drawRoundRect(
            color = bgColor,
            topLeft = Offset(badgeLeft, badgeTop),
            size = Size(badgeW, badgeH),
            cornerRadius = CornerRadius(cornerR, cornerR)
        )
        drawRoundRect(
            color = accentColor,
            topLeft = Offset(badgeLeft, badgeTop),
            size = Size(badgeW, badgeH),
            cornerRadius = CornerRadius(cornerR, cornerR),
            style = Stroke(width = 1.5.dp.toPx())
        )
        drawCircle(
            color = accentColor,
            radius = dotR,
            center = Offset(badgeLeft + padH + dotR, badgeTop + badgeH * 0.5f)
        )
        drawText(
            textLayoutResult = textLayout,
            topLeft = Offset(badgeLeft + padH + dotR * 2f + dotGap, badgeTop + padV)
        )

        hitTargetsOut.add(
            MobileGroupEdgeHitTarget(
                member = m,
                left = minOf(badgeLeft, tipX - arrowHalfW),
                top = minOf(badgeTop, tipY - arrowHalfW),
                right = maxOf(badgeLeft + badgeW, tipX + arrowHalfW),
                bottom = maxOf(badgeTop + badgeH, tipY + arrowHalfW)
            )
        )
    }
}

private fun DrawScope.drawRiderMarker(pos: Offset, rider: RiderTelemetry) {
    if (rider.horizontalAccuracyM in 1.0..50.0) {
        drawCircle(
            color = EnduPalette.AccentBlue.copy(alpha = 0.15f),
            radius = (rider.horizontalAccuracyM * 2.0).toFloat(),
            center = pos
        )
    }

    // La escena completa ya está rotada por -heading, por lo que el rumbo en coordenadas
    // locales de escena es directamente rider.headingDegrees (donde Y negativo es el Norte)
    val riderAngle = rider.headingDegrees.toFloat()
    val tipLen = 13.dp.toPx()
    val wingW = 9.dp.toPx()
    val wingBack = 9.dp.toPx()
    val tailIn = 4.5.dp.toPx()
    rotate(degrees = riderAngle, pivot = pos) {
        val arrowPath = Path().apply {
            moveTo(pos.x, pos.y - tipLen)
            lineTo(pos.x + wingW, pos.y + wingBack)
            lineTo(pos.x, pos.y + tailIn)
            lineTo(pos.x - wingW, pos.y + wingBack)
            close()
        }
        drawPath(path = arrowPath, color = Color.Black, style = Stroke(width = 2.8.dp.toPx(), join = StrokeJoin.Round))
        drawPath(path = arrowPath, color = Color(0xFF, 0xD7, 0x00))
    }
}

/**
 * Convierte un toque en coordenadas de pantalla (píxeles) a coordenadas geográficas (lat, lon),
 * compensando el centro fraccionario del mapa, el nivel de zoom y el rumbo (rotación) actual.
 */
private fun screenOffsetToGeoCoord(
    screenX: Float,
    screenY: Float,
    width: Float,
    height: Float,
    center: GeoCoord,
    zoom: Double,
    heading: Double,
    baseTilePx: Double = 256.0
): GeoCoord {
    val screenCenterX = width / 2.0
    val screenCenterY = height / 2.0
    val dx = screenX - screenCenterX
    val dy = screenY - screenCenterY

    val zInt = zoom.toInt()
    val tileSizePx = baseTilePx * (2.0.pow(zoom - zInt))
    val (centerFracX, centerFracY) = TileMath.latLonToFractionalTile(center.latitude, center.longitude, zInt)

    // Desrotar el toque respecto al heading: Canvas se pintó rotado con -heading
    val rad = heading * PI / 180.0
    val unrotX = dx * cos(rad) - dy * sin(rad)
    val unrotY = dx * sin(rad) + dy * cos(rad)

    val targetFracX = centerFracX + (unrotX / tileSizePx)
    val targetFracY = centerFracY + (unrotY / tileSizePx)

    val (lat, lon) = TileMath.fractionalTileToLatLon(targetFracX, targetFracY, zInt)
    return GeoCoord(lat, lon)
}

/**
 * Localiza el track GPX visible más cercano al toque sobre el mapa compensando zoom,
 * rotación del viewport y densidad de pantalla.
 */
internal fun findTappedRoute(
    routes: List<GPXRoute>,
    activeRouteId: String?,
    tapScreenX: Double,
    tapScreenY: Double,
    viewportWidthPx: Double,
    viewportHeightPx: Double,
    center: GeoCoord,
    zoom: Double,
    headingDeg: Double,
    baseTilePx: Double,
    hitRadiusPx: Double
): GPXRoute? {
    if (routes.isEmpty() || viewportWidthPx <= 0.0 || viewportHeightPx <= 0.0 || baseTilePx <= 0.0 || hitRadiusPx <= 0.0) {
        return null
    }
    val zInt = zoom.toInt()
    val tileSizePx = baseTilePx * (2.0.pow(zoom - zInt))
    if (tileSizePx <= 0.0) return null

    val (centerFracX, centerFracY) = TileMath.latLonToFractionalTile(center.latitude, center.longitude, zInt)
    val dx = tapScreenX - viewportWidthPx / 2.0
    val dy = tapScreenY - viewportHeightPx / 2.0
    val rad = headingDeg * PI / 180.0
    val unrotX = dx * cos(rad) - dy * sin(rad)
    val unrotY = dx * sin(rad) + dy * cos(rad)
    val tapFracX = centerFracX + (unrotX / tileSizePx)
    val tapFracY = centerFracY + (unrotY / tileSizePx)

    val maxFracDist = hitRadiusPx / tileSizePx
    val maxFracDistSq = maxFracDist * maxFracDist

    var bestRoute: GPXRoute? = null
    var bestScore = Double.POSITIVE_INFINITY

    for (route in routes) {
        if (!route.isVisible || route.points.isEmpty()) continue
        val distSq = minFracDistanceSqToRoute(route.points, tapFracX, tapFracY, zInt, maxFracDist)
        if (distSq <= maxFracDistSq) {
            // Priorizar ligeramente el track activo si coincide sobre otros solapados
            val score = if (route.id == activeRouteId) distSq * 0.7 else distSq
            if (score < bestScore) {
                bestScore = score
                bestRoute = route
            }
        }
    }
    return bestRoute
}

private fun minFracDistanceSqToRoute(
    points: List<GPXPoint>,
    tapFracX: Double,
    tapFracY: Double,
    zoomInt: Int,
    maxFracDist: Double
): Double {
    var minDistSq = Double.POSITIVE_INFINITY
    var (prevX, prevY) = TileMath.latLonToFractionalTile(points[0].latitude, points[0].longitude, zoomInt)
    if (points.size == 1) {
        val dpx = tapFracX - prevX
        val dpy = tapFracY - prevY
        return dpx * dpx + dpy * dpy
    }

    for (i in 1 until points.size) {
        val (curX, curY) = TileMath.latLonToFractionalTile(points[i].latitude, points[i].longitude, zoomInt)
        val minSegX = minOf(prevX, curX) - maxFracDist
        val maxSegX = maxOf(prevX, curX) + maxFracDist
        val minSegY = minOf(prevY, curY) - maxFracDist
        val maxSegY = maxOf(prevY, curY) + maxFracDist
        if (tapFracX in minSegX..maxSegX && tapFracY in minSegY..maxSegY) {
            val dSq = pointToSegmentDistanceSq(tapFracX, tapFracY, prevX, prevY, curX, curY)
            if (dSq < minDistSq) {
                minDistSq = dSq
                if (minDistSq <= 1e-18) return minDistSq
            }
        }
        prevX = curX
        prevY = curY
    }
    return minDistSq
}

private fun pointToSegmentDistanceSq(
    px: Double,
    py: Double,
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double
): Double {
    val abX = bx - ax
    val abY = by - ay
    val lenSq = abX * abX + abY * abY
    if (lenSq <= 1e-24) {
        val dpx = px - ax
        val dpy = py - ay
        return dpx * dpx + dpy * dpy
    }
    val t = (((px - ax) * abX + (py - ay) * abY) / lenSq).coerceIn(0.0, 1.0)
    val projX = ax + t * abX
    val projY = ay + t * abY
    val dx = px - projX
    val dy = py - projY
    return dx * dx + dy * dy
}

/**
 * Gestor táctil unificado de alto rendimiento para el mapa:
 * - Detecta pulsación larga sin interferir con el paneo
 * - Detecta toque rápido (tap)
 * - Paneo fluido con 1 dedo
 * - Zoom y rotación con 2 dedos (pinch & twist)
 */
private suspend fun PointerInputScope.detectMapInteractions(
    onTap: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
    onTransform: (pan: Offset, zoom: Float, rotation: Float) -> Unit
) {
    coroutineScope {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val initialDownPos = down.position
            var hasMovedPastSlop = false
            var hasTriggeredLongPress = false
            var isMultiTouch = false
            val touchSlop = viewConfiguration.touchSlop
            val longPressTimeout = viewConfiguration.longPressTimeoutMillis

            val longPressJob = launch {
                delay(longPressTimeout)
                if (!hasMovedPastSlop && !isMultiTouch) {
                    hasTriggeredLongPress = true
                    onLongPress(initialDownPos)
                }
            }

            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val activePointers = event.changes.filter { it.pressed }
                    if (activePointers.isEmpty()) {
                        // Todos los dedos levantados
                        if (!hasMovedPastSlop && !hasTriggeredLongPress && !isMultiTouch) {
                            onTap(initialDownPos)
                        }
                        break
                    }

                    if (activePointers.size > 1) {
                        isMultiTouch = true
                        longPressJob.cancel()
                    }

                    if (!hasTriggeredLongPress) {
                        val zoomChange = event.calculateZoom()
                        val rotationChange = event.calculateRotation()
                        val panChange = event.calculatePan()

                        if (!hasMovedPastSlop) {
                            val dist = if (activePointers.size == 1) {
                                (activePointers[0].position - initialDownPos).getDistance()
                            } else {
                                panChange.getDistance()
                            }
                            if (dist > touchSlop || isMultiTouch || abs(zoomChange - 1f) > 0.01f || abs(rotationChange) > 0.5f) {
                                hasMovedPastSlop = true
                                longPressJob.cancel()
                            }
                        }

                        if (hasMovedPastSlop) {
                            onTransform(panChange, zoomChange, rotationChange)
                            event.changes.forEach { it.consume() }
                        }
                    } else {
                        // Si se disparó long press, consumir eventos hasta levantar los dedos
                        event.changes.forEach { it.consume() }
                    }
                }
            } finally {
                longPressJob.cancel()
            }
        }
    }
}
