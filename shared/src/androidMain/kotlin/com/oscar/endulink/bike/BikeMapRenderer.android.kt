package com.oscar.endulink.bike

import android.graphics.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.oscar.endulink.group.GroupMember
import com.oscar.endulink.group.GroupTftLayout
import com.oscar.endulink.group.RiderStatus
import com.oscar.endulink.group.TftEdgeIndicator
import com.oscar.endulink.group.TftProjectedMember
import com.oscar.endulink.platform.BatteryStatus
import com.oscar.endulink.platform.RiderTelemetry
import com.oscar.endulink.platform.TftNotificationCategory
import com.oscar.endulink.platform.TftNotificationTextLayout
import com.oscar.endulink.platform.TftPhoneNotification
import com.oscar.endulink.platform.getPlatformBatteryStatus
import com.oscar.endulink.tiles.MapLayer
import com.oscar.endulink.tiles.TileCoord
import com.oscar.endulink.tiles.TileMath
import com.oscar.endulink.tiles.TileStore
import com.oscar.endulink.trail.GPXRoute
import com.oscar.endulink.trail.GPXWaypoint
import com.oscar.endulink.trail.GeoCoord
import com.oscar.endulink.trail.RouteNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@Suppress("DEPRECATION")
actual fun getWifiSnapshot(): WifiSnapshot? {
    var currentSsid: String? = null
    try {
        val ctx = com.oscar.endulink.platform.AndroidAppContext.appContext ?: AndroidBikeKeysStorage.appContext
        if (ctx != null) {
            val wifiManager = ctx.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val rawSsid = wifiManager?.connectionInfo?.ssid?.trim()?.trim('"')
            if (!rawSsid.isNullOrEmpty() && !rawSsid.equals("<unknown ssid>", ignoreCase = true)) {
                currentSsid = rawSsid
            }
            val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            if (cm != null) {
                AndroidBikeWifiConnector.ensurePassiveWifiMonitor(cm)
                val activeWifi = cm.allNetworks.firstOrNull { net ->
                    cm.getNetworkCapabilities(net)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
                }
                AndroidSocketBinder.wifiNetwork = activeWifi
            }
        }
    } catch (_: Exception) {}

    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.name.lowercase()
            if (name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("ap") ||
                name.startsWith("swlan") || name.startsWith("mlan") || name.startsWith("p2p")
            ) {
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        val parts = host.split(".")
                        if (parts.size == 4) {
                            val gateway = "${parts[0]}.${parts[1]}.${parts[2]}.1"
                            return WifiSnapshot(
                                phoneIP = host,
                                gatewayCandidateIP = gateway,
                                ssid = currentSsid,
                                interfaceName = iface.name
                            )
                        }
                    }
                }
            }
        }
    } catch (_: Exception) {}
    return null
}

