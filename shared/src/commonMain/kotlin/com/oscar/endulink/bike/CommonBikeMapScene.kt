package com.oscar.endulink.bike

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.oscar.endulink.platform.BatteryStatus
import com.oscar.endulink.platform.RiderTelemetry
import com.oscar.endulink.platform.SynchronizedLock
import com.oscar.endulink.platform.getPlatformBatteryStatus
import com.oscar.endulink.platform.withLock
import com.oscar.endulink.tiles.MapLayer
import com.oscar.endulink.tiles.TileCoord
import com.oscar.endulink.tiles.TileMath
import com.oscar.endulink.tiles.TileStore
import com.oscar.endulink.tiles.decodeByteArrayToBitmap
import com.oscar.endulink.trail.GPXRoute
import com.oscar.endulink.trail.GPXWaypoint
import com.oscar.endulink.trail.GeoCoord
import kotlinx.datetime.Clock
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Escena multiplataforma de renderizado TFT (Preview y Frame RGBA para streaming H.264).
 * Utiliza primitivas 100% Compose Multiplatform (`ImageBitmap`, `Canvas`, `Paint`, `Path`),
 * garantizando paridad visual 1:1 entre iOS (`motillOS`) y Android (`EnduLink`).
 */
class CommonBikeMapScene(private val tileStore: TileStore) {

    private val lock = SynchronizedLock()

    private var cachedBitmap: ImageBitmap? = null
    private var cachedCanvas: Canvas? = null
    private var intPixels: IntArray? = null
    private var rgbaBuffer: ByteArray? = null

    // Caché en memoria de teselas decodificadas (máx 128 entradas)
    private val decodedTileCache = LinkedHashMap<TileCoord, ImageBitmap>(128)

    private fun getCachedTile(coord: TileCoord): ImageBitmap? {
        val existing = decodedTileCache.remove(coord)
        if (existing != null) {
            decodedTileCache[coord] = existing
            return existing
        }
        return null
    }

    private fun putCachedTile(coord: TileCoord, bmp: ImageBitmap) {
        if (decodedTileCache.size >= 128) {
            val firstKey = decodedTileCache.keys.firstOrNull()
            if (firstKey != null) decodedTileCache.remove(firstKey)
        }
        decodedTileCache[coord] = bmp
    }

