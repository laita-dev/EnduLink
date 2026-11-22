package com.oscar.endulink.group

import com.oscar.endulink.tiles.TileMath
import com.oscar.endulink.trail.GeoCoord
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class TftProjectedMember(
    val member: GroupMember,
    val screenX: Float,
    val screenY: Float,
    val relativeHeadingDeg: Float,
    val distanceMeters: Double,
    val isOnScreen: Boolean,
    val isStale: Boolean
)

data class TftEdgeIndicator(
    val member: GroupMember,
    val edgeX: Float,
    val edgeY: Float,
    val angleRad: Float,
    val label: String,
    val distanceMeters: Double,
    val isAlert: Boolean,
    val isStale: Boolean
)

data class TftGroupAlertBanner(
    val member: GroupMember,
    val text: String,
    val subtext: String = "",
    val status: RiderStatus,
    val isExpanded: Boolean = false,
    val expandedProgress: Float = 0f
)

data class TftGroupOverlayPlan(
    val inViewMembers: List<TftProjectedMember>,
    val edgeIndicators: List<TftEdgeIndicator>,
    val alertBanner: TftGroupAlertBanner?
)

/**
 * Motor geométrico compartido (Android + iOS) para proyectar compañeros, radar en bordes
 * y alertas rápidas sobre el lienzo TFT (`592x816` o preview escalada).
 */
object GroupTftLayout {
    const val ALERT_EXPANDED_DURATION_MS = 5_000L

    fun formatCompactDistance(meters: Double): String {
        if (meters < 1000.0) {
            val m = maxOf(10, (meters / 10.0).roundToInt() * 10)
            return "${m}M"
        }
        val km = meters / 1000.0
        return if (km < 100.0) {
            val tenths = (km * 10.0).roundToInt()
            val whole = tenths / 10
            val dec = tenths % 10
            if (dec == 0) "${whole}K" else "${whole}.${dec}K"
        } else {
            "${km.roundToInt()}K"
        }
    }

    fun formatShortName(name: String): String {
        val clean = normalizeTftAscii(name, maxLen = 8)
        return clean.ifBlank { "COMP" }
    }

    fun formatBannerMessage(message: String): String {
        return normalizeTftAscii(message, maxLen = 60)
    }

    private fun normalizeTftAscii(raw: String, maxLen: Int): String {
        val clean = buildString {
            var lastWasSpace = false
            for (ch in raw.trim().uppercase()) {
                val mapped = when (ch) {
                    'Á', 'À', 'Ä', 'Â' -> 'A'
                    'É', 'È', 'Ë', 'Ê' -> 'E'
                    'Í', 'Ì', 'Ï', 'Î' -> 'I'
                    'Ó', 'Ò', 'Ö', 'Ô' -> 'O'
                    'Ú', 'Ù', 'Ü', 'Û' -> 'U'
                    'Ñ' -> 'N'
                    'Ç' -> 'C'
                    '\n', '\r', '\t' -> ' '
                    else -> ch
                }
                if (mapped == ' ') {
                    if (!lastWasSpace && isNotEmpty()) {
                        append(' ')
                        lastWasSpace = true
                    }
                } else {
                    append(mapped)
                    lastWasSpace = false
                }
                if (length >= maxLen) break
            }
        }
        return clean.trim()
    }

