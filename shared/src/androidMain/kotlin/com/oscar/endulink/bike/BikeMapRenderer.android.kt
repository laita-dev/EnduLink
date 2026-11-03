package com.oscar.endulink.bike

import android.graphics.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.oscar.endulink.platform.RiderTelemetry
import com.oscar.endulink.tiles.MapLayer
import com.oscar.endulink.tiles.TileCoord
import com.oscar.endulink.tiles.TileMath
import com.oscar.endulink.tiles.TileStore
import com.oscar.endulink.trail.GPXRoute
import com.oscar.endulink.trail.GeoCoord
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.roundToInt

actual fun getWifiSnapshot(): WifiSnapshot? {
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.name.lowercase()
            if (name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("ap")) {
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        val parts = host.split(".")
                        if (parts.size == 4) {
                            val gateway = "${parts[0]}.${parts[1]}.${parts[2]}.1"
                            return WifiSnapshot(phoneIP = host, gatewayCandidateIP = gateway)
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

    private var previewBitmap: Bitmap? = null
    private var previewCanvas: Canvas? = null

    // Paints preconfigurados para alto rendimiento (cero allocations por frame)
    private val bgPaint = Paint().apply { color = android.graphics.Color.rgb(20, 23, 28) }
    private val blackEdgePaint = Paint().apply { color = android.graphics.Color.BLACK }
    private val gridPaint = Paint().apply {
        color = android.graphics.Color.rgb(30, 34, 45)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
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
        nextInstruction: String?
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
            nextInstruction = nextInstruction
        )

        bmp.copyPixelsToBuffer(ByteBuffer.wrap(buffer))
        return buffer
    }

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
        nextInstruction: String?
    ): ImageBitmap? {
        val cur = previewBitmap
        val bmp = if (cur != null && cur.width == width && cur.height == height) cur else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                previewBitmap = it
                previewCanvas = Canvas(it)
            }
        }
        val canvas = previewCanvas ?: Canvas(bmp).also { previewCanvas = it }
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
            nextInstruction = nextInstruction
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
        nextInstruction: String?
    ) {
        val w = width
        val h = height

        // 1. Fondo oscuro base
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Parámetros de navegación del mapa
        val center = rider.coordinate
            ?: activeRoute?.points?.firstOrNull()?.coordinate
            ?: GeoCoord(40.4168, -3.7038)

        // Auto-zoom dinámico según velocidad (1:1 con MotIOS: Z17 en parado, Z15 crucero, Z13 autovía)
        val zoom = if (config.autoZoomBySpeed) {
            when {
                rider.speedKmh < 25.0 -> 17
                rider.speedKmh < 75.0 -> 15
                else -> 13
            }
        } else {
            config.zoom
        }

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

        // Dibujar teselas 5x5 alrededor del centro
        for (dx in -3..3) {
            val tx = centerTileX + dx
            if (tx < 0 || tx > maxTileIndex) continue
            for (dy in -3..3) {
                val ty = centerTileY + dy
                if (ty < 0 || ty > maxTileIndex) continue

                val posX = ((tx - centerFracX) * tileSizePx).toFloat()
                val posY = ((ty - centerFracY) * tileSizePx).toFloat()

                val tileCoord = TileCoord(layer, zoom, tx, ty)
                val bytes = tileStore.getTileBytes(tileCoord, allowAsyncFetch = true)
                var drewTile = false
                if (bytes != null && bytes.size > 100) {
                    val tileBmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (tileBmp != null) {
                        val dst = RectF(posX, posY, posX + tileSizePx, posY + tileSizePx)
                        canvas.drawBitmap(tileBmp, null, dst, null)
                        drewTile = true
                    }
                }

                if (!drewTile) {
                    val oz = tileStore.getOverzoomedParent(tileCoord)
                    if (oz != null && oz.parentTileBytes.size > 100) {
                        val parentBmp = BitmapFactory.decodeByteArray(oz.parentTileBytes, 0, oz.parentTileBytes.size)
                        if (parentBmp != null) {
                            val subW = parentBmp.width / oz.scale
                            val subH = parentBmp.height / oz.scale
                            val src = Rect(oz.subX * subW, oz.subY * subH, (oz.subX + 1) * subW, (oz.subY + 1) * subH)
                            val dst = RectF(posX, posY, posX + tileSizePx, posY + tileSizePx)
                            canvas.drawBitmap(parentBmp, src, dst, null)
                            drewTile = true
                        }
                    }
                }

                if (!drewTile) {
                    canvas.drawRect(posX, posY, posX + tileSizePx, posY + tileSizePx, gridPaint)
                }
            }
        }

        // 4. Polilíneas de rutas
        if (activeRoute != null && activeRoute.points.size >= 2) {
            val path = Path()
            val first = geoToLocal(activeRoute.points[0].coordinate)
            path.moveTo(first.first, first.second)
            for (i in 1 until activeRoute.points.size) {
                val p = geoToLocal(activeRoute.points[i].coordinate)
                path.lineTo(p.first, p.second)
            }
            canvas.drawPath(path, routePaint)
        }

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

        canvas.restore()

        // 5. Marcador de la Moto (flecha en anchorX, anchorY)
        canvas.save()
        canvas.translate(anchorX, anchorY)
        if (config.orientation == "north") {
            val riderHead = if (rider.hasValidHeading) rider.headingDegrees else 0.0
            canvas.rotate(riderHead.toFloat())
        }

        val arrowPath = Path().apply {
            moveTo(0f, -18f)
            lineTo(13f, 14f)
            lineTo(0f, 9f)
            lineTo(-13f, 14f)
            close()
        }
        canvas.drawPath(arrowPath, riderArrowPaint)

        val corePath = Path().apply {
            moveTo(0f, -10f)
            lineTo(6f, 9f)
            lineTo(0f, 6f)
            lineTo(-6f, 9f)
            close()
        }
        canvas.drawPath(corePath, riderArrowCorePaint)
        canvas.restore()

        // 6. HUD de Moto: Velocímetro (Esquina inferior izquierda)
        val speedBox = RectF(14f, h - 82f, 150f, h - 14f)
        canvas.drawRoundRect(speedBox, 12f, 12f, hudCardPaint)
        canvas.drawRoundRect(speedBox, 12f, 12f, hudBorderPaint)

        val spdStr = "${rider.speedKmh.toInt()}"
        canvas.drawText(spdStr, speedBox.left + 16f, speedBox.bottom - 18f, speedTextPaint)
        canvas.drawText("km/h", speedBox.left + 18f + speedTextPaint.measureText(spdStr) + 6f, speedBox.bottom - 18f, speedUnitPaint)

        // 7. HUD de Moto: Distancia restante o Altitud (Esquina inferior derecha)
        val infoBox = RectF(w - 160f, h - 72f, w - 14f, h - 14f)
        canvas.drawRoundRect(infoBox, 12f, 12f, hudCardPaint)
        canvas.drawRoundRect(infoBox, 12f, 12f, hudBorderPaint)

        if (remainingKm != null && remainingKm > 0.0) {
            val remStr = "${(remainingKm * 10.0).roundToInt() / 10.0} km"
            canvas.drawText("RESTAN", infoBox.left + 12f, infoBox.top + 20f, textMutedPaint)
            canvas.drawText(remStr, infoBox.left + 12f, infoBox.bottom - 14f, textPrimaryPaint)
        } else {
            val altStr = "${rider.altitudeM.toInt()} m"
            canvas.drawText("ALTITUD", infoBox.left + 12f, infoBox.top + 20f, textMutedPaint)
            canvas.drawText(altStr, infoBox.left + 12f, infoBox.bottom - 14f, textPrimaryPaint)
        }

        // 8. HUD de Moto: Próximo giro / Instrucción (Barra superior)
        if (isNavigating && !nextInstruction.isNullOrBlank()) {
            val navBox = RectF(14f, 12f, w - 14f, 58f)
            canvas.drawRoundRect(navBox, 10f, 10f, hudCardPaint)
            canvas.drawRoundRect(navBox, 10f, 10f, hudBorderPaint)
            canvas.drawText("↳ $nextInstruction", navBox.left + 14f, navBox.top + 30f, textPrimaryPaint)
        }

        // 9. BlackEdges (Márgenes negros de 4px arriba y abajo requeridos por el escalador Carbit EasyConn)
        canvas.drawRect(0f, 0f, w, 4f, blackEdgePaint)
        canvas.drawRect(0f, h - 4f, w, h, blackEdgePaint)
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
}