actual class BikeMapRenderer actual constructor(private val tileStore: TileStore) {

    private var cachedBitmap: Bitmap? = null
    private var cachedCanvas: Canvas? = null
    private var rgbaBuffer: ByteArray? = null

    // Caché LRU en memoria de bitmaps de teselas decodificadas para evitar decodificar PNG/JPG en cada frame a 15 FPS
    private val decodedTileCache = android.util.LruCache<TileCoord, Bitmap>(128)
    private val decodeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val loadingTiles = ConcurrentHashMap.newKeySet<TileCoord>()
    private val tileMissUntilMs = ConcurrentHashMap<TileCoord, Long>()

    private val routePath = Path()
    private val approachPath = Path()
    private val chevronPath = Path()
    private val arrowPath = Path()
    private val corePath = Path()
    private val boltPath = Path()
    private val tileSrcRect = Rect()
    private val tileDstRect = RectF()
    private val tmpTagRect = RectF()
    private val groupArrowPath = Path()
    private val groupRect = RectF()

    private fun getOrQueueTileBitmap(coord: TileCoord, allowAsyncFetch: Boolean): Bitmap? {
        decodedTileCache.get(coord)?.let { return it }
        val now = System.currentTimeMillis()
        val retryAfter = tileMissUntilMs[coord]
        if (retryAfter != null && now < retryAfter) return null
        if (loadingTiles.add(coord)) {
            decodeScope.launch {
                try {
                    val bytes = tileStore.getTileBytes(coord, allowAsyncFetch = allowAsyncFetch)
                    if (bytes != null && bytes.size > 100) {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            decodedTileCache.put(coord, bmp)
                            tileMissUntilMs.remove(coord)
                            return@launch
                        }
                    }
                    tileMissUntilMs[coord] = System.currentTimeMillis() + if (allowAsyncFetch) 2500L else 5000L
                } finally {
                    loadingTiles.remove(coord)
                }
            }
        }
        return null
    }

    // Paints preconfigurados para alto rendimiento (cero allocations por frame)
    private val bgPaint = Paint().apply { color = android.graphics.Color.rgb(20, 23, 28) }
    private val blackEdgePaint = Paint().apply { color = android.graphics.Color.BLACK }
    private val tileFilterPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val routePaint = Paint().apply {
        color = android.graphics.Color.rgb(255, 107, 0) // Naranja vivo
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val approachPaint = Paint().apply {
        color = android.graphics.Color.rgb(0, 212, 255) // Cyan enlace
        style = Paint.Style.STROKE
        strokeWidth = 9f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val hudCardPaint = Paint().apply {
        color = android.graphics.Color.argb(225, 22, 26, 34)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val hudBorderPaint = Paint().apply {
        color = android.graphics.Color.argb(160, 48, 54, 61)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }
    private val speedTextPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 42f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    private val speedUnitPaint = Paint().apply {
        color = android.graphics.Color.rgb(255, 107, 0)
        textSize = 14f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    private val textPrimaryPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 17f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    private val textMutedPaint = Paint().apply {
        color = android.graphics.Color.rgb(139, 148, 158)
        textSize = 12f
        isAntiAlias = true
    }
    private val riderArrowPaint = Paint().apply {
        color = android.graphics.Color.rgb(255, 107, 0)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val riderArrowCorePaint = Paint().apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val batteryOutlinePaint = Paint().apply {
        color = android.graphics.Color.rgb(160, 175, 195)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val batteryFillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val batteryBoltPaint = Paint().apply {
        color = android.graphics.Color.rgb(0, 212, 255)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val batteryTextPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 15f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    private val waypointPinPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val waypointLabelPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 11.5f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    private val dirArrowHaloPaint = Paint().apply {
        color = android.graphics.Color.rgb(14, 17, 23)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val dirArrowFillPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val groupFillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val groupStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val groupAlertBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val groupTextPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 11.5f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    private val notifBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val notifStrokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        isAntiAlias = true
    }
    private val notifAppPaint = Paint().apply {
        textSize = 11f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    private val notifTimePaint = Paint().apply {
        color = android.graphics.Color.rgb(165, 175, 188)
        textSize = 10.5f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    private val notifTitlePaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 14.5f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    private val notifBodyPaint = Paint().apply {
        color = android.graphics.Color.rgb(224, 232, 240)
        textSize = 12.5f
        isAntiAlias = true
    }
    private val notifIconStrokePaint = Paint().apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val notifIconFillPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val notifIconPath = Path()
    private val notifRect = RectF()
    private val notifTrackRect = RectF()
    private val notifProgressRect = RectF()
    private val gpxReturnHaloPaint = Paint().apply {
        color = android.graphics.Color.argb(240, 14, 17, 23)
        style = Paint.Style.STROKE
        strokeWidth = 8.5f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val gpxReturnLinePaint = Paint().apply {
        color = android.graphics.Color.rgb(255, 214, 10)
        style = Paint.Style.STROKE
        strokeWidth = 4.8f
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(15f, 9f), 0f)
        isAntiAlias = true
    }
    private val gpxReturnWarnBgPaint = Paint().apply {
        color = android.graphics.Color.argb(244, 180, 96, 0)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val gpxReturnWarnStrokePaint = Paint().apply {
        color = android.graphics.Color.rgb(255, 214, 10)
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        isAntiAlias = true
    }
    private val gpxReturnWarnTextPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val groupSubtextPaint = Paint().apply {
        color = android.graphics.Color.rgb(255, 250, 235)
        textSize = 11.5f
        isAntiAlias = true
    }

    private var lastBatteryCheckMs: Long = 0L
    private var cachedBatteryStatus: BatteryStatus = BatteryStatus()

    private fun getFreshBatteryStatus(): BatteryStatus {
        val now = System.currentTimeMillis()
        if (now - lastBatteryCheckMs > 2000L || cachedBatteryStatus.percentage < 0) {
            lastBatteryCheckMs = now
            cachedBatteryStatus = getPlatformBatteryStatus()
        }
        return cachedBatteryStatus
    }

    @Synchronized
    actual fun renderRgba(
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
        remainingMinutes: Int?,
        routeTypeLabel: String?,
        groupMembers: List<GroupMember>,
        groupActiveCount: Int,
        activeNotification: TftPhoneNotification?,
        gpxOffTrackTarget: GeoCoord?,
        gpxOffTrackMeters: Double?
    ): ByteArray? {
        val bmp = getOrCreateBitmap(width, height)
        val canvas = cachedCanvas ?: Canvas(bmp).also { cachedCanvas = it }
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

        bmp.copyPixelsToBuffer(ByteBuffer.wrap(buffer))
        return buffer
    }

    actual fun renderBgraInts(
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
        remainingMinutes: Int?,
        routeTypeLabel: String?,
        groupMembers: List<GroupMember>,
        groupActiveCount: Int,
        activeNotification: TftPhoneNotification?,
        gpxOffTrackTarget: GeoCoord?,
        gpxOffTrackMeters: Double?
    ): IntArray? = null

    @Synchronized
    actual fun renderPreview(
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
        remainingMinutes: Int?,
        routeTypeLabel: String?,
        groupMembers: List<GroupMember>,
        groupActiveCount: Int,
        activeNotification: TftPhoneNotification?,
        gpxOffTrackTarget: GeoCoord?,
        gpxOffTrackMeters: Double?
    ): ImageBitmap? {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
        return bmp.asImageBitmap()
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

        // 1. Fondo oscuro base
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Parámetros de navegación del mapa
        val center = rider.coordinate
            ?: activeRoute?.points?.firstOrNull()?.coordinate
            ?: GeoCoord(40.4168, -3.7038)

        // Auto-zoom dinámico configurable según velocidad
        val zoom = config.resolveEffectiveZoom(rider.speedKmh)

        // Orientación: "north" (Norte arriba) o "heading" (Rumbo hacia arriba)
        val heading = if (config.orientation == "north") {
            0.0
        } else {
            if (rider.hasValidHeading) rider.headingDegrees else 0.0
        }

        val anchorX = w * 0.5f
        val anchorY = h * 0.67f // 67% hacia abajo para ver más carretera hacia adelante

        // Magnificación de tamaño de teselas / textos (1.10 = Peq, 1.45 = Med, 1.80 = Grd)
        val tileSizePx = (256.0 * config.tileMagnification).toFloat()
        val (centerFracX, centerFracY) = TileMath.latLonToFractionalTile(center.latitude, center.longitude, zoom)

        fun geoToLocal(coord: GeoCoord): Pair<Float, Float> {
            val (fx, fy) = TileMath.latLonToFractionalTile(coord.latitude, coord.longitude, zoom)
            val lx = ((fx - centerFracX) * tileSizePx).toFloat()
            val ly = ((fy - centerFracY) * tileSizePx).toFloat()
            return Pair(lx, ly)
        }

        // 3. Render de escena orientada al rumbo de la moto
        canvas.save()
        canvas.translate(anchorX, anchorY)
        canvas.rotate(-heading.toFloat())

        val centerTileX = floor(centerFracX).toInt()
        val centerTileY = floor(centerFracY).toInt()
        val maxTileIndex = if (zoom in 0..30) (1 shl zoom) - 1 else 0
        val maxRadius = ceil(hypot(w * 0.5, h * 0.68) / tileSizePx).toInt().coerceIn(1, 3)

        // Dibujar teselas visibles sin bloqueo de disco ni decodificación en el hilo de renderizado
        for (dx in -maxRadius..maxRadius) {
            val tx = centerTileX + dx
            if (tx < 0 || tx > maxTileIndex) continue
            for (dy in -maxRadius..maxRadius) {
                val ty = centerTileY + dy
                if (ty < 0 || ty > maxTileIndex) continue

                val posX = ((tx - centerFracX) * tileSizePx).toFloat()
                val posY = ((ty - centerFracY) * tileSizePx).toFloat()
                tileDstRect.set(posX - 0.6f, posY - 0.6f, posX + tileSizePx + 0.6f, posY + tileSizePx + 0.6f)

                val tileCoord = TileCoord(layer, zoom, tx, ty)
                val tileBmp = getOrQueueTileBitmap(tileCoord, allowAsyncFetch = true)
                if (tileBmp != null) {
                    canvas.drawBitmap(tileBmp, null, tileDstRect, tileFilterPaint)
                } else {
                    for (delta in 1..4) {
                        val pZ = zoom - delta
                        if (pZ < 1) break
                        val pX = tx shr delta
                        val pY = ty shr delta
                        val parentCoord = TileCoord(layer, pZ, pX, pY)
                        val parentBmp = getOrQueueTileBitmap(parentCoord, allowAsyncFetch = false)
                        if (parentBmp != null) {
                            val ozScale = 1 shl delta
                            val subX = tx and (ozScale - 1)
                            val subY = ty and (ozScale - 1)
                            val srcX0 = (subX * parentBmp.width) / ozScale
                            val srcY0 = (subY * parentBmp.height) / ozScale
                            val srcX1 = ((subX + 1) * parentBmp.width) / ozScale
                            val srcY1 = ((subY + 1) * parentBmp.height) / ozScale
                            tileSrcRect.set(srcX0, srcY0, srcX1.coerceAtLeast(srcX0 + 1), srcY1.coerceAtLeast(srcY0 + 1))
                            canvas.drawBitmap(parentBmp, tileSrcRect, tileDstRect, tileFilterPaint)
                            break
                        }
                    }
                }
            }
        }

        // 4. Polilíneas de rutas y flechas de sentido (cero asignaciones por frame)
        val scale = (w / 592f).coerceIn(0.45f, 1.5f)
        val ts = config.tftHudFontScale.coerceIn(1.0f, 1.4f)
        val strokeScale = config.trackStrokeScale.coerceIn(0.75f, 1.5f)
        val showArrows = config.showTrackDirectionArrows
        routePaint.strokeWidth = 10f * strokeScale
        approachPaint.strokeWidth = 9f * strokeScale
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
            canvas.drawLine(rx, ry, tx, ty, gpxReturnHaloPaint)
            canvas.drawLine(rx, ry, tx, ty, gpxReturnLinePaint)
            waypointPinPaint.color = android.graphics.Color.BLACK
            canvas.drawCircle(tx, ty, 9.5f, waypointPinPaint)
            waypointPinPaint.color = android.graphics.Color.rgb(255, 214, 10)
            canvas.drawCircle(tx, ty, 7.0f, waypointPinPaint)
            waypointPinPaint.color = android.graphics.Color.WHITE
            canvas.drawCircle(tx, ty, 3.0f, waypointPinPaint)
        }

        // 4b. Paradas intermedias en el cuadro TFT
        for (stopCoord in intermediateStops) {
            val (sx, sy) = geoToLocal(stopCoord)
            if (sx in -w..w && sy in -h..h) {
                waypointPinPaint.color = android.graphics.Color.BLACK
                canvas.drawCircle(sx, sy, 10f, waypointPinPaint)
                waypointPinPaint.color = android.graphics.Color.rgb(0, 212, 255)
                canvas.drawCircle(sx, sy, 8f, waypointPinPaint)
                waypointPinPaint.color = android.graphics.Color.WHITE
                canvas.drawCircle(sx, sy, 4f, waypointPinPaint)
            }
        }

        // 4c. Waypoints y Puntos de Interés (Peligros, Cancelas, Gasolineras, etc.) en el cuadro TFT
        val allWaypoints = if (activeRoute != null && activeRoute.isVisible) {
            activeRoute.waypoints + waypoints
        } else {
            waypoints
        }
        val wptCullX = w * 0.72f
        val wptCullY = h * 0.72f
        var drawnTftWptLabels = 0
        for (wpt in allWaypoints) {
            val (wx, wy) = geoToLocal(wpt.coordinate)
            if (wx in -wptCullX..wptCullX && wy in -wptCullY..wptCullY) {
                val sym = wpt.symbol?.lowercase().orEmpty()
                val pinColor = when {
                    sym.contains("danger") || sym.contains("hazard") || sym.contains("alert") -> android.graphics.Color.rgb(255, 69, 58)
                    sym.contains("gate") || sym.contains("obstacle") || sym.contains("rock") -> android.graphics.Color.rgb(255, 159, 10)
                    sym.contains("water") || sym.contains("fountain") -> android.graphics.Color.rgb(0, 212, 255)
                    sym.contains("fuel") || sym.contains("gas") -> android.graphics.Color.rgb(48, 209, 88)
                    else -> android.graphics.Color.rgb(0, 229, 255)
                }
                val wptPinScale = 1f + (ts - 1f) * 0.45f
                waypointPinPaint.color = android.graphics.Color.BLACK
                canvas.drawCircle(wx, wy, 9.5f * wptPinScale, waypointPinPaint)
                waypointPinPaint.color = pinColor
                canvas.drawCircle(wx, wy, 7.5f * wptPinScale, waypointPinPaint)
                waypointPinPaint.color = android.graphics.Color.WHITE
                canvas.drawCircle(wx, wy, 3f * wptPinScale, waypointPinPaint)

                if (config.showWaypointLabelsOnTft && drawnTftWptLabels < 20 && wpt.name.isNotBlank()) {
                    drawnTftWptLabels++
                    canvas.save()
                    canvas.translate(wx, wy)
                    canvas.rotate(heading.toFloat())
                    waypointLabelPaint.textSize = 11.5f * ts
                    val label = if (wpt.name.length > 16) wpt.name.take(15) + "…" else wpt.name
                    val tw = waypointLabelPaint.measureText(label)
                    val tagPadX = 6f * wptPinScale
                    val tagBottom = -11f * wptPinScale
                    val tagTop = tagBottom - (15f * ts)
                    tmpTagRect.set(-tw / 2f - tagPadX, tagTop, tw / 2f + tagPadX, tagBottom)
                    canvas.drawRoundRect(tmpTagRect, 5f, 5f, hudCardPaint)
                    canvas.drawText(label, -tw / 2f, tagBottom - 3.5f * ts, waypointLabelPaint)
                    canvas.restore()
                }
            }
        }

        canvas.restore()

        val hudScale = (w / 592f).coerceIn(0.45f, 1.5f)

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
                scale = hudScale,
                nowMs = System.currentTimeMillis()
            )
        } else {
            null
        }

        if (config.showGroupMembersOnTft && groupPlan != null) {
            for (proj in groupPlan.inViewMembers) {
                drawGroupMemberPin(canvas, proj, config.showGroupNamesOnTft, hudScale)
            }
            if (config.showGroupEdgeRadarOnTft) {
                for (ind in groupPlan.edgeIndicators) {
                    drawGroupEdgeIndicator(canvas, ind, anchorX, anchorY, w, h, hudScale)
                }
            }
        }

        // 5. Marcador de la Moto (flecha en anchorX, anchorY)
        canvas.save()
        canvas.translate(anchorX, anchorY)
        if (config.orientation == "north") {
            val riderHead = if (rider.hasValidHeading) rider.headingDegrees else 0.0
            canvas.rotate(riderHead.toFloat())
        }

        arrowPath.reset()
        arrowPath.moveTo(0f, -18f)
        arrowPath.lineTo(13f, 14f)
        arrowPath.lineTo(0f, 9f)
        arrowPath.lineTo(-13f, 14f)
        arrowPath.close()
        canvas.drawPath(arrowPath, riderArrowPaint)

        corePath.reset()
        corePath.moveTo(0f, -10f)
        corePath.lineTo(6f, 9f)
        corePath.lineTo(0f, 6f)
        corePath.lineTo(-6f, 9f)
        corePath.close()
        canvas.drawPath(corePath, riderArrowCorePaint)
        canvas.restore()

        // Escalar HUD proporcionalmente a 592px de referencia para que tanto la preview (296x408)
        // como la pantalla TFT real (592x816) mantengan proporciones exactas sin solapamientos
        val vw = w / hudScale
        val vh = h / hudScale
        canvas.save()
        canvas.scale(hudScale, hudScale)

        // Aplicar escala de texto HUD configurable al cuadro TFT
        speedTextPaint.textSize = 42f * ts
        speedUnitPaint.textSize = 14f * ts
        textPrimaryPaint.textSize = 17f * ts
        textMutedPaint.textSize = 12f * ts
        batteryTextPaint.textSize = 15f * ts
        val boxGrowth = 1f + (ts - 1f) * 0.55f

        // 6. HUD de Moto: Velocímetro (Esquina inferior izquierda)
        val spdStr = "${rider.speedKmh.toInt()}"
        val spdTextW = speedTextPaint.measureText(spdStr)
        val unitTextW = speedUnitPaint.measureText("km/h")
        val speedBoxH = 68f * boxGrowth
        val speedBoxW = maxOf(136f * (1f + (ts - 1f) * 0.42f), 16f + spdTextW + 8f + unitTextW + 14f)
        val speedBox = RectF(14f, vh - speedBoxH - 14f, 14f + speedBoxW, vh - 14f)
        canvas.drawRoundRect(speedBox, 12f, 12f, hudCardPaint)
        canvas.drawRoundRect(speedBox, 12f, 12f, hudBorderPaint)

        canvas.drawText(spdStr, speedBox.left + 14f, speedBox.bottom - 16f * boxGrowth, speedTextPaint)
        canvas.drawText("km/h", speedBox.left + 14f + spdTextW + 7f, speedBox.bottom - 16f * boxGrowth, speedUnitPaint)

        // 7. HUD de Moto: Altitud (Esquina inferior derecha, siempre visible aunque haya enlace o ruta)
        val altStr = "${rider.altitudeM.toInt()} m"
        val altBoxH = 58f * boxGrowth
        val altBoxW = maxOf(
            146f * (1f + (ts - 1f) * 0.35f),
            24f + maxOf(textMutedPaint.measureText("ALTITUD"), textPrimaryPaint.measureText(altStr))
        )
        val infoBox = RectF(vw - altBoxW - 14f, vh - altBoxH - 14f, vw - 14f, vh - 14f)
        canvas.drawRoundRect(infoBox, 12f, 12f, hudCardPaint)
        canvas.drawRoundRect(infoBox, 12f, 12f, hudBorderPaint)
        canvas.drawText("ALTITUD", infoBox.left + 12f, infoBox.top + 19f * boxGrowth, textMutedPaint)
        canvas.drawText(altStr, infoBox.left + 12f, infoBox.bottom - 12f * boxGrowth, textPrimaryPaint)

        // 8. HUD de Moto: Batería y Hora del móvil en tiempo real (Esquina superior derecha)
        val battStatus = getFreshBatteryStatus()
        val pct = if (battStatus.percentage >= 0) battStatus.percentage.coerceIn(0, 100) else 100
        val isCharging = battStatus.isCharging

        val topPillH = 38f * (1f + (ts - 1f) * 0.45f)
        val topPillBottom = 12f + topPillH
        val pctStr = "$pct%"
        val pctTextW = batteryTextPaint.measureText(pctStr)
        val battBoxWidth = maxOf(
            (if (isCharging) 108f else 96f) * (1f + (ts - 1f) * 0.28f),
            (if (isCharging) 52f else 42f) + pctTextW + 10f
        )
        val battBox = RectF(vw - battBoxWidth - 14f, 12f, vw - 14f, topPillBottom)
        canvas.drawRoundRect(battBox, 10f, 10f, hudCardPaint)
        canvas.drawRoundRect(battBox, 10f, 10f, hudBorderPaint)

        // Hora actual justo a la izquierda de la batería
        val clockStr = currentTftClockHm()
        val clockTextW = batteryTextPaint.measureText(clockStr)
        val clockBoxWidth = maxOf(66f * (1f + (ts - 1f) * 0.35f), clockTextW + 16f)
        val clockBox = RectF(battBox.left - 8f - clockBoxWidth, 12f, battBox.left - 8f, topPillBottom)
        canvas.drawRoundRect(clockBox, 10f, 10f, hudCardPaint)
        canvas.drawRoundRect(clockBox, 10f, 10f, hudBorderPaint)
        val pillBaselineY = clockBox.centerY() + (batteryTextPaint.textSize * 0.35f)
        canvas.drawText(
            clockStr,
            clockBox.left + ((clockBoxWidth - clockTextW) * 0.5f).coerceAtLeast(6f),
            pillBaselineY,
            batteryTextPaint
        )

        // Icono de contorno de batería: 22px ancho x 14px alto centrado verticalmente
        val iconLeft = battBox.left + 10f
        val iconTop = battBox.centerY() - 7f
        val iconRight = iconLeft + 22f
        val iconBottom = iconTop + 14f
        val iconBody = RectF(iconLeft, iconTop, iconRight, iconBottom)
        canvas.drawRoundRect(iconBody, 3f, 3f, batteryOutlinePaint)

        // Terminal positivo de la batería
        val tipRect = RectF(iconRight + 1f, iconTop + 4f, iconRight + 3.5f, iconBottom - 4f)
        canvas.drawRoundRect(tipRect, 1f, 1f, batteryOutlinePaint)

        // Nivel de llenado
        val fillPadding = 2.5f
        val maxFillWidth = (iconRight - iconLeft) - (fillPadding * 2)
        val fillWidth = maxFillWidth * (pct / 100f)
        if (fillWidth > 1f) {
            val fillRect = RectF(
                iconLeft + fillPadding,
                iconTop + fillPadding,
                iconLeft + fillPadding + fillWidth,
                iconBottom - fillPadding
            )
            batteryFillPaint.color = when {
                isCharging -> android.graphics.Color.rgb(0, 212, 255) // Cyan en carga
                pct <= 15 -> android.graphics.Color.rgb(255, 69, 58)  // Rojo alarma (< 15%)
                pct <= 30 -> android.graphics.Color.rgb(255, 159, 10) // Naranja advertencia (< 30%)
                else -> android.graphics.Color.rgb(48, 209, 88)       // Verde
            }
            canvas.drawRoundRect(fillRect, 2f, 2f, batteryFillPaint)
        }

        // Rayo de carga o texto del porcentaje
        val textX = if (isCharging) {
            // Dibujar pequeño rayo vector ⚡
            val bx = iconRight + 9f
            val by = battBox.centerY()
            boltPath.reset()
            boltPath.moveTo(bx + 4f, by - 7f)
            boltPath.lineTo(bx - 1f, by + 1f)
            boltPath.lineTo(bx + 3f, by + 1f)
            boltPath.lineTo(bx - 3f, by + 8f)
            boltPath.lineTo(bx + 1f, by + 2f)
            boltPath.lineTo(bx - 3f, by + 2f)
            boltPath.close()
            canvas.drawPath(boltPath, batteryBoltPaint)
            iconRight + 20f
        } else {
            iconRight + 8f
        }
        canvas.drawText(pctStr, textX, pillBaselineY, batteryTextPaint)

        // 9. HUD de Moto: Resumen de Enlace / Ruta / GPX (Esquina superior izquierda, sin instrucciones de giro)
        val hasRoadTiming = remainingKm != null && remainingKm > 0.0 && remainingMinutes != null
        val hasGpxRemaining = !hasRoadTiming && remainingKm != null && (remainingKm > 0.0 || routeTypeLabel == "TRACK")
        val navBoxBottom = 12f + 44f * boxGrowth
        if (hasRoadTiming || hasGpxRemaining) {
            val (topNavLine, mainNavLine) = if (hasRoadTiming && remainingKm != null && remainingMinutes != null) {
                val kind = routeTypeLabel ?: "RUTA"
                val etaStr = formatTftEtaHm(remainingMinutes)
                val kmStr = "${(remainingKm * 10.0).roundToInt() / 10.0} km"
                val durStr = formatTftDuration(remainingMinutes)
                "$kind · Llegada $etaStr" to "$kmStr · $durStr"
            } else {
                val remStr = "${((remainingKm ?: 0.0) * 10.0).roundToInt() / 10.0} km"
                "GPX · RESTAN" to remStr
            }
            val neededNavW = maxOf(
                if (hasRoadTiming) 244f else 156f,
                maxOf(speedUnitPaint.measureText(topNavLine), textPrimaryPaint.measureText(mainNavLine)) + 24f
            )
            val navRight = minOf(clockBox.left - 10f, 14f + neededNavW)
            val navBox = RectF(14f, 12f, navRight, navBoxBottom)
            canvas.drawRoundRect(navBox, 10f, 10f, hudCardPaint)
            canvas.drawRoundRect(navBox, 10f, 10f, hudBorderPaint)

            canvas.drawText(topNavLine, navBox.left + 12f, navBox.top + 17f * boxGrowth, speedUnitPaint)
            canvas.drawText(mainNavLine, navBox.left + 12f, navBox.bottom - 10f * boxGrowth, textPrimaryPaint)
        }

        // 9b. Contador discreto de grupo en HUD (debajo de hora/batería)
        val grpTop = topPillBottom + 6f
        val grpBox = RectF(vw - 86f - 14f, grpTop, vw - 14f, grpTop + 26f)
        if (groupActiveCount > 0 && config.showGroupHudBadgeOnTft) {
            canvas.drawRoundRect(grpBox, 8f, 8f, hudCardPaint)
            canvas.drawRoundRect(grpBox, 8f, 8f, hudBorderPaint)
            val hasAnyAlert = groupMembers.any { it.status.isAlert }
            groupFillPaint.color = if (hasAnyAlert) {
                android.graphics.Color.rgb(255, 59, 48)
            } else {
                android.graphics.Color.rgb(48, 209, 88)
            }
            canvas.drawCircle(grpBox.left + 12f, grpBox.centerY(), 4f, groupFillPaint)
            groupTextPaint.textSize = 12f
            canvas.drawText("GRP $groupActiveCount", grpBox.left + 21f, grpBox.bottom - 8f, groupTextPaint)
        }

        // 9c. Banner de alerta/mensaje del grupo en 2 fases:
        // - Fase 1 (primeros 5 s): grande y destacado arriba
        // - Fase 2 (después de 5 s): compacto en la esquina superior izquierda hasta volver a "En ruta"
        val alertBanner = if (config.showGroupAlertsOnTft) groupPlan?.alertBanner else null
        var alertBannerBottomVw = 0f
        if (alertBanner != null) {
            val maxAlertRight = if (groupActiveCount > 0 && config.showGroupHudBadgeOnTft) {
                grpBox.left - 8f
            } else {
                vw - 14f
            }
            val expanded = alertBanner.isExpanded
            val alertRight = if (expanded) vw - 14f else minOf(maxAlertRight, 14f + 250f)
            if (alertRight > 54f) {
                val alertTop = maxOf(navBoxBottom, topPillBottom) + 6f
                val innerPadX = if (expanded) 12f else 9f
                val innerW = (alertRight - 14f - innerPadX * 2f).coerceAtLeast(40f)
                groupTextPaint.textSize = if (expanded) 15f else 11.2f
                groupSubtextPaint.textSize = if (expanded) 13f else 10.2f

                val headerText = TftNotificationTextLayout.truncateToWidth(
                    text = alertBanner.text,
                    maxWidth = innerW
                ) { groupTextPaint.measureText(it) }
                val msgLines = if (alertBanner.subtext.isNotBlank()) {
                    TftNotificationTextLayout.wrapLines(
                        text = alertBanner.subtext,
                        maxWidth = innerW,
                        maxLines = 2
                    ) { groupSubtextPaint.measureText(it) }
                } else {
                    emptyList()
                }
                val bannerHeight = if (expanded) {
                    when (msgLines.size) {
                        0 -> 38f
                        1 -> 62f
                        else -> 82f
                    }
                } else {
                    when (msgLines.size) {
                        0 -> 26f
                        1 -> 44f
                        else -> 58f
                    }
                }
                val alertBottom = alertTop + bannerHeight
                alertBannerBottomVw = alertBottom
                groupRect.set(14f, alertTop, alertRight, alertBottom)
                val bgAlpha = if (expanded) 248 else 226
                groupAlertBgPaint.color = when (alertBanner.status) {
                    RiderStatus.SOS -> android.graphics.Color.argb(bgAlpha, 205, 24, 24)
                    RiderStatus.MECHANICAL -> android.graphics.Color.argb(bgAlpha, 214, 88, 0)
                    RiderStatus.STOPPED_WAIT -> android.graphics.Color.argb(bgAlpha, 172, 116, 0)
                    else -> android.graphics.Color.argb(bgAlpha, 16, 98, 178)
                }
                groupStrokePaint.color = android.graphics.Color.WHITE
                groupStrokePaint.strokeWidth = if (expanded) 2.2f else 1.4f
                val cornerR = if (expanded) 11f else 8f
                canvas.drawRoundRect(groupRect, cornerR, cornerR, groupAlertBgPaint)
                canvas.drawRoundRect(groupRect, cornerR, cornerR, groupStrokePaint)
                canvas.drawText(
                    headerText,
                    groupRect.left + innerPadX,
                    alertTop + (if (expanded) 23f else 17.5f),
                    groupTextPaint
                )
                if (msgLines.isNotEmpty()) {
                    canvas.drawText(
                        msgLines[0],
                        groupRect.left + innerPadX,
                        alertTop + (if (expanded) 44f else 33f),
                        groupSubtextPaint
                    )
                }
                if (msgLines.size >= 2) {
                    canvas.drawText(
                        msgLines[1],
                        groupRect.left + innerPadX,
                        alertTop + (if (expanded) 62f else 46f),
                        groupSubtextPaint
                    )
                }
                if (expanded && alertBanner.expandedProgress > 0f) {
                    val trackL = groupRect.left + 10f
                    val trackR = groupRect.right - 10f
                    val trackT = groupRect.bottom - 6f
                    val trackB = groupRect.bottom - 3f
                    val fillW = (trackR - trackL) * alertBanner.expandedProgress
                    if (fillW > 2f) {
                        notifTrackRect.set(trackL, trackT, trackR, trackB)
                        notifBgPaint.color = android.graphics.Color.argb(70, 255, 255, 255)
                        canvas.drawRoundRect(notifTrackRect, 1.5f, 1.5f, notifBgPaint)
                        notifProgressRect.set(trackL, trackT, trackL + fillW, trackB)
                        notifBgPaint.color = android.graphics.Color.WHITE
                        canvas.drawRoundRect(notifProgressRect, 1.5f, 1.5f, notifBgPaint)
                    }
                }
            }
        }

        // 9d. Notificación emergente del móvil (Heads-up banner temporal)
        if (config.showPhoneNotificationsOnTft && activeNotification != null) {
            drawPhoneNotificationBanner(
                canvas = canvas,
                vw = vw,
                notif = activeNotification,
                alertBannerBottomVw = alertBannerBottomVw
            )
        }

        // 9e. Advertencia configurable de salida del track GPX activo
        val gpxWarnThreshold = config.gpxOffTrackThresholdMeters.toDouble().coerceIn(20.0, 200.0)
        if (config.showGpxOffTrackWarning && gpxOffTrackMeters != null && gpxOffTrackMeters >= gpxWarnThreshold) {
            val warnLeft = speedBox.right + 10f
            val warnRight = infoBox.left - 10f
            if (warnRight > warnLeft + 110f) {
                val warnH = 50f * boxGrowth
                groupRect.set(warnLeft, vh - warnH - 14f, warnRight, vh - 14f)
                canvas.drawRoundRect(groupRect, 10f, 10f, gpxReturnWarnBgPaint)
                canvas.drawRoundRect(groupRect, 10f, 10f, gpxReturnWarnStrokePaint)

                val distLabel = GroupTftLayout.formatCompactDistance(gpxOffTrackMeters)
                val dirLabel = if (rider.coordinate != null && gpxOffTrackTarget != null) {
                    val brg = RouteNavigator.compassBearing(rider.coordinate, gpxOffTrackTarget)
                    val dirs = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
                    " · ${dirs[((brg + 22.5) / 45.0).toInt() % 8]}"
                } else {
                    ""
                }
                val warnCenterX = groupRect.centerX()
                gpxReturnWarnTextPaint.color = android.graphics.Color.rgb(255, 224, 90)
                gpxReturnWarnTextPaint.textSize = 11.5f * ts.coerceAtMost(1.25f)
                canvas.drawText("⚠ FUERA DEL GPX", warnCenterX, groupRect.top + 19f * boxGrowth, gpxReturnWarnTextPaint)
                gpxReturnWarnTextPaint.color = android.graphics.Color.WHITE
                gpxReturnWarnTextPaint.textSize = 13.5f * ts.coerceAtMost(1.25f)
                canvas.drawText("A $distLabel$dirLabel", warnCenterX, groupRect.bottom - 10f * boxGrowth, gpxReturnWarnTextPaint)
            }
        }

        canvas.restore()

        // 10. BlackEdges (Márgenes negros de 4px arriba y abajo requeridos por el escalador Carbit EasyConn)
        canvas.drawRect(0f, 0f, w, 4f, blackEdgePaint)
        canvas.drawRect(0f, h - 4f, w, h, blackEdgePaint)
    }

    private fun drawPhoneNotificationBanner(
        canvas: Canvas,
        vw: Float,
        notif: TftPhoneNotification,
        alertBannerBottomVw: Float
    ) {
        val nowMs = System.currentTimeMillis()
        if (!notif.isActive(nowMs)) return

        val elapsed = notif.elapsedMs(nowMs)
        val remaining = notif.remainingMs(nowMs)
        val alphaFactor = when {
            elapsed < 180L -> 0.72f + 0.28f * (elapsed / 180f).coerceIn(0f, 1f)
            remaining < 220L -> (remaining / 220f).coerceIn(0f, 1f)
            else -> 1f
        }
        val slideOffsetY = when {
            elapsed < 180L -> -(1f - (elapsed / 180f).coerceIn(0f, 1f)) * 12f
            remaining < 220L -> -(1f - (remaining / 220f).coerceIn(0f, 1f)) * 16f
            else -> 0f
        }

        val left = 14f
        val right = vw - 14f
        if (right <= left + 120f) return

        val top = (if (alertBannerBottomVw > 0f) alertBannerBottomVw + 6f else 58f) + slideOffsetY
        val textLeft = left + 54f
        val textMaxW = (right - 14f - textLeft).coerceAtLeast(60f)

        val appText = TftNotificationTextLayout.truncateToWidth(
            text = notif.appName.uppercase(),
            maxWidth = (textMaxW - 52f).coerceAtLeast(40f)
        ) { notifAppPaint.measureText(it) }

        val titleText = TftNotificationTextLayout.truncateToWidth(
            text = notif.title,
            maxWidth = textMaxW
        ) { notifTitlePaint.measureText(it) }

        val bodyLines = TftNotificationTextLayout.wrapLines(
            text = notif.text,
            maxWidth = textMaxW,
            maxLines = 2
        ) { notifBodyPaint.measureText(it) }

        val cardH = when (bodyLines.size) {
            0 -> 58f
            1 -> 74f
            else -> 92f
        }
        val bottom = top + cardH

        val accentRgb = notif.accentArgb.toInt()
        val accentR = (accentRgb ushr 16) and 0xFF
        val accentG = (accentRgb ushr 8) and 0xFF
        val accentB = accentRgb and 0xFF
        val bgAlpha = (246f * alphaFactor).roundToInt().coerceIn(0, 255)
        val fgAlpha = (255f * alphaFactor).roundToInt().coerceIn(0, 255)

        notifRect.set(left, top, right, bottom)
        notifBgPaint.color = android.graphics.Color.argb(bgAlpha, 18, 22, 30)
        canvas.drawRoundRect(notifRect, 14f, 14f, notifBgPaint)

        notifStrokePaint.color = android.graphics.Color.argb(fgAlpha, accentR, accentG, accentB)
        canvas.drawRoundRect(notifRect, 14f, 14f, notifStrokePaint)

        // Avatar circular izquierdo con icono vectorial por categoría
        val cx = left + 29f
        val cy = top + (cardH - 4f) * 0.5f
        notifBgPaint.color = android.graphics.Color.argb(fgAlpha, accentR, accentG, accentB)
        canvas.drawCircle(cx, cy, 16f, notifBgPaint)

        drawCategoryIcon(canvas, notif.category, cx, cy, fgAlpha)

        // Cabecera: Nombre de app + AHORA
        notifAppPaint.color = android.graphics.Color.argb(fgAlpha, accentR, accentG, accentB)
        canvas.drawText(appText, textLeft, top + 20f, notifAppPaint)

        val nowLbl = "AHORA"
        val nowW = notifTimePaint.measureText(nowLbl)
        notifTimePaint.color = android.graphics.Color.argb((190f * alphaFactor).roundToInt().coerceIn(0, 255), 165, 175, 188)
        canvas.drawText(nowLbl, right - 14f - nowW, top + 20f, notifTimePaint)

        // Título / remitente
        notifTitlePaint.color = android.graphics.Color.argb(fgAlpha, 255, 255, 255)
        canvas.drawText(titleText, textLeft, top + 39f, notifTitlePaint)

        // Cuerpo en 1 o 2 líneas
        notifBodyPaint.color = android.graphics.Color.argb(fgAlpha, 224, 232, 240)
        if (bodyLines.isNotEmpty()) {
            canvas.drawText(bodyLines[0], textLeft, top + 57f, notifBodyPaint)
        }
        if (bodyLines.size >= 2) {
            canvas.drawText(bodyLines[1], textLeft, top + 74f, notifBodyPaint)
        }

        // Barra inferior de progreso del tiempo restante
        val trackLeft = left + 12f
        val trackRight = right - 12f
        val trackTop = bottom - 6f
        val trackBottom = bottom - 3f
        notifTrackRect.set(trackLeft, trackTop, trackRight, trackBottom)
        notifBgPaint.color = android.graphics.Color.argb((45f * alphaFactor).roundToInt().coerceIn(0, 255), 255, 255, 255)
        canvas.drawRoundRect(notifTrackRect, 1.5f, 1.5f, notifBgPaint)

        val progress = notif.remainingProgress(nowMs)
        val fillW = (trackRight - trackLeft) * progress
        if (fillW > 2f) {
            notifProgressRect.set(trackLeft, trackTop, trackLeft + fillW, trackBottom)
            notifBgPaint.color = android.graphics.Color.argb(fgAlpha, accentR, accentG, accentB)
            canvas.drawRoundRect(notifProgressRect, 1.5f, 1.5f, notifBgPaint)
        }
    }

    private fun drawCategoryIcon(
        canvas: Canvas,
        category: TftNotificationCategory,
        cx: Float,
        cy: Float,
        fgAlpha: Int
    ) {
        val whiteArgb = android.graphics.Color.argb(fgAlpha, 255, 255, 255)
        notifIconStrokePaint.color = whiteArgb
        notifIconFillPaint.color = whiteArgb
        notifIconPath.reset()

        when (category) {
            TftNotificationCategory.MESSAGE -> {
                notifIconPath.moveTo(cx - 7f, cy - 5.5f)
                notifIconPath.lineTo(cx + 7f, cy - 5.5f)
                notifIconPath.lineTo(cx + 7f, cy + 3.5f)
                notifIconPath.lineTo(cx - 1.5f, cy + 3.5f)
                notifIconPath.lineTo(cx - 5.5f, cy + 7f)
                notifIconPath.lineTo(cx - 5f, cy + 3.5f)
                notifIconPath.lineTo(cx - 7f, cy + 3.5f)
                notifIconPath.close()
                canvas.drawPath(notifIconPath, notifIconStrokePaint)
            }
            TftNotificationCategory.CALL -> {
                notifIconPath.moveTo(cx - 5.5f, cy - 6.5f)
                notifIconPath.lineTo(cx - 2f, cy - 6.5f)
                notifIconPath.lineTo(cx - 1f, cy - 3f)
                notifIconPath.lineTo(cx - 3f, cy - 1f)
                notifIconPath.lineTo(cx + 1f, cy + 3f)
                notifIconPath.lineTo(cx + 3f, cy + 1f)
                notifIconPath.lineTo(cx + 6.5f, cy + 2f)
                notifIconPath.lineTo(cx + 6.5f, cy + 5.5f)
                notifIconPath.lineTo(cx + 3f, cy + 6.5f)
                notifIconPath.lineTo(cx - 2.5f, cy + 3.5f)
                notifIconPath.lineTo(cx - 6.5f, cy - 2.5f)
                notifIconPath.close()
                canvas.drawPath(notifIconPath, notifIconStrokePaint)
            }
            TftNotificationCategory.NAVIGATION -> {
                notifIconPath.moveTo(cx, cy - 7.5f)
                notifIconPath.lineTo(cx + 6.5f, cy + 6.5f)
                notifIconPath.lineTo(cx, cy + 3f)
                notifIconPath.lineTo(cx - 6.5f, cy + 6.5f)
                notifIconPath.close()
                canvas.drawPath(notifIconPath, notifIconFillPaint)
            }
            TftNotificationCategory.ALERT -> {
                notifIconPath.moveTo(cx, cy - 7f)
                notifIconPath.lineTo(cx + 7f, cy + 5.5f)
                notifIconPath.lineTo(cx - 7f, cy + 5.5f)
                notifIconPath.close()
                canvas.drawPath(notifIconPath, notifIconStrokePaint)
                notifIconPath.reset()
                notifIconPath.moveTo(cx, cy - 2.5f)
                notifIconPath.lineTo(cx, cy + 1.5f)
                canvas.drawPath(notifIconPath, notifIconStrokePaint)
                canvas.drawCircle(cx, cy + 3.7f, 1.1f, notifIconFillPaint)
            }
            TftNotificationCategory.APP -> {
                notifIconPath.moveTo(cx - 5f, cy + 2.5f)
                notifIconPath.lineTo(cx - 5f, cy - 2.5f)
                notifIconPath.lineTo(cx - 2.5f, cy - 6f)
                notifIconPath.lineTo(cx + 2.5f, cy - 6f)
                notifIconPath.lineTo(cx + 5f, cy - 2.5f)
                notifIconPath.lineTo(cx + 5f, cy + 2.5f)
                notifIconPath.lineTo(cx + 7f, cy + 4f)
                notifIconPath.lineTo(cx - 7f, cy + 4f)
                notifIconPath.close()
                canvas.drawPath(notifIconPath, notifIconStrokePaint)
                canvas.drawCircle(cx, cy + 6.2f, 1.3f, notifIconFillPaint)
            }
        }
    }

    private fun drawGroupMemberPin(
        canvas: Canvas,
        proj: TftProjectedMember,
        showNames: Boolean,
        scale: Float
    ) {
        val sx = proj.screenX
        val sy = proj.screenY
        val baseArgb = proj.member.color.argb.toInt()
        val fillColor = if (proj.isStale) (baseArgb and 0x00FFFFFF) or (140 shl 24) else baseArgb

        if (proj.member.status.isAlert) {
            val alertRgb = when (proj.member.status) {
                RiderStatus.SOS -> android.graphics.Color.rgb(255, 59, 48)
                RiderStatus.MECHANICAL -> android.graphics.Color.rgb(255, 107, 0)
                else -> android.graphics.Color.rgb(255, 204, 0)
            }
            groupAlertBgPaint.color = (alertRgb and 0x00FFFFFF) or (90 shl 24)
            canvas.drawCircle(sx, sy, 18f * scale, groupAlertBgPaint)
            groupStrokePaint.color = alertRgb
            groupStrokePaint.strokeWidth = 2.2f * scale
            canvas.drawCircle(sx, sy, 16f * scale, groupStrokePaint)
        }

        groupFillPaint.color = android.graphics.Color.BLACK
        canvas.drawCircle(sx, sy, 11.5f * scale, groupFillPaint)
        groupFillPaint.color = fillColor
        canvas.drawCircle(sx, sy, 9.2f * scale, groupFillPaint)

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
            groupFillPaint.color = android.graphics.Color.WHITE
            canvas.drawPath(groupArrowPath, groupFillPaint)
            canvas.restore()
        } else {
            groupFillPaint.color = android.graphics.Color.WHITE
            canvas.drawCircle(sx, sy, 3.3f * scale, groupFillPaint)
        }

        if (showNames) {
            val shortName = GroupTftLayout.formatShortName(proj.member.name)
            groupTextPaint.textSize = 11f * scale
            val tw = groupTextPaint.measureText(shortName)
            val padX = 5.5f * scale
            val pillH = 16f * scale
            val bottom = sy - 14f * scale
            val top = bottom - pillH
            groupRect.set(sx - tw * 0.5f - padX, top, sx + tw * 0.5f + padX, bottom)
            canvas.drawRoundRect(groupRect, 4f * scale, 4f * scale, hudCardPaint)
            groupStrokePaint.color = fillColor
            groupStrokePaint.strokeWidth = 1.2f * scale
            canvas.drawRoundRect(groupRect, 4f * scale, 4f * scale, groupStrokePaint)
            canvas.drawText(shortName, sx - tw * 0.5f, bottom - 4f * scale, groupTextPaint)
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
        val baseRgb = when (ind.member.status) {
            RiderStatus.SOS -> android.graphics.Color.rgb(255, 59, 48)
            RiderStatus.MECHANICAL -> android.graphics.Color.rgb(255, 107, 0)
            RiderStatus.STOPPED_WAIT -> android.graphics.Color.rgb(255, 204, 0)
            else -> ind.member.color.argb.toInt()
        }
        val color = if (ind.isStale) (baseRgb and 0x00FFFFFF) or (155 shl 24) else baseRgb

        val deg = Math.toDegrees(ind.angleRad.toDouble()).toFloat() + 90f
        canvas.save()
        canvas.translate(ind.edgeX, ind.edgeY)
        canvas.rotate(deg)
        groupArrowPath.reset()
        groupArrowPath.moveTo(0f, -9f * scale)
        groupArrowPath.lineTo(6.5f * scale, 6f * scale)
        groupArrowPath.lineTo(0f, 3f * scale)
        groupArrowPath.lineTo(-6.5f * scale, 6f * scale)
        groupArrowPath.close()
        groupFillPaint.color = android.graphics.Color.BLACK
        canvas.drawPath(groupArrowPath, groupFillPaint)
        groupStrokePaint.color = android.graphics.Color.BLACK
        groupStrokePaint.strokeWidth = 2.5f * scale
        canvas.drawPath(groupArrowPath, groupStrokePaint)
        groupFillPaint.color = color
        canvas.drawPath(groupArrowPath, groupFillPaint)
        canvas.restore()

        groupTextPaint.textSize = 11f * scale
        val tw = groupTextPaint.measureText(ind.label)
        val padX = 5.5f * scale
        val pillW = tw + padX * 2f
        val pillH = 17f * scale
        val inDx = anchorX - ind.edgeX
        val inDy = anchorY - ind.edgeY
        val inLen = hypot(inDx, inDy).coerceAtLeast(1f)
        val cx = ind.edgeX + (inDx / inLen) * 20f * scale
        val cy = ind.edgeY + (inDy / inLen) * 16f * scale

        val left = (cx - pillW * 0.5f).coerceIn(8f * scale, (w - pillW - 8f * scale).coerceAtLeast(8f * scale))
        val top = (cy - pillH * 0.5f).coerceIn(60f * scale, (h - pillH - 22f * scale).coerceAtLeast(60f * scale))
        groupRect.set(left, top, left + pillW, top + pillH)
        canvas.drawRoundRect(groupRect, 5f * scale, 5f * scale, hudCardPaint)
        groupStrokePaint.color = color
        groupStrokePaint.strokeWidth = 1.4f * scale
        canvas.drawRoundRect(groupRect, 5f * scale, 5f * scale, groupStrokePaint)
        canvas.drawText(ind.label, left + padX, groupRect.bottom - 4.5f * scale, groupTextPaint)
    }

    private fun getOrCreateBitmap(width: Int, height: Int): Bitmap {
        val cur = cachedBitmap
        if (cur != null && cur.width == width && cur.height == height) {
            return cur
        }
        val newBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        cachedBitmap = newBmp
        cachedCanvas = Canvas(newBmp)
        rgbaBuffer = ByteArray(width * height * 4)
        return newBmp
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

    private fun drawBatchedChevrons(canvas: Canvas, scale: Float) {
        dirArrowHaloPaint.strokeWidth = (4.4f * scale).coerceAtLeast(2.5f)
        dirArrowFillPaint.strokeWidth = (2.2f * scale).coerceAtLeast(1.4f)
        canvas.drawPath(chevronPath, dirArrowHaloPaint)
        canvas.drawPath(chevronPath, dirArrowFillPaint)
    }
}