    private val bgPaint = Paint().apply {
        color = Color(20, 23, 28)
        style = PaintingStyle.Fill
    }
    private val blackEdgePaint = Paint().apply {
        color = Color.Black
        style = PaintingStyle.Fill
    }
    private val tilePaint = Paint().apply {
        isAntiAlias = false
        filterQuality = FilterQuality.Low
    }
    private val routePaint = Paint().apply {
        color = Color(255, 107, 0)
        style = PaintingStyle.Stroke
        strokeWidth = 10f
        strokeCap = StrokeCap.Round
        strokeJoin = StrokeJoin.Round
        isAntiAlias = true
    }
    private val approachPaint = Paint().apply {
        color = Color(0, 212, 255)
        style = PaintingStyle.Stroke
        strokeWidth = 9f
        strokeCap = StrokeCap.Round
        strokeJoin = StrokeJoin.Round
        isAntiAlias = true
    }
    private val hudCardPaint = Paint().apply {
        color = Color(22, 26, 34, 225)
        style = PaintingStyle.Fill
        isAntiAlias = true
    }
    private val hudBorderPaint = Paint().apply {
        color = Color(48, 54, 61, 160)
        style = PaintingStyle.Stroke
        strokeWidth = 1.5f
        isAntiAlias = true
    }
    private val riderArrowPaint = Paint().apply {
        color = Color(255, 107, 0)
        style = PaintingStyle.Fill
        isAntiAlias = true
    }
    private val riderArrowCorePaint = Paint().apply {
        color = Color.White
        style = PaintingStyle.Fill
        isAntiAlias = true
    }
    private val batteryOutlinePaint = Paint().apply {
        color = Color(160, 175, 195)
        style = PaintingStyle.Stroke
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val batteryFillPaint = Paint().apply {
        style = PaintingStyle.Fill
        isAntiAlias = true
    }
    private val batteryBoltPaint = Paint().apply {
        color = Color(0, 212, 255)
        style = PaintingStyle.Fill
        isAntiAlias = true
    }
    private val waypointPinPaint = Paint().apply {
        style = PaintingStyle.Fill
        isAntiAlias = true
    }

    private var lastBatteryCheckMs: Long = 0L
    private var cachedBatteryStatus: BatteryStatus = BatteryStatus()

    private fun getFreshBatteryStatus(): BatteryStatus {
        val now = Clock.System.now().toEpochMilliseconds()
        if (now - lastBatteryCheckMs > 2000L || cachedBatteryStatus.percentage < 0) {
            lastBatteryCheckMs = now
            cachedBatteryStatus = getPlatformBatteryStatus()
        }
        return cachedBatteryStatus
    }

    fun renderPreview(
        width: Int,
        height: Int,
        config: BikeMapConfig,
        rider: RiderTelemetry,
        layer: MapLayer,
        activeRoute: GPXRoute?,
        roadRouteCoordinates: List<GeoCoord>?,
        isNavigating: Boolean,
        remainingKm: Double?,
        nextInstruction: String?,
        waypoints: List<GPXWaypoint>,
        intermediateStops: List<GeoCoord>,
        remainingMinutes: Int? = null,
        routeTypeLabel: String? = null
    ): ImageBitmap = lock.withLock {
        val bmp = ImageBitmap(width, height, ImageBitmapConfig.Argb8888)
        val canvas = Canvas(bmp)
        drawScene(
            canvas = canvas,
            width = width.toFloat(),
            height = height.toFloat(),
            config = config,
            rider = rider,
            layer = layer,
            activeRoute = activeRoute,
            roadRouteCoordinates = roadRouteCoordinates,
            isNavigating = isNavigating,
            remainingKm = remainingKm,
            nextInstruction = nextInstruction,
            waypoints = waypoints,
            intermediateStops = intermediateStops,
            remainingMinutes = remainingMinutes,
            routeTypeLabel = routeTypeLabel
        )
        return bmp
    }

    fun renderRgba(
        width: Int,
        height: Int,
        config: BikeMapConfig,
        rider: RiderTelemetry,
        layer: MapLayer,
        activeRoute: GPXRoute?,
        roadRouteCoordinates: List<GeoCoord>?,
        isNavigating: Boolean,
        remainingKm: Double?,
        nextInstruction: String?,
        waypoints: List<GPXWaypoint>,
        intermediateStops: List<GeoCoord>,
        remainingMinutes: Int? = null,
        routeTypeLabel: String? = null
    ): ByteArray = lock.withLock {
        val cur = cachedBitmap
        val bmp = if (cur != null && cur.width == width && cur.height == height) {
            cur
        } else {
            ImageBitmap(width, height, ImageBitmapConfig.Argb8888).also {
                cachedBitmap = it
                cachedCanvas = Canvas(it)
                intPixels = IntArray(width * height)
                rgbaBuffer = ByteArray(width * height * 4)
            }
        }
        val canvas = cachedCanvas ?: Canvas(bmp).also { cachedCanvas = it }
        val pixels = intPixels ?: IntArray(width * height).also { intPixels = it }
        val buffer = rgbaBuffer ?: ByteArray(width * height * 4).also { rgbaBuffer = it }

        drawScene(
            canvas = canvas,
            width = width.toFloat(),
            height = height.toFloat(),
            config = config,
            rider = rider,
            layer = layer,
            activeRoute = activeRoute,
            roadRouteCoordinates = roadRouteCoordinates,
            isNavigating = isNavigating,
            remainingKm = remainingKm,
            nextInstruction = nextInstruction,
            waypoints = waypoints,
            intermediateStops = intermediateStops,
            remainingMinutes = remainingMinutes,
            routeTypeLabel = routeTypeLabel
        )

        bmp.readPixels(
            buffer = pixels,
            startX = 0,
            startY = 0,
            width = width,
            height = height
        )

        var outIdx = 0
        for (i in pixels.indices) {
            val argb = pixels[i]
            buffer[outIdx++] = ((argb ushr 16) and 0xFF).toByte() // R
            buffer[outIdx++] = ((argb ushr 8) and 0xFF).toByte()  // G
            buffer[outIdx++] = (argb and 0xFF).toByte()           // B
            buffer[outIdx++] = ((argb ushr 24) and 0xFF).toByte() // A
        }
        return buffer
    }

    private fun drawScene(
        canvas: Canvas,
        width: Float,
        height: Float,
        config: BikeMapConfig,
        rider: RiderTelemetry,
        layer: MapLayer,
        activeRoute: GPXRoute?,
        roadRouteCoordinates: List<GeoCoord>?,
        isNavigating: Boolean,
        remainingKm: Double?,
        nextInstruction: String?,
        waypoints: List<GPXWaypoint>,
        intermediateStops: List<GeoCoord>,
        remainingMinutes: Int?,
        routeTypeLabel: String?
    ) {
        val w = width
        val h = height
        val scale = (w / 592f).coerceIn(0.45f, 1.5f)

        // 1. Fondo oscuro base
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Centro del mapa (GPS actual -> primer punto ruta -> Madrid)
        val center = rider.coordinate
            ?: activeRoute?.points?.firstOrNull()?.coordinate
            ?: GeoCoord(40.4168, -3.7038)

        val zoom = if (config.autoZoomBySpeed) {
            when {
                rider.speedKmh < 25.0 -> 17
                rider.speedKmh < 75.0 -> 15
                else -> 13
            }
        } else {
            config.zoom
        }

        val heading = if (config.orientation == "north") {
            0.0
        } else {
            if (rider.hasValidHeading) rider.headingDegrees else 0.0
        }

        val anchorX = w * 0.5f
        val anchorY = h * 0.67f

        val tileSizePx = (256.0 * config.tileMagnification * scale).toFloat()
        val (centerFracX, centerFracY) = TileMath.latLonToFractionalTile(center.latitude, center.longitude, zoom)

        fun geoToLocal(coord: GeoCoord): Pair<Float, Float> {
            val (fx, fy) = TileMath.latLonToFractionalTile(coord.latitude, coord.longitude, zoom)
            val lx = ((fx - centerFracX) * tileSizePx).toFloat()
            val ly = ((fy - centerFracY) * tileSizePx).toFloat()
            return Pair(lx, ly)
        }

        // 3. Escena del mapa orientada al rumbo
        canvas.save()
        canvas.translate(anchorX, anchorY)
        canvas.rotate(-heading.toFloat())

        val centerTileX = floor(centerFracX).toInt()
        val centerTileY = floor(centerFracY).toInt()
        val maxTileIndex = if (zoom in 0..30) (1 shl zoom) - 1 else 0

        for (dx in -3..3) {
            val tx = centerTileX + dx
            if (tx < 0 || tx > maxTileIndex) continue
            for (dy in -3..3) {
                val ty = centerTileY + dy
                if (ty < 0 || ty > maxTileIndex) continue

                val x0 = floor((tx - centerFracX) * tileSizePx).toInt()
                val y0 = floor((ty - centerFracY) * tileSizePx).toInt()
                val x1 = kotlin.math.ceil(((tx + 1) - centerFracX) * tileSizePx).toInt() + 1
                val y1 = kotlin.math.ceil(((ty + 1) - centerFracY) * tileSizePx).toInt() + 1
                val dstOffset = IntOffset(x0, y0)
                val dstSize = IntSize((x1 - x0).coerceAtLeast(1), (y1 - y0).coerceAtLeast(1))

                val tileCoord = TileCoord(layer, zoom, tx, ty)
                var drewTile = false
                var tileBmp = getCachedTile(tileCoord)
                if (tileBmp == null) {
                    val bytes = tileStore.getTileBytes(tileCoord, allowAsyncFetch = true)
                    if (bytes != null && bytes.size > 100) {
                        tileBmp = decodeByteArrayToBitmap(bytes)
                        if (tileBmp != null) {
                            putCachedTile(tileCoord, tileBmp)
                        }
                    }
                }
                if (tileBmp != null) {
                    canvas.drawImageRect(
                        image = tileBmp,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(tileBmp.width, tileBmp.height),
                        dstOffset = dstOffset,
                        dstSize = dstSize,
                        paint = tilePaint
                    )
                    drewTile = true
                }

                if (!drewTile) {
                    val oz = tileStore.getOverzoomedParent(tileCoord)
                    if (oz != null && oz.parentTileBytes.size > 100) {
                        val parentCoord = TileCoord(layer, oz.parentZ, oz.parentX, oz.parentY)
                        var parentBmp = getCachedTile(parentCoord)
                        if (parentBmp == null) {
                            parentBmp = decodeByteArrayToBitmap(oz.parentTileBytes)
                            if (parentBmp != null) {
                                putCachedTile(parentCoord, parentBmp)
                            }
                        }
                        if (parentBmp != null) {
                            val subW = (parentBmp.width / oz.scale).coerceAtLeast(1)
                            val subH = (parentBmp.height / oz.scale).coerceAtLeast(1)
                            canvas.drawImageRect(
                                image = parentBmp,
                                srcOffset = IntOffset(oz.subX * subW, oz.subY * subH),
                                srcSize = IntSize(subW, subH),
                                dstOffset = dstOffset,
                                dstSize = dstSize,
                                paint = tilePaint
                            )
                        }
                    }
                }
            }
        }

        // 4. Polilíneas de rutas
        routePaint.strokeWidth = 10f * scale
        if (activeRoute != null && activeRoute.isVisible && activeRoute.points.size >= 2) {
            val path = Path()
            val first = geoToLocal(activeRoute.points[0].coordinate)
            path.moveTo(first.first, first.second)
            for (i in 1 until activeRoute.points.size) {
                val p = geoToLocal(activeRoute.points[i].coordinate)
                path.lineTo(p.first, p.second)
            }
            canvas.drawPath(path, routePaint)
        }

        approachPaint.strokeWidth = 9f * scale
        if (roadRouteCoordinates != null && roadRouteCoordinates.size >= 2) {
            val path = Path()
            val first = geoToLocal(roadRouteCoordinates[0])
            path.moveTo(first.first, first.second)
            for (i in 1 until roadRouteCoordinates.size) {
                val p = geoToLocal(roadRouteCoordinates[i])
                path.lineTo(p.first, p.second)
            }
            canvas.drawPath(path, approachPaint)
        }

        // 4b. Paradas intermedias
        for (stopCoord in intermediateStops) {
            val (sx, sy) = geoToLocal(stopCoord)
            if (sx in -w..w && sy in -h..h) {
                waypointPinPaint.color = Color.Black
                canvas.drawCircle(Offset(sx, sy), 10f * scale, waypointPinPaint)
                waypointPinPaint.color = Color(0, 212, 255)
                canvas.drawCircle(Offset(sx, sy), 8f * scale, waypointPinPaint)
                waypointPinPaint.color = Color.White
                canvas.drawCircle(Offset(sx, sy), 4f * scale, waypointPinPaint)
            }
        }

        // 4c. Waypoints
        val allWaypoints = if (activeRoute != null && activeRoute.isVisible) {
            activeRoute.waypoints + waypoints
        } else {
            waypoints
        }
        for (wpt in allWaypoints) {
            val (wx, wy) = geoToLocal(wpt.coordinate)
            if (wx in -w..w && wy in -h..h) {
                val sym = wpt.symbol?.lowercase().orEmpty()
                val pinColor = when {
                    sym.contains("danger") || sym.contains("hazard") || sym.contains("alert") -> Color(255, 69, 58)
                    sym.contains("gate") || sym.contains("obstacle") || sym.contains("rock") -> Color(255, 159, 10)
                    sym.contains("water") || sym.contains("fountain") -> Color(0, 212, 255)
                    sym.contains("fuel") || sym.contains("gas") -> Color(48, 209, 88)
                    else -> Color(0, 229, 255)
                }
                waypointPinPaint.color = Color.Black
                canvas.drawCircle(Offset(wx, wy), 9.5f * scale, waypointPinPaint)
                waypointPinPaint.color = pinColor
                canvas.drawCircle(Offset(wx, wy), 7.5f * scale, waypointPinPaint)
                waypointPinPaint.color = Color.White
                canvas.drawCircle(Offset(wx, wy), 3f * scale, waypointPinPaint)
            }
        }

        canvas.restore()

        // 5. Flecha del piloto en (anchorX, anchorY)
        canvas.save()
        canvas.translate(anchorX, anchorY)
        if (config.orientation == "north") {
            val riderHead = if (rider.hasValidHeading) rider.headingDegrees else 0.0
            canvas.rotate(riderHead.toFloat())
        }

        val arrowPath = Path().apply {
            moveTo(0f, -18f * scale)
            lineTo(13f * scale, 14f * scale)
            lineTo(0f, 9f * scale)
            lineTo(-13f * scale, 14f * scale)
            close()
        }
        canvas.drawPath(arrowPath, riderArrowPaint)

        val corePath = Path().apply {
            moveTo(0f, -10f * scale)
            lineTo(6f * scale, 9f * scale)
            lineTo(0f, 6f * scale)
            lineTo(-6f * scale, 9f * scale)
            close()
        }
        canvas.drawPath(corePath, riderArrowCorePaint)
        canvas.restore()

        // 6. HUD Velocímetro (Esquina inferior izquierda)
        val speedLeft = 14f * scale
        val speedTop = h - 82f * scale
        val speedRight = 152f * scale
        val speedBottom = h - 14f * scale
        val rCard = 12f * scale
        canvas.drawRoundRect(speedLeft, speedTop, speedRight, speedBottom, rCard, rCard, hudCardPaint)
        canvas.drawRoundRect(speedLeft, speedTop, speedRight, speedBottom, rCard, rCard, hudBorderPaint)

        val spdStr = "${rider.speedKmh.toInt()}"
        val spdSize = 36f * scale
        val spdWidth = TftVectorText.measureText(spdStr, spdSize)
        TftVectorText.drawText(
            canvas = canvas,
            text = spdStr,
            x = speedLeft + 14f * scale,
            y = speedBottom - 16f * scale,
            size = spdSize,
            color = Color.White,
            strokeW = 3.6f * scale
        )
        TftVectorText.drawText(
            canvas = canvas,
            text = "KM/H",
            x = speedLeft + 18f * scale + spdWidth,
            y = speedBottom - 16f * scale,
            size = 11.5f * scale,
            color = Color(255, 107, 0),
            strokeW = 1.8f * scale
        )

        // 7. HUD Altitud (Esquina inferior derecha, siempre visible aunque haya enlace o ruta)
        val infoWidth = 146f * scale
        val infoHeight = 58f * scale
        val infoLeft = w - infoWidth - 14f * scale
        val infoTop = h - infoHeight - 14f * scale
        val infoRight = w - 14f * scale
        val infoBottom = h - 14f * scale
        canvas.drawRoundRect(infoLeft, infoTop, infoRight, infoBottom, rCard, rCard, hudCardPaint)
        canvas.drawRoundRect(infoLeft, infoTop, infoRight, infoBottom, rCard, rCard, hudBorderPaint)

        TftVectorText.drawText(
            canvas = canvas,
            text = "ALTITUD",
            x = infoLeft + 12f * scale,
            y = infoTop + 21f * scale,
            size = 10.5f * scale,
            color = Color(139, 148, 158),
            strokeW = 1.5f * scale
        )
        TftVectorText.drawText(
            canvas = canvas,
            text = "${rider.altitudeM.toInt()} M",
            x = infoLeft + 12f * scale,
            y = infoBottom - 14f * scale,
            size = 15.5f * scale,
            color = Color.White,
            strokeW = 2.1f * scale
        )

        // 8. HUD Batería y Hora del móvil en tiempo real (Esquina superior derecha)
        val battStatus = getFreshBatteryStatus()
        val pct = if (battStatus.percentage >= 0) battStatus.percentage.coerceIn(0, 100) else 100
        val isCharging = battStatus.isCharging

        val battBoxWidth = (if (isCharging) 108f else 96f) * scale
        val battLeft = w - battBoxWidth - 14f * scale
        val battTop = 12f * scale
        val battRight = w - 14f * scale
        val battBottom = 50f * scale
        val rBatt = 10f * scale
        canvas.drawRoundRect(battLeft, battTop, battRight, battBottom, rBatt, rBatt, hudCardPaint)
        canvas.drawRoundRect(battLeft, battTop, battRight, battBottom, rBatt, rBatt, hudBorderPaint)

        // Hora actual justo a la izquierda de la batería
        val clockBoxWidth = 66f * scale
        val clockRight = battLeft - 8f * scale
        val clockLeft = clockRight - clockBoxWidth
        canvas.drawRoundRect(clockLeft, battTop, clockRight, battBottom, rBatt, rBatt, hudCardPaint)
        canvas.drawRoundRect(clockLeft, battTop, clockRight, battBottom, rBatt, rBatt, hudBorderPaint)
        val clockStr = currentTftClockHm()
        val clockTextSize = 13.5f * scale
        val clockTextW = TftVectorText.measureText(clockStr, clockTextSize)
        TftVectorText.drawText(
            canvas = canvas,
            text = clockStr,
            x = clockLeft + ((clockBoxWidth - clockTextW) * 0.5f).coerceAtLeast(6f * scale),
            y = battBottom - 13f * scale,
            size = clockTextSize,
            color = Color.White,
            strokeW = 1.9f * scale
        )

        val iconLeft = battLeft + 10f * scale
        val iconTop = battTop + 12f * scale
        val iconRight = iconLeft + 22f * scale
        val iconBottom = iconTop + 14f * scale
        batteryOutlinePaint.strokeWidth = 1.8f * scale
        canvas.drawRoundRect(iconLeft, iconTop, iconRight, iconBottom, 3f * scale, 3f * scale, batteryOutlinePaint)
        canvas.drawRoundRect(
            iconRight + 1f * scale,
            iconTop + 4f * scale,
            iconRight + 3.5f * scale,
            iconBottom - 4f * scale,
            1f * scale,
            1f * scale,
            batteryOutlinePaint
        )

        val fillPad = 2.5f * scale
        val maxFillW = (iconRight - iconLeft) - (fillPad * 2f)
        val fillW = maxFillW * (pct / 100f)
        if (fillW > 1f) {
            batteryFillPaint.color = when {
                isCharging -> Color(0, 212, 255)
                pct <= 15 -> Color(255, 69, 58)
                pct <= 30 -> Color(255, 159, 10)
                else -> Color(48, 209, 88)
            }
            canvas.drawRoundRect(
                iconLeft + fillPad,
                iconTop + fillPad,
                iconLeft + fillPad + fillW,
                iconBottom - fillPad,
                2f * scale,
                2f * scale,
                batteryFillPaint
            )
        }

        val pctStr = "$pct%"
        val textX = if (isCharging) {
            val bx = iconRight + 9f * scale
            val by = (battTop + battBottom) * 0.5f
            val boltPath = Path().apply {
                moveTo(bx + 4f * scale, by - 7f * scale)
                lineTo(bx - 1f * scale, by + 1f * scale)
                lineTo(bx + 3f * scale, by + 1f * scale)
                lineTo(bx - 3f * scale, by + 8f * scale)
                lineTo(bx + 1f * scale, by + 2f * scale)
                lineTo(bx - 3f * scale, by + 2f * scale)
                close()
            }
            canvas.drawPath(boltPath, batteryBoltPaint)
            iconRight + 19f * scale
        } else {
            iconRight + 8f * scale
        }
        TftVectorText.drawText(
            canvas = canvas,
            text = pctStr,
            x = textX,
            y = battBottom - 13f * scale,
            size = 13.5f * scale,
            color = Color.White,
            strokeW = 1.9f * scale
        )

        // 9. HUD Resumen de Enlace / Ruta / GPX (Esquina superior izquierda, sin instrucciones de giro)
        val hasRoadTiming = remainingKm != null && remainingKm > 0.0 && remainingMinutes != null
        val hasGpxRemaining = !hasRoadTiming && remainingKm != null && (remainingKm > 0.0 || routeTypeLabel == "TRACK")
        if (hasRoadTiming || hasGpxRemaining) {
            val navLeft = 14f * scale
            val maxNavRight = clockLeft - 10f * scale
            val desiredW = (if (hasRoadTiming) 244f else 156f) * scale
            val navRight = minOf(maxNavRight, navLeft + desiredW)
            val navBottom = 56f * scale
            canvas.drawRoundRect(navLeft, battTop, navRight, navBottom, rBatt, rBatt, hudCardPaint)
            canvas.drawRoundRect(navLeft, battTop, navRight, navBottom, rBatt, rBatt, hudBorderPaint)

            val (topLbl, mainVal) = if (hasRoadTiming && remainingKm != null && remainingMinutes != null) {
                val kind = routeTypeLabel ?: "RUTA"
                val etaStr = formatTftEtaHm(remainingMinutes)
                val kmStr = "${(remainingKm * 10.0).roundToInt() / 10.0} KM"
                val durStr = formatTftDuration(remainingMinutes)
                "$kind - LLEGADA $etaStr" to "$kmStr - $durStr"
            } else {
                val kmStr = "${((remainingKm ?: 0.0) * 10.0).roundToInt() / 10.0} KM"
                "GPX - RESTAN" to kmStr
            }

            TftVectorText.drawText(
                canvas = canvas,
                text = topLbl,
                x = navLeft + 10f * scale,
                y = battTop + 17f * scale,
                size = 9.5f * scale,
                color = Color(255, 107, 0),
                strokeW = 1.4f * scale
            )
            TftVectorText.drawText(
                canvas = canvas,
                text = mainVal,
                x = navLeft + 10f * scale,
                y = navBottom - 10f * scale,
                size = 12.5f * scale,
                color = Color.White,
                strokeW = 1.8f * scale
            )
        }

        // 10. BlackEdges (4px arriba y abajo requeridos por Carbit EasyConn)
        val edgePx = (4f * scale).coerceAtLeast(2f)
        canvas.drawRect(0f, 0f, w, edgePx, blackEdgePaint)
        canvas.drawRect(0f, h - edgePx, w, h, blackEdgePaint)
    }
}

/**
 * Renderizador vectorial ligero y nítido de caracteres alfanuméricos para el HUD TFT en `commonMain`.
 * No depende de fuentes nativas del sistema operativo y funciona de forma idéntica en iOS y Android.
 */
internal object TftVectorText {

