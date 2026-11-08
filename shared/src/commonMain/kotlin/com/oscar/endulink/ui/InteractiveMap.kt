package com.oscar.endulink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
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
    val currentIsPickingOnMap by rememberUpdatedState(isPickingOnMap)

    Canvas(
        modifier = modifier
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
                        val tileSizePx = 256.0 * (2.0.pow(curZoom - zInt))
                        val (centerFracX, centerFracY) = TileMath.latLonToFractionalTile(curCenter.latitude, curCenter.longitude, zInt)

                        // Comprobar si se ha pulsado sobre algún waypoint (área táctil cómoda de 42 px)
                        val tappedWpt = curMarkers.lastOrNull { wpt ->
                            val (ptFracX, ptFracY) = TileMath.latLonToFractionalTile(wpt.latitude, wpt.longitude, zInt)
                            val unrotX = (ptFracX - centerFracX) * tileSizePx
                            val unrotY = (ptFracY - centerFracY) * tileSizePx
                            val screenX = screenCenterX + (unrotX * cos(rad) - unrotY * sin(rad))
                            val screenY = screenCenterY + (unrotX * sin(rad) + unrotY * cos(rad))
                            val pinHeadY = screenY - 20.0
                            val dx = tapOffset.x - screenX
                            val dy = tapOffset.y - pinHeadY
                            sqrt(dx * dx + dy * dy) <= 42.0
                        }

                        if (tappedWpt != null) {
                            currentOnSelectWaypoint(tappedWpt)
                        } else if (controller.droppedPin.value != null) {
                            controller.clearDroppedPin()
                        }
                    },
                    onLongPress = { offset ->
                        if (currentIsPickingOnMap) return@detectMapInteractions
                        // Pulsación larga: calcular coordenadas geográficas reales compensando centro, zoom y rumbo
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val coord = screenOffsetToGeoCoord(
                            screenX = offset.x,
                            screenY = offset.y,
                            width = w,
                            height = h,
                            center = controller.mapCenter.value,
                            zoom = controller.mapZoom.value,
                            heading = controller.mapHeading.value
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
                            val tileSizePx = 256.0 * (2.0.pow(curZ - zInt))

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

        // Fondo oscuro base
        drawRect(color = EnduPalette.Background)

        val zInt = zoom.toInt()
        val tileSizePx = (256.0 * (2.0.pow(zoom - zInt))).toFloat()
        val (centerFracX, centerFracY) = TileMath.latLonToFractionalTile(center.latitude, center.longitude, zInt)

        fun geoToScreen(coord: GeoCoord): Offset {
            val (ptFracX, ptFracY) = TileMath.latLonToFractionalTile(coord.latitude, coord.longitude, zInt)
            val dx = ((ptFracX - centerFracX) * tileSizePx).toFloat()
            val dy = ((ptFracY - centerFracY) * tileSizePx).toFloat()
            return Offset(screenCenter.x + dx, screenCenter.y + dy)
        }

        // Rotar toda la escena según el rumbo del mapa
        rotate(degrees = -heading.toFloat(), pivot = screenCenter) {

            // 1. Teselas raster (asíncronas, nunca bloquean UI)
            // 1. Teselas con radio dinámico (cubre el 100% de la pantalla incluso rotada)
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
                drawCircle(color = Color.Black, radius = 13f, center = pt)
                drawCircle(color = Color(0xFF00D4FF), radius = 11f, center = pt)
                drawCircle(color = Color.White, radius = 7.5f, center = pt)
                drawCircle(color = Color(0xFF0D1117), radius = 4f, center = pt)
            }

            // 11. Pin del Destino Final
            if (searchPin != null) {
                val destPos = geoToScreen(searchPin!!.coordinate)
                drawCircle(color = Color.Black, radius = 12f, center = destPos)
                drawCircle(color = EnduPalette.Accent, radius = 9.5f, center = destPos)
                drawCircle(color = Color.White, radius = 4f, center = destPos)
            }

            // 12. Chincheta de punto seleccionado en el mapa mediante pulsación larga
            if (droppedPin != null) {
                val pinPos = geoToScreen(droppedPin!!.coordinate)
                // Halo de pulsación translúcido
                drawCircle(color = EnduPalette.Accent.copy(alpha = 0.35f), radius = 24f, center = pinPos)
                drawCircle(color = Color.Black, radius = 12f, center = pinPos)
                drawCircle(color = Color(0xFFFF5252), radius = 9.5f, center = pinPos)
                drawCircle(color = Color.White, radius = 4f, center = pinPos)
            }

            // 13. Cursor de inspección del perfil de altitud
            scrubbedPoint?.let { scrub ->
                val scrubPos = geoToScreen(scrub.coordinate)
                drawCircle(color = Color.White, radius = 9f, center = scrubPos)
                drawCircle(color = EnduPalette.Accent, radius = 6f, center = scrubPos)
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
            drawCircle(color = EnduPalette.Accent.copy(alpha = 0.25f), radius = 22f, center = pinTip)
            drawCircle(color = Color.Black, radius = 5f, center = pinTip)
            drawCircle(color = Color.White, radius = 2.5f, center = pinTip)

            // Chincheta flotante que apunta directamente al centro exacto
            val pinHeadY = pinTip.y - 28f
            drawLine(color = Color.Black, start = Offset(pinTip.x, pinHeadY + 8f), end = pinTip, strokeWidth = 5f)
            drawLine(color = EnduPalette.Accent, start = Offset(pinTip.x, pinHeadY + 8f), end = pinTip, strokeWidth = 3f)

            drawCircle(color = Color.Black, radius = 12f, center = Offset(pinTip.x, pinHeadY))
            drawCircle(color = EnduPalette.Accent, radius = 9.5f, center = Offset(pinTip.x, pinHeadY))
            drawCircle(color = Color.White, radius = 4f, center = Offset(pinTip.x, pinHeadY))
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
    val gridPaint = Color(0xFF1E, 0x22, 0x2D)

    for (x in startX..endX) {
        if (x < 0 || x > maxTileIndex) continue
        for (y in startY..endY) {
            if (y < 0 || y > maxTileIndex) continue
            val posX = screenCenter.x + ((x - centerFracX) * tileSizePx).toFloat()
            val posY = screenCenter.y + ((y - centerFracY) * tileSizePx).toFloat()

            val tileCoord = TileCoord(layer, zoom, x, y)
            val bmp = tileStore.requestBitmap(tileCoord)

            if (bmp != null) {
                drawImage(
                    image = bmp,
                    dstOffset = IntOffset(posX.roundToInt(), posY.roundToInt()),
                    dstSize = IntSize(ceil(tileSizePx).toInt(), ceil(tileSizePx).toInt())
                )
            } else {
                // Algoritmo Parent Overzoom: escala la tesela padre desde RAM/Disco para evitar cuadros negros al hacer zoom
                val oz = tileStore.getOverzoomBitmap(tileCoord)
                var drewParent = false
                if (oz != null) {
                    val subW = oz.bitmap.width / oz.scale
                    val subH = oz.bitmap.height / oz.scale
                    drawImage(
                        image = oz.bitmap,
                        srcOffset = IntOffset(oz.subX * subW, oz.subY * subH),
                        srcSize = IntSize(subW, subH),
                        dstOffset = IntOffset(posX.roundToInt(), posY.roundToInt()),
                        dstSize = IntSize(ceil(tileSizePx).toInt(), ceil(tileSizePx).toInt())
                    )
                    drewParent = true
                }

                if (!drewParent) {
                    drawRect(
                        color = gridPaint,
                        topLeft = Offset(posX, posY),
                        size = androidx.compose.ui.geometry.Size(tileSizePx, tileSizePx),
                        style = Stroke(width = 1f)
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
            style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    } else {
        // Línea principal con contorno negro de alto contraste
        drawPath(
            path = path,
            color = Color(0xFF0E, 0x11, 0x17),
            style = Stroke(width = 10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path = path,
            color = baseColor,
            style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Inicio (Verde) y Fin (Rojo)
        val startPos = geoToScreen(coords.first())
        drawCircle(color = Color.Black, radius = 9f, center = startPos)
        drawCircle(color = EnduPalette.Success, radius = 7f, center = startPos)

        val endPos = geoToScreen(coords.last())
        drawCircle(color = Color.Black, radius = 9f, center = endPos)
        drawCircle(color = EnduPalette.Danger, radius = 7f, center = endPos)
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
        drawOval(
            color = Color.Black.copy(alpha = 0.35f),
            topLeft = Offset(pos.x - 7f, pos.y - 3f),
            size = Size(14f, 6f)
        )

        // 2. Vástago/aguja de la chincheta
        val pinHeadY = pos.y - 20f
        drawLine(
            color = Color.Black,
            start = Offset(pos.x, pinHeadY + 4f),
            end = pos,
            strokeWidth = 4.5f
        )
        drawLine(
            color = Color.White,
            start = Offset(pos.x, pinHeadY + 4f),
            end = pos,
            strokeWidth = 2.5f
        )

        // 3. Cabeza de la chincheta
        val pinCenter = Offset(pos.x, pinHeadY)
        drawCircle(color = Color.Black, radius = 13.5f, center = pinCenter)
        drawCircle(color = markerColor, radius = 11.5f, center = pinCenter)
        drawCircle(color = Color.White, radius = 6f, center = pinCenter)
        drawCircle(color = Color(0xFF0F172A), radius = 3.5f, center = pinCenter)

        // 4. Cartela flotante con el nombre del Waypoint
        val textLayout = textMeasurer.measure(
            text = wpt.name,
            style = TextStyle(
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        )
        val padH = 6f
        val padV = 3f
        val badgeW = textLayout.size.width + padH * 2
        val badgeH = textLayout.size.height + padV * 2
        val badgeLeft = pos.x - badgeW / 2f
        val badgeTop = pinHeadY - 14f - badgeH

        drawRoundRect(
            color = Color(0xEE0B111A),
            topLeft = Offset(badgeLeft, badgeTop),
            size = Size(badgeW, badgeH),
            cornerRadius = CornerRadius(5f, 5f)
        )
        drawRoundRect(
            color = markerColor,
            topLeft = Offset(badgeLeft, badgeTop),
            size = Size(badgeW, badgeH),
            cornerRadius = CornerRadius(5f, 5f),
            style = Stroke(width = 1.2f)
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
    rotate(degrees = riderAngle, pivot = pos) {
        val arrowPath = Path().apply {
            moveTo(pos.x, pos.y - 18f)
            lineTo(pos.x + 12f, pos.y + 12f)
            lineTo(pos.x, pos.y + 6f)
            lineTo(pos.x - 12f, pos.y + 12f)
            close()
        }
        drawPath(path = arrowPath, color = Color.Black, style = Stroke(width = 4f, join = StrokeJoin.Round))
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
    heading: Double
): GeoCoord {
    val screenCenterX = width / 2.0
    val screenCenterY = height / 2.0
    val dx = screenX - screenCenterX
    val dy = screenY - screenCenterY

    val zInt = zoom.toInt()
    val tileSizePx = 256.0 * (2.0.pow(zoom - zInt))
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
