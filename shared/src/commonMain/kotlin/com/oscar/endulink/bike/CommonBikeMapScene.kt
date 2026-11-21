package com.oscar.endulink.bike

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.oscar.endulink.group.GroupMember
import com.oscar.endulink.group.GroupTftLayout
import com.oscar.endulink.group.RiderStatus
import com.oscar.endulink.group.TftEdgeIndicator
import com.oscar.endulink.group.TftProjectedMember
import com.oscar.endulink.platform.BatteryStatus
import com.oscar.endulink.platform.RiderTelemetry
import com.oscar.endulink.platform.SynchronizedLock
import com.oscar.endulink.platform.getPlatformBatteryStatus
import com.oscar.endulink.platform.withLock
import com.oscar.endulink.tiles.MapLayer
import com.oscar.endulink.tiles.TileCoord
import com.oscar.endulink.tiles.TileMath
import com.oscar.endulink.tiles.TileStore
import com.oscar.endulink.trail.GPXRoute
import com.oscar.endulink.trail.GPXWaypoint
import com.oscar.endulink.trail.GeoCoord
import kotlinx.datetime.Clock
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

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

    private val routePath = Path()
    private val approachPath = Path()
    private val chevronPath = Path()
    private val arrowPath = Path()
    private val corePath = Path()
    private val boltPath = Path()

    private val bgPaint = Paint().apply {
        color = Color(20, 23, 28)
        style = PaintingStyle.Fill
    }
    private val blackEdgePaint = Paint().apply {
        color = Color.Black
        style = PaintingStyle.Fill
    }
    private val tilePaint = Paint().apply {
        isAntiAlias = true
        filterQuality = FilterQuality.Low
    }
    private val dirArrowHaloPaint = Paint().apply {
        color = Color(14, 17, 23)
        style = PaintingStyle.Stroke
        strokeCap = StrokeCap.Round
        strokeJoin = StrokeJoin.Round
        isAntiAlias = true
    }
    private val dirArrowFillPaint = Paint().apply {
        color = Color.White
        style = PaintingStyle.Stroke
        strokeCap = StrokeCap.Round
        strokeJoin = StrokeJoin.Round
        isAntiAlias = true
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
    private val groupFillPaint = Paint().apply {
        style = PaintingStyle.Fill
        isAntiAlias = true
    }
    private val groupStrokePaint = Paint().apply {
        style = PaintingStyle.Stroke
        isAntiAlias = true
    }
    private val groupAlertBgPaint = Paint().apply {
        style = PaintingStyle.Fill
        isAntiAlias = true
    }
    private val groupArrowPath = Path()

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
        routeTypeLabel: String? = null,
        groupMembers: List<GroupMember> = emptyList(),
        groupActiveCount: Int = 0
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
            routeTypeLabel = routeTypeLabel,
            groupMembers = groupMembers,
            groupActiveCount = groupActiveCount
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
        routeTypeLabel: String? = null,
        groupMembers: List<GroupMember> = emptyList(),
        groupActiveCount: Int = 0
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
            routeTypeLabel = routeTypeLabel,
            groupMembers = groupMembers,
            groupActiveCount = groupActiveCount
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
        routeTypeLabel: String?,
        groupMembers: List<GroupMember>,
        groupActiveCount: Int
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

        val zoom = config.resolveEffectiveZoom(rider.speedKmh)

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
        val maxRadius = ceil(hypot(w * 0.5, h * 0.68) / tileSizePx).toInt().coerceIn(1, 3)

        for (dx in -maxRadius..maxRadius) {
            val tx = centerTileX + dx
            if (tx < 0 || tx > maxTileIndex) continue
            for (dy in -maxRadius..maxRadius) {
                val ty = centerTileY + dy
                if (ty < 0 || ty > maxTileIndex) continue

                val x0 = floor((tx - centerFracX) * tileSizePx).toInt() - 1
                val y0 = floor((ty - centerFracY) * tileSizePx).toInt() - 1
                val x1 = floor(((tx + 1) - centerFracX) * tileSizePx).toInt() + 2
                val y1 = floor(((ty + 1) - centerFracY) * tileSizePx).toInt() + 2
                val dstOffset = IntOffset(x0, y0)
                val dstSize = IntSize((x1 - x0).coerceAtLeast(1), (y1 - y0).coerceAtLeast(1))

                val tileCoord = TileCoord(layer, zoom, tx, ty)
                val tileBmp = tileStore.requestBitmap(tileCoord)
                if (tileBmp != null) {
                    canvas.drawImageRect(
                        image = tileBmp,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(tileBmp.width, tileBmp.height),
                        dstOffset = dstOffset,
                        dstSize = dstSize,
                        paint = tilePaint
                    )
                } else {
                    val oz = tileStore.getOverzoomBitmap(tileCoord)
                    if (oz != null) {
                        val parentBmp = oz.bitmap
                        val srcX0 = (oz.subX * parentBmp.width) / oz.scale
                        val srcY0 = (oz.subY * parentBmp.height) / oz.scale
                        val srcX1 = ((oz.subX + 1) * parentBmp.width) / oz.scale
                        val srcY1 = ((oz.subY + 1) * parentBmp.height) / oz.scale
                        canvas.drawImageRect(
                            image = parentBmp,
                            srcOffset = IntOffset(srcX0, srcY0),
                            srcSize = IntSize((srcX1 - srcX0).coerceAtLeast(1), (srcY1 - srcY0).coerceAtLeast(1)),
                            dstOffset = dstOffset,
                            dstSize = dstSize,
                            paint = tilePaint
                        )
                    }
                }
            }
        }

        // 4. Polilíneas de rutas y flechas de sentido (sin asignaciones por frame)
        routePaint.strokeWidth = 10f * scale
        val maxBound = hypot(w, h) * 0.85f
        val maxArrowRange = hypot(w, h) * 0.78f
        val worldN = (1 shl zoom).toDouble()

        if (activeRoute != null && activeRoute.isVisible && activeRoute.points.size >= 2) {
            val nx = activeRoute.normMercatorX
            val ny = activeRoute.normMercatorY
            if (nx.size >= 2 && ny.size == nx.size) {
                val hasChevrons = buildClippedRouteAndChevronsFromNorm(
                    outPath = routePath,
                    nx = nx,
                    ny = ny,
                    worldN = worldN,
                    centerFracX = centerFracX,
                    centerFracY = centerFracY,
                    tileSizePx = tileSizePx,
                    maxBound = maxBound,
                    spacingPx = 88f * scale,
                    arrowLenPx = 8.5f * scale,
                    maxArrowRange = maxArrowRange
                )
                canvas.drawPath(routePath, routePaint)
                if (hasChevrons) drawBatchedChevrons(canvas, scale)
            }
        }

        approachPaint.strokeWidth = 9f * scale
        if (roadRouteCoordinates != null && roadRouteCoordinates.size >= 2) {
            val hasChevrons = buildClippedRouteAndChevronsFromCoords(
                outPath = approachPath,
                coords = roadRouteCoordinates,
                geoToLocal = ::geoToLocal,
                maxBound = maxBound,
                spacingPx = 88f * scale,
                arrowLenPx = 8.0f * scale,
                maxArrowRange = maxArrowRange
            )
            canvas.drawPath(approachPath, approachPaint)
            if (hasChevrons) drawBatchedChevrons(canvas, scale)
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

        // 4d. Compañeros de grupo en tiempo real (en pantalla y radar de bordes configurables)
        val groupPlan = if (groupMembers.isNotEmpty() && (config.showGroupMembersOnTft || config.showGroupAlertsOnTft)) {
            GroupTftLayout.compute(
                members = groupMembers,
                mapCenter = center,
                zoom = zoom,
                tileSizePx = tileSizePx,
                mapHeadingDeg = heading,
                anchorX = anchorX,
                anchorY = anchorY,
                width = w,
                height = h,
                scale = scale,
                nowMs = Clock.System.now().toEpochMilliseconds()
            )
        } else {
            null
        }

        if (config.showGroupMembersOnTft && groupPlan != null) {
            for (proj in groupPlan.inViewMembers) {
                drawGroupMemberPin(canvas, proj, config.showGroupNamesOnTft, scale)
            }
            if (config.showGroupEdgeRadarOnTft) {
                for (ind in groupPlan.edgeIndicators) {
                    drawGroupEdgeIndicator(canvas, ind, anchorX, anchorY, w, h, scale)
                }
            }
        }

        // 5. Flecha del piloto en (anchorX, anchorY)
        canvas.save()
        canvas.translate(anchorX, anchorY)
        if (config.orientation == "north") {
            val riderHead = if (rider.hasValidHeading) rider.headingDegrees else 0.0
            canvas.rotate(riderHead.toFloat())
        }

        arrowPath.reset()
        arrowPath.moveTo(0f, -18f * scale)
        arrowPath.lineTo(13f * scale, 14f * scale)
        arrowPath.lineTo(0f, 9f * scale)
        arrowPath.lineTo(-13f * scale, 14f * scale)
        arrowPath.close()
        canvas.drawPath(arrowPath, riderArrowPaint)

        corePath.reset()
        corePath.moveTo(0f, -10f * scale)
        corePath.lineTo(6f * scale, 9f * scale)
        corePath.lineTo(0f, 6f * scale)
        corePath.lineTo(-6f * scale, 9f * scale)
        corePath.close()
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
            boltPath.reset()
            boltPath.moveTo(bx + 4f * scale, by - 7f * scale)
            boltPath.lineTo(bx - 1f * scale, by + 1f * scale)
            boltPath.lineTo(bx + 3f * scale, by + 1f * scale)
            boltPath.lineTo(bx - 3f * scale, by + 8f * scale)
            boltPath.lineTo(bx + 1f * scale, by + 2f * scale)
            boltPath.lineTo(bx - 3f * scale, by + 2f * scale)
            boltPath.close()
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

        // 9b. Contador discreto de grupo en HUD (debajo de hora/batería)
        val grpBoxWidth = 86f * scale
        val grpLeft = w - grpBoxWidth - 14f * scale
        val grpTop = 56f * scale
        val grpBottom = 82f * scale
        if (groupActiveCount > 0 && config.showGroupHudBadgeOnTft) {
            canvas.drawRoundRect(grpLeft, grpTop, w - 14f * scale, grpBottom, 8f * scale, 8f * scale, hudCardPaint)
            canvas.drawRoundRect(grpLeft, grpTop, w - 14f * scale, grpBottom, 8f * scale, 8f * scale, hudBorderPaint)
            val hasAnyAlert = groupMembers.any { it.status.isAlert }
            groupFillPaint.color = if (hasAnyAlert) Color(255, 59, 48) else Color(48, 209, 88)
            canvas.drawCircle(Offset(grpLeft + 12f * scale, (grpTop + grpBottom) * 0.5f), 4f * scale, groupFillPaint)
            TftVectorText.drawText(
                canvas = canvas,
                text = "GRP $groupActiveCount",
                x = grpLeft + 21f * scale,
                y = grpBottom - 8f * scale,
                size = 11.5f * scale,
                color = Color.White,
                strokeW = 1.7f * scale
            )
        }

        // 9c. Banner destacado de alerta rápida (SOS / Avería / Esperadme)
        val alertBanner = if (config.showGroupAlertsOnTft) groupPlan?.alertBanner else null
        if (alertBanner != null) {
            val alertLeft = 14f * scale
            val maxAlertRight = if (groupActiveCount > 0 && config.showGroupHudBadgeOnTft) {
                grpLeft - 8f * scale
            } else {
                w - 14f * scale
            }
            val alertRight = minOf(maxAlertRight, alertLeft + 286f * scale)
            val alertTop = 62f * scale
            val alertBottom = 90f * scale
            if (alertRight > alertLeft + 40f * scale) {
                groupAlertBgPaint.color = when (alertBanner.status) {
                    RiderStatus.SOS -> Color(215, 38, 38, 240)
                    RiderStatus.MECHANICAL -> Color(225, 105, 0, 240)
                    else -> Color(195, 145, 0, 240)
                }
                groupStrokePaint.color = Color.White
                groupStrokePaint.strokeWidth = 1.6f * scale
                canvas.drawRoundRect(alertLeft, alertTop, alertRight, alertBottom, 8f * scale, 8f * scale, groupAlertBgPaint)
                canvas.drawRoundRect(alertLeft, alertTop, alertRight, alertBottom, 8f * scale, 8f * scale, groupStrokePaint)
                TftVectorText.drawText(
                    canvas = canvas,
                    text = alertBanner.text,
                    x = alertLeft + 10f * scale,
                    y = alertBottom - 8.5f * scale,
                    size = 11.5f * scale,
                    color = Color.White,
                    strokeW = 1.9f * scale
                )
            }
        }

        // 10. BlackEdges (4px arriba y abajo requeridos por Carbit EasyConn)
        val edgePx = (4f * scale).coerceAtLeast(2f)
        canvas.drawRect(0f, 0f, w, edgePx, blackEdgePaint)
        canvas.drawRect(0f, h - edgePx, w, h, blackEdgePaint)
    }

    private fun buildClippedRouteAndChevronsFromNorm(
        outPath: Path,
        nx: FloatArray,
        ny: FloatArray,
        worldN: Double,
        centerFracX: Double,
        centerFracY: Double,
        tileSizePx: Float,
        maxBound: Float,
        spacingPx: Float,
        arrowLenPx: Float,
        maxArrowRange: Float
    ): Boolean {
        outPath.reset()
        chevronPath.reset()
        if (nx.size < 2) return false

        var prevX = ((nx[0].toDouble() * worldN - centerFracX) * tileSizePx).toFloat()
        var prevY = ((ny[0].toDouble() * worldN - centerFracY) * tileSizePx).toFloat()
        var isPenDown = false
        var hasChevrons = false
        var accum = spacingPx * 0.55f
        val wingAngleOffset = 2.42f
        val lastIdx = nx.size - 1

        for (i in 1..lastIdx) {
            val bx = ((nx[i].toDouble() * worldN - centerFracX) * tileSizePx).toFloat()
            val by = ((ny[i].toDouble() * worldN - centerFracY) * tileSizePx).toFloat()
            val dx = bx - prevX
            val dy = by - prevY
            val segLen = hypot(dx, dy)
            if (i < lastIdx && segLen < 1.5f) continue
            if (segLen < 0.01f) continue

            val overlaps = maxOf(prevX, bx) >= -maxBound && minOf(prevX, bx) <= maxBound &&
                maxOf(prevY, by) >= -maxBound && minOf(prevY, by) <= maxBound
            if (overlaps) {
                if (!isPenDown) {
                    outPath.moveTo(prevX, prevY)
                    isPenDown = true
                }
                outPath.lineTo(bx, by)

                var d = spacingPx - accum
                if (d <= segLen) {
                    val dirAngle = atan2(dy, dx)
                    val cosDir = cos(dirAngle)
                    val sinDir = sin(dirAngle)
                    val cosL = cos(dirAngle + wingAngleOffset) * arrowLenPx
                    val sinL = sin(dirAngle + wingAngleOffset) * arrowLenPx
                    val cosR = cos(dirAngle - wingAngleOffset) * arrowLenPx
                    val sinR = sin(dirAngle - wingAngleOffset) * arrowLenPx
                    while (d <= segLen) {
                        val t = d / segLen
                        val px = prevX + dx * t
                        val py = prevY + dy * t
                        if (hypot(px, py) <= maxArrowRange) {
                            val tipX = px + cosDir * arrowLenPx * 0.35f
                            val tipY = py + sinDir * arrowLenPx * 0.35f
                            chevronPath.moveTo(tipX + cosL, tipY + sinL)
                            chevronPath.lineTo(tipX, tipY)
                            chevronPath.lineTo(tipX + cosR, tipY + sinR)
                            hasChevrons = true
                        }
                        d += spacingPx
                    }
                }
            } else {
                isPenDown = false
            }
            accum = (accum + segLen) % spacingPx
            prevX = bx
            prevY = by
        }
        return hasChevrons
    }

    private fun buildClippedRouteAndChevronsFromCoords(
        outPath: Path,
        coords: List<GeoCoord>,
        geoToLocal: (GeoCoord) -> Pair<Float, Float>,
        maxBound: Float,
        spacingPx: Float,
        arrowLenPx: Float,
        maxArrowRange: Float
    ): Boolean {
        outPath.reset()
        chevronPath.reset()
        if (coords.size < 2) return false

        val first = geoToLocal(coords[0])
        var prevX = first.first
        var prevY = first.second
        var isPenDown = false
        var hasChevrons = false
        var accum = spacingPx * 0.55f
        val wingAngleOffset = 2.42f
        val lastIdx = coords.size - 1

        for (i in 1..lastIdx) {
            val p = geoToLocal(coords[i])
            val bx = p.first
            val by = p.second
            val dx = bx - prevX
            val dy = by - prevY
            val segLen = hypot(dx, dy)
            if (i < lastIdx && segLen < 1.5f) continue
            if (segLen < 0.01f) continue

            val overlaps = maxOf(prevX, bx) >= -maxBound && minOf(prevX, bx) <= maxBound &&
                maxOf(prevY, by) >= -maxBound && minOf(prevY, by) <= maxBound
            if (overlaps) {
                if (!isPenDown) {
                    outPath.moveTo(prevX, prevY)
                    isPenDown = true
                }
                outPath.lineTo(bx, by)

                var d = spacingPx - accum
                if (d <= segLen) {
                    val dirAngle = atan2(dy, dx)
                    val cosDir = cos(dirAngle)
                    val sinDir = sin(dirAngle)
                    val cosL = cos(dirAngle + wingAngleOffset) * arrowLenPx
                    val sinL = sin(dirAngle + wingAngleOffset) * arrowLenPx
                    val cosR = cos(dirAngle - wingAngleOffset) * arrowLenPx
                    val sinR = sin(dirAngle - wingAngleOffset) * arrowLenPx
                    while (d <= segLen) {
                        val t = d / segLen
                        val px = prevX + dx * t
                        val py = prevY + dy * t
                        if (hypot(px, py) <= maxArrowRange) {
                            val tipX = px + cosDir * arrowLenPx * 0.35f
                            val tipY = py + sinDir * arrowLenPx * 0.35f
                            chevronPath.moveTo(tipX + cosL, tipY + sinL)
                            chevronPath.lineTo(tipX, tipY)
                            chevronPath.lineTo(tipX + cosR, tipY + sinR)
                            hasChevrons = true
                        }
                        d += spacingPx
                    }
                }
            } else {
                isPenDown = false
            }
            accum = (accum + segLen) % spacingPx
            prevX = bx
            prevY = by
        }
        return hasChevrons
    }

    private fun drawGroupMemberPin(
        canvas: Canvas,
        proj: TftProjectedMember,
        showNames: Boolean,
        scale: Float
    ) {
        val sx = proj.screenX
        val sy = proj.screenY
        val baseColor = Color(proj.member.color.argb)
        val fillColor = if (proj.isStale) baseColor.copy(alpha = 0.55f) else baseColor

        if (proj.member.status.isAlert) {
            val alertClr = when (proj.member.status) {
                RiderStatus.SOS -> Color(255, 59, 48)
                RiderStatus.MECHANICAL -> Color(255, 107, 0)
                else -> Color(255, 204, 0)
            }
            groupAlertBgPaint.color = alertClr.copy(alpha = 0.35f)
            canvas.drawCircle(Offset(sx, sy), 18f * scale, groupAlertBgPaint)
            groupStrokePaint.color = alertClr
            groupStrokePaint.strokeWidth = 2.2f * scale
            canvas.drawCircle(Offset(sx, sy), 16f * scale, groupStrokePaint)
        }

        groupFillPaint.color = Color.Black
        canvas.drawCircle(Offset(sx, sy), 11.5f * scale, groupFillPaint)
        groupFillPaint.color = fillColor
        canvas.drawCircle(Offset(sx, sy), 9.2f * scale, groupFillPaint)

        if (proj.member.speedKmh >= 3.0) {
            canvas.save()
            canvas.translate(sx, sy)
            canvas.rotate(proj.relativeHeadingDeg)
            groupArrowPath.reset()
            groupArrowPath.moveTo(0f, -6.2f * scale)
            groupArrowPath.lineTo(4.4f * scale, 4.5f * scale)
            groupArrowPath.lineTo(0f, 2.2f * scale)
            groupArrowPath.lineTo(-4.4f * scale, 4.5f * scale)
            groupArrowPath.close()
            groupFillPaint.color = Color.White
            canvas.drawPath(groupArrowPath, groupFillPaint)
            canvas.restore()
        } else {
            groupFillPaint.color = Color.White
            canvas.drawCircle(Offset(sx, sy), 3.3f * scale, groupFillPaint)
        }

        if (showNames) {
            val shortName = GroupTftLayout.formatShortName(proj.member.name)
            val txtSize = 9.5f * scale
            val txtW = TftVectorText.measureText(shortName, txtSize)
            val padX = 5f * scale
            val pillH = 16f * scale
            val left = sx - txtW * 0.5f - padX
            val right = sx + txtW * 0.5f + padX
            val bottom = sy - 14f * scale
            val top = bottom - pillH
            canvas.drawRoundRect(left, top, right, bottom, 4f * scale, 4f * scale, hudCardPaint)
            groupStrokePaint.color = fillColor
            groupStrokePaint.strokeWidth = 1.2f * scale
            canvas.drawRoundRect(left, top, right, bottom, 4f * scale, 4f * scale, groupStrokePaint)
            TftVectorText.drawText(
                canvas = canvas,
                text = shortName,
                x = left + padX,
                y = bottom - 4f * scale,
                size = txtSize,
                color = Color.White,
                strokeW = 1.4f * scale
            )
        }
    }

    private fun drawGroupEdgeIndicator(
        canvas: Canvas,
        ind: TftEdgeIndicator,
        anchorX: Float,
        anchorY: Float,
        w: Float,
        h: Float,
        scale: Float
    ) {
        val baseColor = when {
            ind.member.status == RiderStatus.SOS -> Color(255, 59, 48)
            ind.member.status == RiderStatus.MECHANICAL -> Color(255, 107, 0)
            ind.member.status == RiderStatus.STOPPED_WAIT -> Color(255, 204, 0)
            else -> Color(ind.member.color.argb)
        }
        val color = if (ind.isStale) baseColor.copy(alpha = 0.6f) else baseColor

        val deg = (ind.angleRad * 180.0 / PI).toFloat() + 90f
        canvas.save()
        canvas.translate(ind.edgeX, ind.edgeY)
        canvas.rotate(deg)
        groupArrowPath.reset()
        groupArrowPath.moveTo(0f, -9f * scale)
        groupArrowPath.lineTo(6.5f * scale, 6f * scale)
        groupArrowPath.lineTo(0f, 3f * scale)
        groupArrowPath.lineTo(-6.5f * scale, 6f * scale)
        groupArrowPath.close()
        groupFillPaint.color = Color.Black
        canvas.drawPath(groupArrowPath, groupFillPaint)
        groupStrokePaint.color = Color.Black
        groupStrokePaint.strokeWidth = 2.5f * scale
        canvas.drawPath(groupArrowPath, groupStrokePaint)
        groupFillPaint.color = color
        canvas.drawPath(groupArrowPath, groupFillPaint)
        canvas.restore()

        val txtSize = 9.5f * scale
        val txtW = TftVectorText.measureText(ind.label, txtSize)
        val padX = 5.5f * scale
        val pillW = txtW + padX * 2f
        val pillH = 17f * scale
        val inDx = anchorX - ind.edgeX
        val inDy = anchorY - ind.edgeY
        val inLen = hypot(inDx, inDy).coerceAtLeast(1f)
        val cx = ind.edgeX + (inDx / inLen) * 20f * scale
        val cy = ind.edgeY + (inDy / inLen) * 16f * scale

        val left = (cx - pillW * 0.5f).coerceIn(8f * scale, (w - pillW - 8f * scale).coerceAtLeast(8f * scale))
        val top = (cy - pillH * 0.5f).coerceIn(60f * scale, (h - pillH - 22f * scale).coerceAtLeast(60f * scale))
        val right = left + pillW
        val bottom = top + pillH

        canvas.drawRoundRect(left, top, right, bottom, 5f * scale, 5f * scale, hudCardPaint)
        groupStrokePaint.color = color
        groupStrokePaint.strokeWidth = 1.4f * scale
        canvas.drawRoundRect(left, top, right, bottom, 5f * scale, 5f * scale, groupStrokePaint)
        TftVectorText.drawText(
            canvas = canvas,
            text = ind.label,
            x = left + padX,
            y = bottom - 4.5f * scale,
            size = txtSize,
            color = Color.White,
            strokeW = 1.5f * scale
        )
    }

    private fun drawBatchedChevrons(canvas: Canvas, scale: Float) {
        dirArrowHaloPaint.strokeWidth = (4.4f * scale).coerceAtLeast(2.5f)
        dirArrowFillPaint.strokeWidth = (2.2f * scale).coerceAtLeast(1.4f)
        canvas.drawPath(chevronPath, dirArrowHaloPaint)
        canvas.drawPath(chevronPath, dirArrowFillPaint)
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
    private val glyphPath = Path()

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
        glyphPath.reset()

        for (rawCh in text) {
            val ch = normalizeChar(rawCh)
            if (ch != ' ') {
                buildGlyph(glyphPath, ch, curX, top, charW, charH)
            }
            curX += charW + gap
        }
        canvas.drawPath(glyphPath, strokePaint)
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
            '!' -> {
                p.moveTo(xm, y0)
                p.lineTo(xm, y0 + h * 0.62f)
                p.moveTo(xm, y1 - h * 0.14f)
                p.lineTo(xm, y1)
            }
        }
    }
}