    private val strokePaint = Paint().apply {
        style = PaintingStyle.Stroke
        strokeCap = StrokeCap.Round
        strokeJoin = StrokeJoin.Round
        isAntiAlias = true
    }

    fun measureText(text: String, size: Float): Float {
        if (text.isEmpty()) return 0f
        val charW = size * 0.56f
        val gap = size * 0.16f
        return text.length * (charW + gap) - gap
    }

    fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Color,
        strokeW: Float
    ) {
        if (text.isEmpty()) return
        strokePaint.color = color
        strokePaint.strokeWidth = strokeW

        val charW = size * 0.56f
        val charH = size * 0.78f
        val gap = size * 0.16f
        val top = y - charH
        var curX = x

        for (rawCh in text) {
            val ch = normalizeChar(rawCh)
            if (ch != ' ') {
                val path = Path()
                buildGlyph(path, ch, curX, top, charW, charH)
                canvas.drawPath(path, strokePaint)
            }
            curX += charW + gap
        }
    }

    private fun normalizeChar(c: Char): Char {
        return when (c.uppercaseChar()) {
            'Á', 'À', 'Ä' -> 'A'
            'É', 'È', 'Ë' -> 'E'
            'Í', 'Ì', 'Ï' -> 'I'
            'Ó', 'Ò', 'Ö' -> 'O'
            'Ú', 'Ù', 'Ü' -> 'U'
            'Ñ' -> 'N'
            else -> c.uppercaseChar()
        }
    }