    fun compute(
        members: List<GroupMember>,
        mapCenter: GeoCoord,
        zoom: Int,
        tileSizePx: Float,
        mapHeadingDeg: Double,
        anchorX: Float,
        anchorY: Float,
        width: Float,
        height: Float,
        scale: Float,
        nowMs: Long
    ): TftGroupOverlayPlan {
        if (members.isEmpty()) {
            return TftGroupOverlayPlan(emptyList(), emptyList(), null)
        }

        val (centerFracX, centerFracY) = TileMath.latLonToFractionalTile(
            mapCenter.latitude,
            mapCenter.longitude,
            zoom
        )
        val rad = -mapHeadingDeg * PI / 180.0
        val cosR = cos(rad)
        val sinR = sin(rad)

        val visiblePadX = 18f * scale
        val visibleTop = 64f * scale
        val visibleBottom = height - 86f * scale

        val safeLeft = 56f * scale
        val safeRight = (width - 56f * scale).coerceAtLeast(safeLeft + 10f)
        val safeTop = 104f * scale
        val safeBottom = (height - 104f * scale).coerceAtLeast(safeTop + 10f)

        val inView = ArrayList<TftProjectedMember>()
        val offScreen = ArrayList<TftProjectedMember>()

        for (member in members) {
            val (fx, fy) = TileMath.latLonToFractionalTile(member.latitude, member.longitude, zoom)
            val lx = (fx - centerFracX) * tileSizePx
            val ly = (fy - centerFracY) * tileSizePx
            val sx = (anchorX + (lx * cosR - ly * sinR)).toFloat()
            val sy = (anchorY + (lx * sinR + ly * cosR)).toFloat()

            val distM = mapCenter.distanceTo(member.coordinate)
            val stale = member.isStale(nowMs)
            val relHead = (((member.headingDegrees - mapHeadingDeg) % 360.0 + 360.0) % 360.0).toFloat()

            val onScreen = sx in visiblePadX..(width - visiblePadX) && sy in visibleTop..visibleBottom
            val projected = TftProjectedMember(
                member = member,
                screenX = sx,
                screenY = sy,
                relativeHeadingDeg = relHead,
                distanceMeters = distM,
                isOnScreen = onScreen,
                isStale = stale
            )
            if (onScreen) {
                inView.add(projected)
            } else {
                offScreen.add(projected)
            }
        }

        // Priorizar alertas y compañeros más cercanos para el radar en bordes (máximo 6)
        val prioritizedOffScreen = offScreen
            .sortedWith(
                compareByDescending<TftProjectedMember> { it.member.status.alertPriority }
                    .thenByDescending { it.member.hasActiveTftAlert }
                    .thenBy { it.distanceMeters }
            )
            .take(6)

        val rawIndicators = prioritizedOffScreen.map { proj ->
            val dx = proj.screenX - anchorX
            val dy = proj.screenY - anchorY
            var tMin = Float.MAX_VALUE
            if (dx > 1e-3f) {
                val t = (safeRight - anchorX) / dx
                if (t > 0f && t < tMin) tMin = t
            } else if (dx < -1e-3f) {
                val t = (safeLeft - anchorX) / dx
                if (t > 0f && t < tMin) tMin = t
            }
            if (dy > 1e-3f) {
                val t = (safeBottom - anchorY) / dy
                if (t > 0f && t < tMin) tMin = t
            } else if (dy < -1e-3f) {
                val t = (safeTop - anchorY) / dy
                if (t > 0f && t < tMin) tMin = t
            }
            if (tMin == Float.MAX_VALUE) tMin = 1f

            val ex = (anchorX + dx * tMin).coerceIn(safeLeft, safeRight)
            val ey = (anchorY + dy * tMin).coerceIn(safeTop, safeBottom)
            val angle = atan2(dy, dx)
            val distStr = formatCompactDistance(proj.distanceMeters)
            val label = if (proj.member.hasActiveTftAlert) {
                val tag = proj.member.status.tftTag.ifBlank { "MSG" }
                "!$tag ${proj.member.initials()}"
            } else {
                "${proj.member.initials()} $distStr"
            }
            TftEdgeIndicator(
                member = proj.member,
                edgeX = ex,
                edgeY = ey,
                angleRad = angle,
                label = label,
                distanceMeters = proj.distanceMeters,
                isAlert = proj.member.hasActiveTftAlert,
                isStale = proj.isStale
            )
        }

        val resolvedIndicators = separateCloseIndicators(rawIndicators, safeTop, safeBottom, 26f * scale)

        // Banner en 2 fases:
        // - Primeros 5 s tras cambiar estado o recibir mensaje nuevo -> grande y destacado (`isExpanded = true`)
        // - Pasados 5 s -> pequeño en la esquina superior izquierda hasta volver a "En ruta" sin mensaje
        val alertCandidates = (inView + offScreen).filter { it.member.hasActiveTftAlert }
        val recentlyChanged = alertCandidates
            .filter {
                it.member.statusChangedAtEpochMs > 0L &&
                    (nowMs - it.member.statusChangedAtEpochMs) in 0L until ALERT_EXPANDED_DURATION_MS
            }
            .maxWithOrNull(
                compareBy<TftProjectedMember> { it.member.statusChangedAtEpochMs }
                    .thenBy { it.member.status.alertPriority }
                    .thenBy { -it.distanceMeters }
            )
        val topAlert = recentlyChanged ?: alertCandidates.maxWithOrNull(
            compareBy<TftProjectedMember> { it.member.status.alertPriority }
                .thenBy { it.member.statusChangedAtEpochMs }
                .thenBy { -it.distanceMeters }
        )
        val banner = topAlert?.let {
            val tag = it.member.status.tftTag.ifBlank { "MSG" }
            val who = formatShortName(it.member.name)
            val dist = formatCompactDistance(it.distanceMeters)
            val elapsed = if (it.member.statusChangedAtEpochMs > 0L) {
                nowMs - it.member.statusChangedAtEpochMs
            } else {
                Long.MAX_VALUE
            }
            val expanded = elapsed in 0L until ALERT_EXPANDED_DURATION_MS
            val progress = if (expanded) {
                (1f - elapsed.toFloat() / ALERT_EXPANDED_DURATION_MS.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            TftGroupAlertBanner(
                member = it.member,
                text = "$tag: $who - $dist",
                subtext = formatBannerMessage(it.member.statusMessage),
                status = it.member.status,
                isExpanded = expanded,
                expandedProgress = progress
            )
        }

        return TftGroupOverlayPlan(
            inViewMembers = inView,
            edgeIndicators = resolvedIndicators,
            alertBanner = banner
        )
    }

    private fun separateCloseIndicators(
        items: List<TftEdgeIndicator>,
        minY: Float,
        maxY: Float,
        minGapPx: Float
    ): List<TftEdgeIndicator> {
        if (items.size <= 1) return items
        val result = items.toMutableList()
        for (i in result.indices) {
            for (j in 0 until i) {
                val a = result[j]
                val b = result[i]
                if (abs(a.edgeX - b.edgeX) < minGapPx * 2.2f && abs(a.edgeY - b.edgeY) < minGapPx) {
                    val shiftedY = if (b.edgeY + minGapPx <= maxY) {
                        b.edgeY + minGapPx
                    } else {
                        (b.edgeY - minGapPx).coerceAtLeast(minY)
                    }
                    result[i] = b.copy(edgeY = shiftedY)
                }
            }
        }
        return result
    }
}
