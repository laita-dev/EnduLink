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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    isPickingOnMap: Boolean = false,
    onSelectWaypoint: (GPXWaypoint) -> Unit = {},
    onSelectRoute: (GPXRoute) -> Unit = {},
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
    val currentOnSelectWaypoint by rememberUpdatedState(onSelectWaypoint)
    val currentOnSelectRoute by rememberUpdatedState(onSelectRoute)
    val currentIsPickingOnMap by rememberUpdatedState(isPickingOnMap)
    val currentLayer by rememberUpdatedState(layer)
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
                        val pinOffsetPx = 14.dp.toPx().toDouble()
                        val hitRadiusPx = 28.dp.toPx().toDouble()

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
        val baseTileDp = if (layer.isRetina512) 148.dp else 132.dp
        val baseTilePx = maxOf(256.0, baseTileDp.toPx().toDouble())
        val tileSizePx = (baseTilePx * (2.0.pow(zoom - zInt))).toFloat()
        val (centerFracX, centerFracY) = TileMath.latLonToFractionalTile(center.latitude, center.longitude, zInt)

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

            // 2. Rutas secundarias GPX de la biblioteca
            // 2. Rutas secundarias GPX
            for (sec in secondaryRoutes) {
                if (sec.isVisible && sec.id != activeRoute?.id) {
                    drawRoutePolyline(sec.points.map { it.coordinate }, Color(sec.color.rgbHex), ::geoToScreen, isSecondary = true)
                }
            }

            // 3. Traza grabada en directo (REC)
            if (recordedCoords.size >= 2) {
                val path = Path()
                val first = geoToScreen(recordedCoords[0])
                path.moveTo(first.x, first.y)
                for (i in 1 until recordedCoords.size) {
                    val p = geoToScreen(recordedCoords[i])
                    path.lineTo(p.x, p.y)
                }
                drawPath(path, color = EnduPalette.Danger, style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            // 4. Rutas alternativas por carretera OSRM (en gris/azul tenue si hay varias)
            // 4. Rutas alternativas por carretera OSRM
            if (roadOptions.size > 1) {
                roadOptions.forEachIndexed { idx, opt ->
                    if (idx != selectedOptionIdx) {
                        drawRoutePolyline(opt.coordinates, Color(0xFF6A, 0x82, 0x9B), ::geoToScreen, isSecondary = true)
                    }
                }
            }

            // 5. Ruta por carretera activa OSRM (Destino buscado)
            val activeRoad = roadOptions.getOrNull(selectedOptionIdx)
            if (activeRoad != null) {
                drawRoutePolyline(activeRoad.coordinates, EnduPalette.AccentBlue, ::geoToScreen, isSecondary = false)
            }

            // 6. Enlace activo por carretera al track GPX
            if (approachRoad != null) {
                drawRoutePolyline(approachRoad!!.coordinates, Color.Cyan, ::geoToScreen, isSecondary = false)
            }

            // 7. Ruta GPX activa seleccionada
            // 7. Ruta GPX activa
            if (activeRoute != null && activeRoute.isVisible && activeRoad == null) {
                drawRoutePolyline(activeRoute.points.map { it.coordinate }, Color(activeRoute.color.rgbHex), ::geoToScreen, isSecondary = false)
            }

            // 8. Flecha de reconexión DMD2 si estamos desviados del track
            if (trackProgress != null && rider.coordinate != null && trackProgress.offTrackMeters > 35.0) {
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

            // 14. Marcadores y Waypoints con chinchetas visuales y etiquetas (incluyendo los embebidos en el GPX activo)
            if (activeRoute != null && activeRoute.isVisible) {
                for (wpt in activeRoute.waypoints) {
                    val pt = geoToScreen(wpt.coordinate)
                    drawWaypointMarker(pt, wpt, heading, textMeasurer)
                }
            }
            for (wpt in quickMarkers) {
                val pt = geoToScreen(wpt.coordinate)
                drawWaypointMarker(pt, wpt, heading, textMeasurer)
            }

            // 15. Icono del Piloto con precisión GPS y Rumbo
            rider.coordinate?.let { riderCoord ->
                val riderPos = geoToScreen(riderCoord)
                drawRiderMarker(riderPos, rider)
            }
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
    // Cálculo dinámico para cubrir toda la pantalla en diagonal y con rotación (sin esquinas negras)
    val diagonalPx = hypot(w.toDouble(), h.toDouble()) / 2.0
    val radius = maxOf(5, ceil(diagonalPx / tileSizePx).toInt() + 2)

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

private fun DrawScope.drawRoutePolyline(
    coords: List<GeoCoord>,
    baseColor: Color,
    geoToScreen: (GeoCoord) -> Offset,
    isSecondary: Boolean
) {
    if (coords.size < 2) return

    val path = Path()
    val first = geoToScreen(coords[0])
    path.moveTo(first.x, first.y)
    for (i in 1 until coords.size) {
        val p = geoToScreen(coords[i])
        path.lineTo(p.x, p.y)
    }

    if (isSecondary) {
        drawPath(
            path = path,
            color = baseColor.copy(alpha = 0.55f),
            style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    } else {
        // Línea principal con contorno negro de alto contraste
        drawPath(
            path = path,
            color = Color(0xFF0E, 0x11, 0x17),
            style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path = path,
            color = baseColor,
            style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Inicio (Verde) y Fin (Rojo)
        val startPos = geoToScreen(coords.first())
        drawCircle(color = Color.Black, radius = 6.5.dp.toPx(), center = startPos)
        drawCircle(color = EnduPalette.Success, radius = 5.dp.toPx(), center = startPos)

        val endPos = geoToScreen(coords.last())
        drawCircle(color = Color.Black, radius = 6.5.dp.toPx(), center = endPos)
        drawCircle(color = EnduPalette.Danger, radius = 5.dp.toPx(), center = endPos)
    }
}

private fun DrawScope.drawWaypointMarker(
    pos: Offset,
    wpt: GPXWaypoint,
    heading: Double,
    textMeasurer: TextMeasurer
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
        val sw = 10.dp.toPx()
        val sh = 4.5.dp.toPx()
        drawOval(
            color = Color.Black.copy(alpha = 0.35f),
            topLeft = Offset(pos.x - sw / 2f, pos.y - sh / 2f),
            size = Size(sw, sh)
        )

        // 2. Vástago/aguja de la chincheta
        val pinHeadY = pos.y - 14.dp.toPx()
        drawLine(
            color = Color.Black,
            start = Offset(pos.x, pinHeadY + 3.dp.toPx()),
            end = pos,
            strokeWidth = 3.2.dp.toPx()
        )
        drawLine(
            color = Color.White,
            start = Offset(pos.x, pinHeadY + 3.dp.toPx()),
            end = pos,
            strokeWidth = 1.8.dp.toPx()
        )

        // 3. Cabeza de la chincheta
        val pinCenter = Offset(pos.x, pinHeadY)
        drawCircle(color = Color.Black, radius = 9.5.dp.toPx(), center = pinCenter)
        drawCircle(color = markerColor, radius = 8.dp.toPx(), center = pinCenter)
        drawCircle(color = Color.White, radius = 4.2.dp.toPx(), center = pinCenter)
        drawCircle(color = Color(0xFF0F172A), radius = 2.4.dp.toPx(), center = pinCenter)

        // 4. Cartela flotante con el nombre del Waypoint
        val textLayout = textMeasurer.measure(
            text = wpt.name,
            style = TextStyle(
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        )
        val padH = 5.dp.toPx()
        val padV = 2.5.dp.toPx()
        val badgeW = textLayout.size.width + padH * 2
        val badgeH = textLayout.size.height + padV * 2
        val badgeLeft = pos.x - badgeW / 2f
        val badgeTop = pinHeadY - 10.dp.toPx() - badgeH

        drawRoundRect(
            color = Color(0xEE0B111A),
            topLeft = Offset(badgeLeft, badgeTop),
            size = Size(badgeW, badgeH),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
        drawRoundRect(
            color = markerColor,
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