    private fun buildGlyph(p: Path, c: Char, x: Float, y: Float, w: Float, h: Float) {
        val x0 = x
        val x1 = x + w
        val xm = x + w * 0.5f
        val y0 = y
        val y1 = y + h
        val ym = y + h * 0.5f

        when (c) {
            '0', 'O' -> {
                p.moveTo(x0, y0)
                p.lineTo(x1, y0)
                p.lineTo(x1, y1)
                p.lineTo(x0, y1)
                p.close()
            }
            '1', 'I' -> {
                p.moveTo(xm, y0)
                p.lineTo(xm, y1)
            }
            '2' -> {
                p.moveTo(x0, y0)
                p.lineTo(x1, y0)
                p.lineTo(x1, ym)
                p.lineTo(x0, ym)
                p.lineTo(x0, y1)
                p.lineTo(x1, y1)
            }
            '3' -> {
                p.moveTo(x0, y0)
                p.lineTo(x1, y0)
                p.lineTo(x1, y1)
                p.lineTo(x0, y1)
                p.moveTo(x0 + w * 0.15f, ym)
                p.lineTo(x1, ym)
            }
            '4' -> {
                p.moveTo(x0, y0)
                p.lineTo(x0, ym)
                p.lineTo(x1, ym)
                p.moveTo(x1, y0)
                p.lineTo(x1, y1)
            }
            '5', 'S' -> {
                p.moveTo(x1, y0)
                p.lineTo(x0, y0)
                p.lineTo(x0, ym)
                p.lineTo(x1, ym)
                p.lineTo(x1, y1)
                p.lineTo(x0, y1)
            }
            '6' -> {
                p.moveTo(x1, y0)
                p.lineTo(x0, y0)
                p.lineTo(x0, y1)
                p.lineTo(x1, y1)
                p.lineTo(x1, ym)
                p.lineTo(x0, ym)
            }
            '7' -> {
                p.moveTo(x0, y0)
                p.lineTo(x1, y0)
                p.lineTo(xm, y1)
            }
            '8', 'B' -> {
                p.moveTo(x0, y0)
                p.lineTo(x1, y0)
                p.lineTo(x1, y1)
                p.lineTo(x0, y1)
                p.close()
                p.moveTo(x0, ym)
                p.lineTo(x1, ym)
            }
            '9' -> {
                p.moveTo(x0, y1)
                p.lineTo(x1, y1)
                p.lineTo(x1, y0)
                p.lineTo(x0, y0)
                p.lineTo(x0, ym)
                p.lineTo(x1, ym)
            }
            'A' -> {
                p.moveTo(x0, y1)
                p.lineTo(x0, y0)
                p.lineTo(x1, y0)
                p.lineTo(x1, y1)
                p.moveTo(x0, ym)
                p.lineTo(x1, ym)
            }
            'C' -> {
                p.moveTo(x1, y0)
                p.lineTo(x0, y0)
                p.lineTo(x0, y1)
                p.lineTo(x1, y1)
            }
            'D' -> {
                p.moveTo(x0, y0)
                p.lineTo(x0 + w * 0.75f, y0)
                p.lineTo(x1, ym)
                p.lineTo(x0 + w * 0.75f, y1)
                p.lineTo(x0, y1)
                p.close()
            }
            'E' -> {
                p.moveTo(x1, y0)
                p.lineTo(x0, y0)
                p.lineTo(x0, y1)
                p.lineTo(x1, y1)
                p.moveTo(x0, ym)
                p.lineTo(x1 - w * 0.15f, ym)
            }
            'F' -> {
                p.moveTo(x1, y0)
                p.lineTo(x0, y0)
                p.lineTo(x0, y1)
                p.moveTo(x0, ym)
                p.lineTo(x1 - w * 0.15f, ym)
            }
            'G' -> {
                p.moveTo(x1, y0)
                p.lineTo(x0, y0)
                p.lineTo(x0, y1)
                p.lineTo(x1, y1)
                p.lineTo(x1, ym)
                p.lineTo(xm, ym)
            }
            'H' -> {
                p.moveTo(x0, y0)
                p.lineTo(x0, y1)
                p.moveTo(x1, y0)
                p.lineTo(x1, y1)
                p.moveTo(x0, ym)
                p.lineTo(x1, ym)
            }
            'J' -> {
                p.moveTo(x1, y0)
                p.lineTo(x1, y1)
                p.lineTo(x0, y1)
                p.lineTo(x0, ym)
            }
            'K' -> {
                p.moveTo(x0, y0)
                p.lineTo(x0, y1)
                p.moveTo(x1, y0)
                p.lineTo(x0, ym)
                p.lineTo(x1, y1)
            }
            'L' -> {
                p.moveTo(x0, y0)
                p.lineTo(x0, y1)
                p.lineTo(x1, y1)
            }
            'M' -> {
                p.moveTo(x0, y1)
                p.lineTo(x0, y0)
                p.lineTo(xm, ym)
                p.lineTo(x1, y0)
                p.lineTo(x1, y1)
            }
            'N' -> {
                p.moveTo(x0, y1)
                p.lineTo(x0, y0)
                p.lineTo(x1, y1)
                p.lineTo(x1, y0)
            }
            'P' -> {
                p.moveTo(x0, y1)
                p.lineTo(x0, y0)
                p.lineTo(x1, y0)
                p.lineTo(x1, ym)
                p.lineTo(x0, ym)
            }
            'Q' -> {
                p.moveTo(x0, y0)
                p.lineTo(x1, y0)
                p.lineTo(x1, y1)
                p.lineTo(x0, y1)
                p.close()
                p.moveTo(xm, ym)
                p.lineTo(x1, y1)
            }
            'R' -> {
                p.moveTo(x0, y1)
                p.lineTo(x0, y0)
                p.lineTo(x1, y0)
                p.lineTo(x1, ym)
                p.lineTo(x0, ym)
                p.moveTo(xm, ym)
                p.lineTo(x1, y1)
            }
            'T' -> {
                p.moveTo(x0, y0)
                p.lineTo(x1, y0)
                p.moveTo(xm, y0)
                p.lineTo(xm, y1)
            }
            'U' -> {
                p.moveTo(x0, y0)
                p.lineTo(x0, y1)
                p.lineTo(x1, y1)
                p.lineTo(x1, y0)
            }
            'V' -> {
                p.moveTo(x0, y0)
                p.lineTo(xm, y1)
                p.lineTo(x1, y0)
            }
            'W' -> {
                p.moveTo(x0, y0)
                p.lineTo(x0 + w * 0.25f, y1)
                p.lineTo(xm, ym)
                p.lineTo(x0 + w * 0.75f, y1)
                p.lineTo(x1, y0)
            }
            'X' -> {
                p.moveTo(x0, y0)
                p.lineTo(x1, y1)
                p.moveTo(x1, y0)
                p.lineTo(x0, y1)
            }
            'Y' -> {
                p.moveTo(x0, y0)
                p.lineTo(xm, ym)
                p.lineTo(x1, y0)
                p.moveTo(xm, ym)
                p.lineTo(xm, y1)
            }
            'Z' -> {
                p.moveTo(x0, y0)
                p.lineTo(x1, y0)
                p.lineTo(x0, y1)
                p.lineTo(x1, y1)
            }
            '/' -> {
                p.moveTo(x0, y1)
                p.lineTo(x1, y0)
            }
            '%' -> {
                p.moveTo(x0, y1)
                p.lineTo(x1, y0)
                p.moveTo(x0, y0)
                p.lineTo(x0 + w * 0.25f, y0)
                p.lineTo(x0 + w * 0.25f, y0 + h * 0.25f)
                p.lineTo(x0, y0 + h * 0.25f)
                p.close()
                p.moveTo(x1 - w * 0.25f, y1 - h * 0.25f)
                p.lineTo(x1, y1 - h * 0.25f)
                p.lineTo(x1, y1)
                p.lineTo(x1 - w * 0.25f, y1)
                p.close()
            }
            '.', ',' -> {
                p.moveTo(xm, y1 - h * 0.12f)
                p.lineTo(xm, y1)
            }
            ':' -> {
                p.moveTo(xm, y0 + h * 0.25f)
                p.lineTo(xm, y0 + h * 0.32f)
                p.moveTo(xm, y1 - h * 0.25f)
                p.lineTo(xm, y1 - h * 0.18f)
            }
            '-' -> {
                p.moveTo(x0, ym)
                p.lineTo(x1, ym)
            }
            '+' -> {
                p.moveTo(x0, ym)
                p.lineTo(x1, ym)
                p.moveTo(xm, y0 + h * 0.2f)
                p.lineTo(xm, y1 - h * 0.2f)
            }
        }
    }
}

