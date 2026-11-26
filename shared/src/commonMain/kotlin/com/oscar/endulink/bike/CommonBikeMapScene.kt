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
import com.oscar.endulink.platform.TftNotificationCategory
import com.oscar.endulink.platform.TftNotificationTextLayout
import com.oscar.endulink.platform.TftPhoneNotification
import com.oscar.endulink.platform.getPlatformBatteryStatus
import com.oscar.endulink.platform.withLock
import com.oscar.endulink.tiles.MapLayer
import com.oscar.endulink.tiles.TileCoord
import com.oscar.endulink.tiles.TileMath
import com.oscar.endulink.tiles.TileStore
import com.oscar.endulink.trail.GPXRoute
import com.oscar.endulink.trail.GPXWaypoint
import com.oscar.endulink.trail.GeoCoord
import com.oscar.endulink.trail.RouteNavigator
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
    private val notifBgPaint = Paint().apply {
        style = PaintingStyle.Fill
        isAntiAlias = true
    }
    private val notifStrokePaint = Paint().apply {
        style = PaintingStyle.Stroke
        isAntiAlias = true
    }
    private val notifIconStrokePaint = Paint().apply {
        color = Color.White
        style = PaintingStyle.Stroke
        strokeCap = StrokeCap.Round
        strokeJoin = StrokeJoin.Round
        isAntiAlias = true
    }
    private val notifIconFillPaint = Paint().apply {
        color = Color.White
        style = PaintingStyle.Fill
        isAntiAlias = true
    }
    private val gpxReturnHaloPaint = Paint().apply {
        color = Color(14, 17, 23, 240)
        style = PaintingStyle.Stroke
        strokeCap = StrokeCap.Round
        isAntiAlias = true
    }
    private val gpxReturnLinePaint = Paint().apply {
        color = Color(255, 214, 10)
        style = PaintingStyle.Stroke
        strokeCap = StrokeCap.Round
        isAntiAlias = true
    }
    private val gpxReturnWarnBgPaint = Paint().apply {
        color = Color(180, 96, 0, 244)
        style = PaintingStyle.Fill
        isAntiAlias = true
    }
    private val gpxReturnWarnStrokePaint = Paint().apply {
        color = Color(255, 214, 10)
        style = PaintingStyle.Stroke
        isAntiAlias = true
    }
    private val notifIconPath = Path()

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
        groupActiveCount: Int = 0,
        activeNotification: TftPhoneNotification? = null,
        gpxOffTrackTarget: GeoCoord? = null,
        gpxOffTrackMeters: Double? = null
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
            groupActiveCount = groupActiveCount,
            activeNotification = activeNotification,
            gpxOffTrackTarget = gpxOffTrackTarget,
            gpxOffTrackMeters = gpxOffTrackMeters
        )
        return bmp
    }

    fun renderBgraInts(
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
        groupActiveCount: Int = 0,
        activeNotification: TftPhoneNotification? = null,
        gpxOffTrackTarget: GeoCoord? = null,
        gpxOffTrackMeters: Double? = null
    ): IntArray = lock.withLock {
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
            groupActiveCount = groupActiveCount,
            activeNotification = activeNotification,
            gpxOffTrackTarget = gpxOffTrackTarget,
            gpxOffTrackMeters = gpxOffTrackMeters
        )

        bmp.readPixels(
            buffer = pixels,
            startX = 0,
            startY = 0,
            width = width,
            height = height
        )
        return pixels
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
        groupActiveCount: Int = 0,
        activeNotification: TftPhoneNotification? = null,
        gpxOffTrackTarget: GeoCoord? = null,
        gpxOffTrackMeters: Double? = null
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
            groupActiveCount = groupActiveCount,
            activeNotification = activeNotification,
            gpxOffTrackTarget = gpxOffTrackTarget,
            gpxOffTrackMeters = gpxOffTrackMeters
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
        groupActiveCount: Int,
        activeNotification: TftPhoneNotification?,
        gpxOffTrackTarget: GeoCoord?,
        gpxOffTrackMeters: Double?
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
        val ts = config.tftHudFontScale.coerceIn(1.0f, 1.4f)
        val strokeScale = config.trackStrokeScale.coerceIn(0.75f, 1.5f)
        val showArrows = config.showTrackDirectionArrows
        routePaint.strokeWidth = 10f * scale * strokeScale
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
                if (showArrows && hasChevrons) drawBatchedChevrons(canvas, scale)
            }
        }

        approachPaint.strokeWidth = 9f * scale * strokeScale
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
            if (showArrows && hasChevrons) drawBatchedChevrons(canvas, scale)
        }

        // 4a. Ruta en línea recta al punto más cercano del GPX cuando te sales del track
        if (gpxOffTrackTarget != null && rider.coordinate != null) {
            val (rx, ry) = geoToLocal(rider.coordinate)
            val (tx, ty) = geoToLocal(gpxOffTrackTarget)
            gpxReturnHaloPaint.strokeWidth = 8.5f * scale
            gpxReturnLinePaint.strokeWidth = 4.8f * scale
            gpxReturnLinePaint.pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f * scale, 9f * scale), 0f)
            canvas.drawLine(Offset(rx, ry), Offset(tx, ty), gpxReturnHaloPaint)
            canvas.drawLine(Offset(rx, ry), Offset(tx, ty), gpxReturnLinePaint)
            waypointPinPaint.color = Color.Black
            canvas.drawCircle(Offset(tx, ty), 9.5f * scale, waypointPinPaint)
            waypointPinPaint.color = Color(255, 214, 10)
            canvas.drawCircle(Offset(tx, ty), 7.0f * scale, waypointPinPaint)
            waypointPinPaint.color = Color.White
            canvas.drawCircle(Offset(tx, ty), 3.0f * scale, waypointPinPaint)
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
                val wptPinScale = 1f + (ts - 1f) * 0.45f
                waypointPinPaint.color = Color.Black
                canvas.drawCircle(Offset(wx, wy), 9.5f * scale * wptPinScale, waypointPinPaint)
                waypointPinPaint.color = pinColor
                canvas.drawCircle(Offset(wx, wy), 7.5f * scale * wptPinScale, waypointPinPaint)
                waypointPinPaint.color = Color.White
                canvas.drawCircle(Offset(wx, wy), 3f * scale * wptPinScale, waypointPinPaint)

                if (config.showWaypointLabelsOnTft && wpt.name.isNotBlank()) {
                    canvas.save()
                    canvas.translate(wx, wy)
                    canvas.rotate(heading.toFloat())
                    val label = if (wpt.name.length > 16) wpt.name.take(15) else wpt.name
                    val lblSize = 10.5f * scale * ts
                    val tw = TftVectorText.measureText(label, lblSize)
                    val padX = 6f * scale * wptPinScale
                    val tagBottom = -11f * scale * wptPinScale
                    val tagTop = tagBottom - (15f * scale * ts)
                    val tagR = 5f * scale
                    canvas.drawRoundRect(-tw / 2f - padX, tagTop, tw / 2f + padX, tagBottom, tagR, tagR, hudCardPaint)
                    TftVectorText.drawText(
                        canvas = canvas,
                        text = label,
                        x = -tw / 2f,
                        y = tagBottom - 3.5f * scale * ts,
                        size = lblSize,
                        color = Color.White,
                        strokeW = 1.5f * scale * ts
                    )
                    canvas.restore()
                }
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

        val boxGrowth = 1f + (ts - 1f) * 0.55f

        // 6. HUD Velocímetro (Esquina inferior izquierda)
        val spdStr = "${rider.speedKmh.toInt()}"
        val spdSize = 36f * scale * ts
        val spdUnitSize = 11.5f * scale * ts
        val spdWidth = TftVectorText.measureText(spdStr, spdSize)
        val unitWidth = TftVectorText.measureText("KM/H", spdUnitSize)
        val speedLeft = 14f * scale
        val speedBoxH = 68f * scale * boxGrowth
        val speedBoxW = maxOf(138f * scale * (1f + (ts - 1f) * 0.42f), 14f * scale + spdWidth + 8f * scale + unitWidth + 14f * scale)
        val speedTop = h - speedBoxH - 14f * scale
        val speedRight = speedLeft + speedBoxW
        val speedBottom = h - 14f * scale
        val rCard = 12f * scale
        canvas.drawRoundRect(speedLeft, speedTop, speedRight, speedBottom, rCard, rCard, hudCardPaint)
        canvas.drawRoundRect(speedLeft, speedTop, speedRight, speedBottom, rCard, rCard, hudBorderPaint)

        TftVectorText.drawText(
            canvas = canvas,
            text = spdStr,
            x = speedLeft + 14f * scale,
            y = speedBottom - 16f * scale * boxGrowth,
            size = spdSize,
            color = Color.White,
            strokeW = 3.6f * scale * ts
        )
        TftVectorText.drawText(
            canvas = canvas,
            text = "KM/H",
            x = speedLeft + 18f * scale + spdWidth,
            y = speedBottom - 16f * scale * boxGrowth,
            size = spdUnitSize,
            color = Color(255, 107, 0),
            strokeW = 1.8f * scale * ts
        )

        // 7. HUD Altitud (Esquina inferior derecha, siempre visible aunque haya enlace o ruta)
        val altLblSize = 10.5f * scale * ts
        val altValSize = 15.5f * scale * ts
        val altValStr = "${rider.altitudeM.toInt()} M"
        val infoWidth = maxOf(
            146f * scale * (1f + (ts - 1f) * 0.35f),
            24f * scale + maxOf(TftVectorText.measureText("ALTITUD", altLblSize), TftVectorText.measureText(altValStr, altValSize))
        )
        val infoHeight = 58f * scale * boxGrowth
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
            y = infoTop + 20f * scale * boxGrowth,
            size = altLblSize,
            color = Color(139, 148, 158),
            strokeW = 1.5f * scale * ts
        )
        TftVectorText.drawText(
            canvas = canvas,
            text = altValStr,
            x = infoLeft + 12f * scale,
            y = infoBottom - 12f * scale * boxGrowth,
            size = altValSize,
            color = Color.White,
            strokeW = 2.1f * scale * ts
        )

        // 8. HUD Batería y Hora del móvil en tiempo real (Esquina superior derecha)
        val battStatus = getFreshBatteryStatus()
        val pct = if (battStatus.percentage >= 0) battStatus.percentage.coerceIn(0, 100) else 100
        val isCharging = battStatus.isCharging

        val clockTextSize = 13.5f * scale * ts
        val pctStr = "$pct%"
        val pctTextW = TftVectorText.measureText(pctStr, clockTextSize)
        val topPillH = 38f * scale * (1f + (ts - 1f) * 0.45f)
        val battBoxWidth = maxOf(
            (if (isCharging) 108f else 96f) * scale * (1f + (ts - 1f) * 0.28f),
            (if (isCharging) 52f else 42f) * scale + pctTextW + 10f * scale
        )
        val battLeft = w - battBoxWidth - 14f * scale
        val battTop = 12f * scale
        val battRight = w - 14f * scale
        val battBottom = battTop + topPillH
        val rBatt = 10f * scale
        canvas.drawRoundRect(battLeft, battTop, battRight, battBottom, rBatt, rBatt, hudCardPaint)
        canvas.drawRoundRect(battLeft, battTop, battRight, battBottom, rBatt, rBatt, hudBorderPaint)

        // Hora actual justo a la izquierda de la batería
        val clockStr = currentTftClockHm()
        val clockTextW = TftVectorText.measureText(clockStr, clockTextSize)
        val clockBoxWidth = maxOf(66f * scale * (1f + (ts - 1f) * 0.35f), clockTextW + 16f * scale)
        val clockRight = battLeft - 8f * scale
        val clockLeft = clockRight - clockBoxWidth
        canvas.drawRoundRect(clockLeft, battTop, clockRight, battBottom, rBatt, rBatt, hudCardPaint)
        canvas.drawRoundRect(clockLeft, battTop, clockRight, battBottom, rBatt, rBatt, hudBorderPaint)
        val pillMidY = (battTop + battBottom) * 0.5f
        val pillBaselineY = pillMidY + clockTextSize * 0.38f
        TftVectorText.drawText(
            canvas = canvas,
            text = clockStr,
            x = clockLeft + ((clockBoxWidth - clockTextW) * 0.5f).coerceAtLeast(6f * scale),
            y = pillBaselineY,
            size = clockTextSize,
            color = Color.White,
            strokeW = 1.9f * scale * ts
        )

        val iconLeft = battLeft + 10f * scale
        val iconTop = pillMidY - 7f * scale
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
            y = pillBaselineY,
            size = clockTextSize,
            color = Color.White,
            strokeW = 1.9f * scale * ts
        )

        // 9. HUD Resumen de Enlace / Ruta / GPX (Esquina superior izquierda, sin instrucciones de giro)
        val hasRoadTiming = remainingKm != null && remainingKm > 0.0 && remainingMinutes != null
        val hasGpxRemaining = !hasRoadTiming && remainingKm != null && (remainingKm > 0.0 || routeTypeLabel == "TRACK")
        val navBottom = battTop + 44f * scale * boxGrowth
        if (hasRoadTiming || hasGpxRemaining) {
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

            val navTopSize = 9.5f * scale * ts
            val navMainSize = 12.5f * scale * ts
            val navLeft = 14f * scale
            val maxNavRight = clockLeft - 10f * scale
            val desiredW = maxOf(
                (if (hasRoadTiming) 244f else 156f) * scale,
                maxOf(TftVectorText.measureText(topLbl, navTopSize), TftVectorText.measureText(mainVal, navMainSize)) + 24f * scale
            )
            val navRight = minOf(maxNavRight, navLeft + desiredW)
            canvas.drawRoundRect(navLeft, battTop, navRight, navBottom, rBatt, rBatt, hudCardPaint)
            canvas.drawRoundRect(navLeft, battTop, navRight, navBottom, rBatt, rBatt, hudBorderPaint)

            TftVectorText.drawText(
                canvas = canvas,
                text = topLbl,
                x = navLeft + 10f * scale,
                y = battTop + 17f * scale * boxGrowth,
                size = navTopSize,
                color = Color(255, 107, 0),
                strokeW = 1.4f * scale * ts
            )
            TftVectorText.drawText(
                canvas = canvas,
                text = mainVal,
                x = navLeft + 10f * scale,
                y = navBottom - 10f * scale * boxGrowth,
                size = navMainSize,
                color = Color.White,
                strokeW = 1.8f * scale * ts
            )
        }

        // 9b. Contador discreto de grupo en HUD (debajo de hora/batería)
        val grpBoxWidth = 86f * scale
        val grpLeft = w - grpBoxWidth - 14f * scale
        val grpTop = battBottom + 6f * scale
        val grpBottom = grpTop + 26f * scale
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

        // 9c. Banner de alerta/mensaje del grupo en 2 fases:
        // - Fase 1 (primeros 5 s): grande y destacado arriba
        // - Fase 2 (después de 5 s): compacto en la esquina superior izquierda hasta volver a "En ruta"
        val alertBanner = if (config.showGroupAlertsOnTft) groupPlan?.alertBanner else null
        var alertBannerBottomPx = 0f
        if (alertBanner != null) {
            val alertLeft = 14f * scale
            val maxAlertRight = if (groupActiveCount > 0 && config.showGroupHudBadgeOnTft) {
                grpLeft - 8f * scale
            } else {
                w - 14f * scale
            }
            val expanded = alertBanner.isExpanded
            val alertRight = if (expanded) {
                w - 14f * scale
            } else {
                minOf(maxAlertRight, alertLeft + 250f * scale)
            }
            val alertTop = maxOf(navBottom, battBottom) + 6f * scale
            val innerPadX = if (expanded) 12f * scale else 9f * scale
            val innerW = (alertRight - alertLeft - innerPadX * 2f).coerceAtLeast(40f * scale)
            val headerSize = if (expanded) 14.5f * scale else 10.2f * scale
            val msgSize = if (expanded) 12.5f * scale else 9.4f * scale
            val headerStroke = if (expanded) 2.25f * scale else 1.7f * scale
            val msgStroke = if (expanded) 1.85f * scale else 1.45f * scale

            val headerText = TftNotificationTextLayout.truncateToWidth(
                text = alertBanner.text,
                maxWidth = innerW
            ) { TftVectorText.measureText(it, headerSize) }
            val msgLines = if (alertBanner.subtext.isNotBlank()) {
                TftNotificationTextLayout.wrapLines(
                    text = alertBanner.subtext,
                    maxWidth = innerW,
                    maxLines = 2
                ) { TftVectorText.measureText(it, msgSize) }
            } else {
                emptyList()
            }

            val bannerHeight = if (expanded) {
                when (msgLines.size) {
                    0 -> 40f
                    1 -> 64f
                    else -> 86f
                } * scale
            } else {
                when (msgLines.size) {
                    0 -> 26f
                    1 -> 44f
                    else -> 58f
                } * scale
            }
            val alertBottom = alertTop + bannerHeight
            if (alertRight > alertLeft + 40f * scale) {
                alertBannerBottomPx = alertBottom
                val bgAlpha = if (expanded) 248 else 226
                groupAlertBgPaint.color = when (alertBanner.status) {
                    RiderStatus.SOS -> Color(205, 24, 24, bgAlpha)
                    RiderStatus.MECHANICAL -> Color(214, 88, 0, bgAlpha)
                    RiderStatus.STOPPED_WAIT -> Color(172, 116, 0, bgAlpha)
                    else -> Color(16, 98, 178, bgAlpha)
                }
                groupStrokePaint.color = Color.White
                groupStrokePaint.strokeWidth = (if (expanded) 2.3f else 1.4f) * scale
                val cornerR = (if (expanded) 11f else 8f) * scale
                canvas.drawRoundRect(alertLeft, alertTop, alertRight, alertBottom, cornerR, cornerR, groupAlertBgPaint)
                canvas.drawRoundRect(alertLeft, alertTop, alertRight, alertBottom, cornerR, cornerR, groupStrokePaint)

                TftVectorText.drawText(
                    canvas = canvas,
                    text = headerText,
                    x = alertLeft + innerPadX,
                    y = alertTop + (if (expanded) 23f else 17.5f) * scale,
                    size = headerSize,
                    color = Color.White,
                    strokeW = headerStroke
                )
                if (msgLines.isNotEmpty()) {
                    TftVectorText.drawText(
                        canvas = canvas,
                        text = msgLines[0],
                        x = alertLeft + innerPadX,
                        y = alertTop + (if (expanded) 45f else 33f) * scale,
                        size = msgSize,
                        color = Color(255, 250, 235, 255),
                        strokeW = msgStroke
                    )
                }
                if (msgLines.size >= 2) {
                    TftVectorText.drawText(
                        canvas = canvas,
                        text = msgLines[1],
                        x = alertLeft + innerPadX,
                        y = alertTop + (if (expanded) 64f else 46f) * scale,
                        size = msgSize,
                        color = Color(255, 250, 235, 255),
                        strokeW = msgStroke
                    )
                }

                if (expanded && alertBanner.expandedProgress > 0f) {
                    val trackL = alertLeft + 10f * scale
                    val trackR = alertRight - 10f * scale
                    val trackT = alertBottom - 6f * scale
                    val trackB = alertBottom - 3f * scale
                    val fillW = (trackR - trackL) * alertBanner.expandedProgress
                    if (fillW > 2f * scale) {
                        notifBgPaint.color = Color(255, 255, 255, 70)
                        canvas.drawRoundRect(trackL, trackT, trackR, trackB, 1.5f * scale, 1.5f * scale, notifBgPaint)
                        notifBgPaint.color = Color.White
                        canvas.drawRoundRect(trackL, trackT, trackL + fillW, trackB, 1.5f * scale, 1.5f * scale, notifBgPaint)
                    }
                }
            }
        }

        // 9d. Notificación emergente del móvil (Heads-up banner temporal)
        if (config.showPhoneNotificationsOnTft && activeNotification != null) {
            drawPhoneNotificationBanner(
                canvas = canvas,
                w = w,
                scale = scale,
                notif = activeNotification,
                alertBannerBottomPx = alertBannerBottomPx
            )
        }

        // 9e. Advertencia configurable de salida del track GPX activo (franja inferior central libre de HUD)
        val gpxWarnThreshold = config.gpxOffTrackThresholdMeters.toDouble().coerceIn(20.0, 200.0)
        if (config.showGpxOffTrackWarning && gpxOffTrackMeters != null && gpxOffTrackMeters >= gpxWarnThreshold) {
            val warnLeft = speedRight + 10f * scale
            val warnRight = infoLeft - 10f * scale
            if (warnRight > warnLeft + 110f * scale) {
                val warnH = 50f * scale * boxGrowth
                val warnTop = h - warnH - 14f * scale
                val warnBottom = h - 14f * scale
                val warnR = 10f * scale
                canvas.drawRoundRect(warnLeft, warnTop, warnRight, warnBottom, warnR, warnR, gpxReturnWarnBgPaint)
                gpxReturnWarnStrokePaint.strokeWidth = 1.8f * scale
                canvas.drawRoundRect(warnLeft, warnTop, warnRight, warnBottom, warnR, warnR, gpxReturnWarnStrokePaint)

                val distLabel = GroupTftLayout.formatCompactDistance(gpxOffTrackMeters)
                val dirLabel = if (rider.coordinate != null && gpxOffTrackTarget != null) {
                    val brg = RouteNavigator.compassBearing(rider.coordinate, gpxOffTrackTarget)
                    val dirs = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
                    " · ${dirs[((brg + 22.5) / 45.0).toInt() % 8]}"
                } else {
                    ""
                }
                val warnTs = ts.coerceAtMost(1.25f)
                val titleStr = "! FUERA DEL GPX"
                val subStr = "A $distLabel$dirLabel"
                val warnTitleSize = 11f * scale * warnTs
                val warnSubSize = 12f * scale * warnTs
                val titleW = TftVectorText.measureText(titleStr, warnTitleSize)
                val subW = TftVectorText.measureText(subStr, warnSubSize)
                val warnCenterX = (warnLeft + warnRight) * 0.5f

                TftVectorText.drawText(
                    canvas = canvas,
                    text = titleStr,
                    x = warnCenterX - titleW * 0.5f,
                    y = warnTop + 19f * scale * boxGrowth,
                    size = warnTitleSize,
                    color = Color(255, 224, 90),
                    strokeW = 1.7f * scale * warnTs
                )
                TftVectorText.drawText(
                    canvas = canvas,
                    text = subStr,
                    x = warnCenterX - subW * 0.5f,
                    y = warnBottom - 10f * scale * boxGrowth,
                    size = warnSubSize,
                    color = Color.White,
                    strokeW = 1.85f * scale
                )
            }
        }

        // 10. BlackEdges (4px arriba y abajo requeridos por Carbit EasyConn)
        val edgePx = (4f * scale).coerceAtLeast(2f)
        canvas.drawRect(0f, 0f, w, edgePx, blackEdgePaint)
        canvas.drawRect(0f, h - edgePx, w, h, blackEdgePaint)
    }

    private fun drawPhoneNotificationBanner(
        canvas: Canvas,
        w: Float,
        scale: Float,
        notif: TftPhoneNotification,
        alertBannerBottomPx: Float
    ) {
        val nowMs = Clock.System.now().toEpochMilliseconds()
        if (!notif.isActive(nowMs)) return

        val elapsed = notif.elapsedMs(nowMs)
        val remaining = notif.remainingMs(nowMs)
        val alphaFactor = when {
            elapsed < 180L -> 0.72f + 0.28f * (elapsed / 180f).coerceIn(0f, 1f)
            remaining < 220L -> (remaining / 220f).coerceIn(0f, 1f)
            else -> 1f
        }
        val slideOffsetY = when {
            elapsed < 180L -> -(1f - (elapsed / 180f).coerceIn(0f, 1f)) * 12f * scale
            remaining < 220L -> -(1f - (remaining / 220f).coerceIn(0f, 1f)) * 16f * scale
            else -> 0f
        }

        val left = 14f * scale
        val right = w - 14f * scale
        if (right <= left + 120f * scale) return

        val top = (if (alertBannerBottomPx > 0f) alertBannerBottomPx + 6f * scale else 58f * scale) + slideOffsetY
        val textLeft = left + 54f * scale
        val textMaxW = (right - 14f * scale - textLeft).coerceAtLeast(60f * scale)

        val appSize = 10.5f * scale
        val nowSize = 9.5f * scale
        val titleSize = 12.5f * scale
        val bodySize = 10.5f * scale

        val appText = TftNotificationTextLayout.truncateToWidth(
            text = notif.appName.uppercase(),
            maxWidth = (textMaxW - 56f * scale).coerceAtLeast(40f * scale)
        ) { TftVectorText.measureText(it, appSize) }

        val titleText = TftNotificationTextLayout.truncateToWidth(
            text = notif.title,
            maxWidth = textMaxW
        ) { TftVectorText.measureText(it, titleSize) }

        val bodyLines = TftNotificationTextLayout.wrapLines(
            text = notif.text,
            maxWidth = textMaxW,
            maxLines = 2
        ) { TftVectorText.measureText(it, bodySize) }

        val cardH = when (bodyLines.size) {
            0 -> 58f
            1 -> 74f
            else -> 92f
        } * scale
        val bottom = top + cardH
        val radius = 14f * scale

        val accentBase = Color(notif.accentArgb)
        val accentColor = accentBase.copy(alpha = alphaFactor)

        notifBgPaint.color = Color(18, 22, 30, (246f * alphaFactor).roundToInt().coerceIn(0, 255))
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, notifBgPaint)

        notifStrokePaint.color = accentColor
        notifStrokePaint.strokeWidth = 1.8f * scale
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, notifStrokePaint)

        // Avatar circular izquierdo con icono vectorial por categoría
        val cx = left + 29f * scale
        val cy = top + (cardH - 4f * scale) * 0.5f
        val avatarR = 16f * scale
        notifBgPaint.color = accentColor
        canvas.drawCircle(Offset(cx, cy), avatarR, notifBgPaint)

        drawCategoryIcon(canvas, notif.category, cx, cy, scale, alphaFactor)

        // Cabecera: Nombre app + AHORA
        TftVectorText.drawText(
            canvas = canvas,
            text = appText,
            x = textLeft,
            y = top + 19f * scale,
            size = appSize,
            color = accentColor,
            strokeW = 1.7f * scale
        )
        val nowStr = "AHORA"
        val nowW = TftVectorText.measureText(nowStr, nowSize)
        TftVectorText.drawText(
            canvas = canvas,
            text = nowStr,
            x = right - 14f * scale - nowW,
            y = top + 19f * scale,
            size = nowSize,
            color = Color(165, 175, 188, (190f * alphaFactor).roundToInt().coerceIn(0, 255)),
            strokeW = 1.4f * scale
        )

        // Título / remitente
        TftVectorText.drawText(
            canvas = canvas,
            text = titleText,
            x = textLeft,
            y = top + 38f * scale,
            size = titleSize,
            color = Color.White.copy(alpha = alphaFactor),
            strokeW = 2.0f * scale
        )

        // Cuerpo en 1 o 2 líneas
        val bodyColor = Color(224, 232, 240, (255f * alphaFactor).roundToInt().coerceIn(0, 255))
        if (bodyLines.isNotEmpty()) {
            TftVectorText.drawText(
                canvas = canvas,
                text = bodyLines[0],
                x = textLeft,
                y = top + 56f * scale,
                size = bodySize,
                color = bodyColor,
                strokeW = 1.55f * scale
            )
        }
        if (bodyLines.size >= 2) {
            TftVectorText.drawText(
                canvas = canvas,
                text = bodyLines[1],
                x = textLeft,
                y = top + 73f * scale,
                size = bodySize,
                color = bodyColor,
                strokeW = 1.55f * scale
            )
        }

        // Barra inferior de tiempo restante
        val trackLeft = left + 12f * scale
        val trackRight = right - 12f * scale
        val trackTop = bottom - 6f * scale
        val trackBottom = bottom - 3f * scale
        val trackR = 1.5f * scale
        notifBgPaint.color = Color(255, 255, 255, (45f * alphaFactor).roundToInt().coerceIn(0, 255))
        canvas.drawRoundRect(trackLeft, trackTop, trackRight, trackBottom, trackR, trackR, notifBgPaint)

        val progress = notif.remainingProgress(nowMs)
        val fillW = (trackRight - trackLeft) * progress
        if (fillW > 2f * scale) {
            notifBgPaint.color = accentColor
            canvas.drawRoundRect(trackLeft, trackTop, trackLeft + fillW, trackBottom, trackR, trackR, notifBgPaint)
        }
    }

    private fun drawCategoryIcon(
        canvas: Canvas,
        category: TftNotificationCategory,
        cx: Float,
        cy: Float,
        scale: Float,
        alphaFactor: Float
    ) {
        val white = Color.White.copy(alpha = alphaFactor)
        notifIconStrokePaint.color = white
        notifIconStrokePaint.strokeWidth = 1.8f * scale
        notifIconFillPaint.color = white
        notifIconPath.reset()

        when (category) {
            TftNotificationCategory.MESSAGE -> {
                notifIconPath.moveTo(cx - 7f * scale, cy - 5.5f * scale)
                notifIconPath.lineTo(cx + 7f * scale, cy - 5.5f * scale)
                notifIconPath.lineTo(cx + 7f * scale, cy + 3.5f * scale)
                notifIconPath.lineTo(cx - 1.5f * scale, cy + 3.5f * scale)
                notifIconPath.lineTo(cx - 5.5f * scale, cy + 7f * scale)
                notifIconPath.lineTo(cx - 5f * scale, cy + 3.5f * scale)
                notifIconPath.lineTo(cx - 7f * scale, cy + 3.5f * scale)
                notifIconPath.close()
                canvas.drawPath(notifIconPath, notifIconStrokePaint)
            }
            TftNotificationCategory.CALL -> {
                notifIconPath.moveTo(cx - 5.5f * scale, cy - 6.5f * scale)
                notifIconPath.lineTo(cx - 2f * scale, cy - 6.5f * scale)
                notifIconPath.lineTo(cx - 1f * scale, cy - 3f * scale)
                notifIconPath.lineTo(cx - 3f * scale, cy - 1f * scale)
                notifIconPath.lineTo(cx + 1f * scale, cy + 3f * scale)
                notifIconPath.lineTo(cx + 3f * scale, cy + 1f * scale)
                notifIconPath.lineTo(cx + 6.5f * scale, cy + 2f * scale)
                notifIconPath.lineTo(cx + 6.5f * scale, cy + 5.5f * scale)
                notifIconPath.lineTo(cx + 3f * scale, cy + 6.5f * scale)
                notifIconPath.lineTo(cx - 2.5f * scale, cy + 3.5f * scale)
                notifIconPath.lineTo(cx - 6.5f * scale, cy - 2.5f * scale)
                notifIconPath.close()
                canvas.drawPath(notifIconPath, notifIconStrokePaint)
            }
            TftNotificationCategory.NAVIGATION -> {
                notifIconPath.moveTo(cx, cy - 7.5f * scale)
                notifIconPath.lineTo(cx + 6.5f * scale, cy + 6.5f * scale)
                notifIconPath.lineTo(cx, cy + 3f * scale)
                notifIconPath.lineTo(cx - 6.5f * scale, cy + 6.5f * scale)
                notifIconPath.close()
                canvas.drawPath(notifIconPath, notifIconFillPaint)
            }
            TftNotificationCategory.ALERT -> {
                notifIconPath.moveTo(cx, cy - 7f * scale)
                notifIconPath.lineTo(cx + 7f * scale, cy + 5.5f * scale)
                notifIconPath.lineTo(cx - 7f * scale, cy + 5.5f * scale)
                notifIconPath.close()
                canvas.drawPath(notifIconPath, notifIconStrokePaint)
                notifIconPath.reset()
                notifIconPath.moveTo(cx, cy - 2.5f * scale)
                notifIconPath.lineTo(cx, cy + 1.5f * scale)
                canvas.drawPath(notifIconPath, notifIconStrokePaint)
                canvas.drawCircle(Offset(cx, cy + 3.7f * scale), 1f * scale, notifIconFillPaint)
            }
            TftNotificationCategory.APP -> {
                notifIconPath.moveTo(cx - 5f * scale, cy + 2.5f * scale)
                notifIconPath.lineTo(cx - 5f * scale, cy - 2.5f * scale)
                notifIconPath.lineTo(cx - 2.5f * scale, cy - 6f * scale)
                notifIconPath.lineTo(cx + 2.5f * scale, cy - 6f * scale)
                notifIconPath.lineTo(cx + 5f * scale, cy - 2.5f * scale)
                notifIconPath.lineTo(cx + 5f * scale, cy + 2.5f * scale)
                notifIconPath.lineTo(cx + 7f * scale, cy + 4f * scale)
                notifIconPath.lineTo(cx - 7f * scale, cy + 4f * scale)
                notifIconPath.close()
                canvas.drawPath(notifIconPath, notifIconStrokePaint)
                canvas.drawCircle(Offset(cx, cy + 6.2f * scale), 1.3f * scale, notifIconFillPaint)
            }
        }
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
            'Ç' -> 'C'
            '¿' -> '?'
            '¡' -> '!'
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
            '?' -> {
                p.moveTo(x0, y0 + h * 0.22f)
                p.lineTo(x0 + w * 0.25f, y0)
                p.lineTo(x1 - w * 0.15f, y0)
                p.lineTo(x1, y0 + h * 0.25f)
                p.lineTo(xm, ym)
                p.lineTo(xm, y0 + h * 0.65f)
                p.moveTo(xm, y1 - h * 0.14f)
                p.lineTo(xm, y1)
            }
            '(', '[' -> {
                p.moveTo(x1 - w * 0.2f, y0)
                p.lineTo(x0 + w * 0.25f, ym)
                p.lineTo(x1 - w * 0.2f, y1)
            }
            ')', ']' -> {
                p.moveTo(x0 + w * 0.2f, y0)
                p.lineTo(x1 - w * 0.25f, ym)
                p.lineTo(x0 + w * 0.2f, y1)
            }
            '\'' -> {
                p.moveTo(xm, y0)
                p.lineTo(xm, y0 + h * 0.28f)
            }
            '"' -> {
                p.moveTo(x0 + w * 0.3f, y0)
                p.lineTo(x0 + w * 0.3f, y0 + h * 0.28f)
                p.moveTo(x1 - w * 0.3f, y0)
                p.lineTo(x1 - w * 0.3f, y0 + h * 0.28f)
            }
            ';' -> {
                p.moveTo(xm, y0 + h * 0.25f)
                p.lineTo(xm, y0 + h * 0.32f)
                p.moveTo(xm, y1 - h * 0.25f)
                p.lineTo(x0 + w * 0.35f, y1)
            }
            '@' -> {
                p.moveTo(x1 - w * 0.15f, ym)
                p.lineTo(x1 - w * 0.15f, y0 + h * 0.25f)
                p.lineTo(xm, y0 + h * 0.25f)
                p.lineTo(xm, ym + h * 0.15f)
                p.lineTo(x1, ym + h * 0.15f)
                p.lineTo(x1, y0)
                p.lineTo(x0, y0)
                p.lineTo(x0, y1)
                p.lineTo(x1, y1)
            }
            '#' -> {
                p.moveTo(x0 + w * 0.3f, y0)
                p.lineTo(x0 + w * 0.2f, y1)
                p.moveTo(x1 - w * 0.2f, y0)
                p.lineTo(x1 - w * 0.3f, y1)
                p.moveTo(x0, y0 + h * 0.35f)
                p.lineTo(x1, y0 + h * 0.35f)
                p.moveTo(x0, y1 - h * 0.3f)
                p.lineTo(x1, y1 - h * 0.3f)
            }
            '*' -> {
                p.moveTo(xm, y0 + h * 0.15f)
                p.lineTo(xm, y1 - h * 0.15f)
                p.moveTo(x0 + w * 0.1f, y0 + h * 0.28f)
                p.lineTo(x1 - w * 0.1f, y1 - h * 0.28f)
                p.moveTo(x1 - w * 0.1f, y0 + h * 0.28f)
                p.lineTo(x0 + w * 0.1f, y1 - h * 0.28f)
            }
            '=' -> {
                p.moveTo(x0, y0 + h * 0.36f)
                p.lineTo(x1, y0 + h * 0.36f)
                p.moveTo(x0, y1 - h * 0.32f)
                p.lineTo(x1, y1 - h * 0.32f)
            }
            '_' -> {
                p.moveTo(x0, y1)
                p.lineTo(x1, y1)
            }
            '…' -> {
                p.moveTo(x0 + w * 0.15f, y1 - h * 0.08f)
                p.lineTo(x0 + w * 0.2f, y1)
                p.moveTo(xm, y1 - h * 0.08f)
                p.lineTo(xm, y1)
                p.moveTo(x1 - w * 0.2f, y1 - h * 0.08f)
                p.lineTo(x1 - w * 0.15f, y1)
            }
        }
    }
}

