package com.oscar.endulink.group

import com.oscar.endulink.trail.GPXParser
import com.oscar.endulink.trail.GPXPoint
import com.oscar.endulink.trail.GPXRoute
import com.oscar.endulink.trail.GPXWaypoint
import com.oscar.endulink.trail.GeoCoord
import com.oscar.endulink.trail.TrackColor
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

@Serializable
enum class RiderColor(val raw: String, val argb: Long, val label: String) {
    CYAN("cyan", 0xFF00E5FFL, "Cian"),
    LIME("lime", 0xFF00E676L, "Verde"),
    MAGENTA("magenta", 0xFFFF2E93L, "Magenta"),
    YELLOW("yellow", 0xFFFFD600L, "Amarillo"),
    PURPLE("purple", 0xFFB388FFL, "Morado"),
    CORAL("coral", 0xFFFF6E40L, "Coral"),
    SKY("sky", 0xFF40C4FFL, "Azul"),
    PINK("pink", 0xFFFF80ABL, "Rosa");

    companion object {
        fun fromRaw(raw: String?): RiderColor {
            if (raw.isNullOrBlank()) return CYAN
            return entries.firstOrNull { it.raw.equals(raw.trim(), ignoreCase = true) } ?: CYAN
        }
    }
}

@Serializable
enum class RiderStatus(val raw: String, val shortLabel: String, val tftTag: String) {
    RIDING("riding", "En ruta", ""),
    STOPPED_WAIT("wait", "Esperadme", "ESPERA"),
    MECHANICAL("mech", "Avería", "AVERIA"),
    SOS("sos", "SOS · Ayuda", "SOS"),
    LEFT("left", "Salió", "");

    val isAlert: Boolean
        get() = this == SOS || this == MECHANICAL || this == STOPPED_WAIT

    val alertPriority: Int
        get() = when (this) {
            SOS -> 3
            MECHANICAL -> 2
            STOPPED_WAIT -> 1
            else -> 0
        }

    companion object {
        fun fromRaw(raw: String?): RiderStatus {
            if (raw.isNullOrBlank()) return RIDING
            return entries.firstOrNull { it.raw.equals(raw.trim(), ignoreCase = true) } ?: RIDING
        }
    }
}

enum class GroupTransportSource(val label: String) {
    ONLINE_MQTT("4G/5G"),
    OFFLINE_LAN("Wi-Fi Local")
}

enum class GroupConnectionState(val label: String) {
    DISCONNECTED("Desconectado"),
    CONNECTING("Conectando..."),
    ONLINE("Online 4G/5G"),
    OFFLINE_LAN_ONLY("Wi-Fi Local"),
    HYBRID("4G/5G + Wi-Fi Local"),
    RECONNECTING("Reconectando...")
}

data class GroupMember(
    val id: String,
    val name: String,
    val color: RiderColor,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double = 0.0,
    val speedKmh: Double = 0.0,
    val headingDegrees: Double = 0.0,
    val batteryPct: Int = -1,
    val status: RiderStatus = RiderStatus.RIDING,
    val statusMessage: String = "",
    val seq: Long = 0L,
    val sentAtEpochMs: Long = 0L,
    val receivedAtEpochMs: Long = 0L,
    val source: GroupTransportSource = GroupTransportSource.ONLINE_MQTT
) {
    val coordinate: GeoCoord
        get() = GeoCoord(latitude, longitude)

    fun ageSeconds(nowMs: Long = Clock.System.now().toEpochMilliseconds()): Long {
        return ((nowMs - receivedAtEpochMs).coerceAtLeast(0L)) / 1000L
    }

    fun isStale(nowMs: Long = Clock.System.now().toEpochMilliseconds()): Boolean {
        return ageSeconds(nowMs) > 60L
    }

    fun initials(): String {
        val clean = buildString {
            for (ch in name.trim().uppercase()) {
                val mapped = when (ch) {
                    'Á', 'À', 'Ä' -> 'A'
                    'É', 'È', 'Ë' -> 'E'
                    'Í', 'Ì', 'Ï' -> 'I'
                    'Ó', 'Ò', 'Ö' -> 'O'
                    'Ú', 'Ù', 'Ü' -> 'U'
                    'Ñ' -> 'N'
                    in 'A'..'Z', in '0'..'9' -> ch
                    else -> null
                }
                if (mapped != null) append(mapped)
                if (length >= 3) break
            }
        }
        return clean.ifEmpty { "RDR" }
    }
}

data class SharedGroupTrack(
    val id: String,
    val name: String,
    val senderId: String,
    val senderName: String,
    val sentAtEpochMs: Long,
    val distanceKm: Double,
    val pointCount: Int,
    val gpxXml: String
)

@Serializable
data class GroupConfig(
    val memberId: String = "",
    val nickname: String = "",
    val riderColorRaw: String = RiderColor.CYAN.raw,
    val batterySaverMode: Boolean = false,
    val enableOnlineSync: Boolean = true,
    val enableOfflineLanSync: Boolean = true,
    val customBrokerUrl: String = "",
    val lastRoomCode: String? = null
) {
    val riderColor: RiderColor
        get() = RiderColor.fromRaw(riderColorRaw)
}

object GroupInvite {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val SCHEME_PREFIX = "endulink://group/"

    fun generateRoomCode(length: Int = 6): String {
        val len = length.coerceIn(4, 12)
        return buildString(len) {
            repeat(len) {
                append(ALPHABET[Random.nextInt(ALPHABET.length)])
            }
        }
    }

    fun formatForDisplay(code: String): String {
        val clean = normalizeRawToken(code)
        return if (clean.length == 6) {
            "${clean.take(3)}-${clean.drop(3)}"
        } else {
            clean
        }
    }

    fun buildInviteUri(code: String): String {
        val clean = normalizeRawToken(code)
        return "$SCHEME_PREFIX$clean"
    }

    fun buildShareMessage(code: String, inviterName: String): String {
        val formatted = formatForDisplay(code)
        val uri = buildInviteUri(code)
        val who = inviterName.trim().ifBlank { "Un compañero" }
        return "$who te invita a su grupo de ruta en EnduLink.\nCódigo: $formatted\nEnlace directo: $uri"
    }

    fun parseRoomCode(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val extracted = if (trimmed.contains("://")) {
            val lower = trimmed.lowercase()
            if (!lower.startsWith(SCHEME_PREFIX)) {
                return null
            }
            trimmed.substring(SCHEME_PREFIX.length)
                .substringBefore('?')
                .substringBefore('#')
                .trim('/')
        } else {
            if (trimmed.contains(':')) return null
            trimmed
        }

        val clean = normalizeRawToken(extracted)
        if (clean.length !in 4..12) return null
        if (!clean.all { it in 'A'..'Z' || it in '0'..'9' }) return null
        return clean
    }

    /**
     * Versión estricta para el escáner QR por cámara para no confundir QR de moto (`HJ-...`, `http://...`, `WIFI:...`)
     * con códigos de sala de grupo.
     */
    fun parseQrInvite(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        if (lower.startsWith(SCHEME_PREFIX)) {
            return parseRoomCode(trimmed)
        }
        if (lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("wifi:") ||
            lower.startsWith("hj-")
        ) {
            return null
        }
        val clean = normalizeRawToken(trimmed)
        return if (clean.length == 6 && clean.all { it in 'A'..'Z' || it in '0'..'9' }) clean else null
    }

    private fun normalizeRawToken(token: String): String {
        return token.trim()
            .replace("-", "")
            .replace(" ", "")
            .replace("_", "")
            .uppercase()
    }
}

@Serializable
internal data class WirePeerGossip(
    val id: String,
    val n: String,
    val c: String,
    val la: Double,
    val lo: Double,
    val al: Int = 0,
    val sp: Int = 0,
    val hd: Int = 0,
    val bt: Int = -1,
    val st: String = "riding",
    val sm: String = "",
    val sq: Long = 0L,
    val ts: Long = 0L
)

@Serializable
internal data class WirePositionPayload(
    val v: Int = 1,
    val id: String,
    val n: String,
    val c: String,
    val la: Double,
    val lo: Double,
    val al: Double = 0.0,
    val sp: Double = 0.0,
    val hd: Double = 0.0,
    val bt: Int = -1,
    val st: String = "riding",
    val sm: String = "",
    val sq: Long = 0L,
    val ts: Long = 0L,
    val gpxId: String? = null,
    val gpxPort: Int? = null,
    val peers: List<WirePeerGossip> = emptyList()
)

@Serializable
internal data class WireSharedWaypoint(
    val n: String,
    val la: Int,
    val lo: Int,
    val el: Int? = null,
    val sy: String? = null
)

@Serializable
internal data class WireSharedTrackPayload(
    val v: Int = 1,
    val id: String,
    val n: String,
    val sid: String,
    val sn: String,
    val ts: Long,
    val clr: String = TrackColor.ORANGE.id,
    val latE5: List<Int>,
    val lonE5: List<Int>,
    val eleM: List<Int> = emptyList(),
    val wpts: List<WireSharedWaypoint> = emptyList()
)

internal object CompactGroupTrackCodec {
    private const val MAX_SHARED_POINTS = 3000

    fun encodeRoute(
        route: GPXRoute,
        senderId: String,
        senderName: String,
        sentAtEpochMs: Long = Clock.System.now().toEpochMilliseconds()
    ): WireSharedTrackPayload? {
        val rawPoints = route.points
        if (rawPoints.size < 2) return null

        val sampled = if (rawPoints.size <= MAX_SHARED_POINTS) {
            rawPoints
        } else {
            val out = ArrayList<GPXPoint>(MAX_SHARED_POINTS)
            val lastIdx = rawPoints.size - 1
            for (k in 0 until MAX_SHARED_POINTS - 1) {
                val idx = ((k.toLong() * lastIdx) / (MAX_SHARED_POINTS - 1)).toInt()
                out.add(rawPoints[idx])
            }
            out.add(rawPoints[lastIdx])
            out
        }

        val latDeltas = ArrayList<Int>(sampled.size)
        val lonDeltas = ArrayList<Int>(sampled.size)
        val hasElevation = sampled.any { it.elevation != null }
        val eleDeltas: MutableList<Int>? = if (hasElevation) ArrayList(sampled.size) else null

        var prevLat = 0
        var prevLon = 0
        var prevEle = sampled.firstOrNull { it.elevation != null }?.elevation?.roundToInt() ?: 0

        for (i in sampled.indices) {
            val pt = sampled[i]
            val curLat = (pt.latitude * 100_000.0).roundToInt()
            val curLon = (pt.longitude * 100_000.0).roundToInt()
            if (i == 0) {
                latDeltas.add(curLat)
                lonDeltas.add(curLon)
                if (eleDeltas != null) {
                    val curEle = pt.elevation?.roundToInt() ?: prevEle
                    eleDeltas.add(curEle)
                    prevEle = curEle
                }
            } else {
                latDeltas.add(curLat - prevLat)
                lonDeltas.add(curLon - prevLon)
                if (eleDeltas != null) {
                    val curEle = pt.elevation?.roundToInt() ?: prevEle
                    eleDeltas.add(curEle - prevEle)
                    prevEle = curEle
                }
            }
            prevLat = curLat
            prevLon = curLon
        }

        val wpts = route.waypoints.take(64).map { w ->
            WireSharedWaypoint(
                n = w.name.take(40),
                la = (w.latitude * 100_000.0).roundToInt(),
                lo = (w.longitude * 100_000.0).roundToInt(),
                el = w.elevation?.roundToInt(),
                sy = w.symbol
            )
        }

        val trackId = "${sentAtEpochMs}_${abs(route.name.hashCode())}"
        return WireSharedTrackPayload(
            id = trackId,
            n = route.name.take(80),
            sid = senderId,
            sn = senderName.take(32),
            ts = sentAtEpochMs,
            clr = route.color.id,
            latE5 = latDeltas,
            lonE5 = lonDeltas,
            eleM = eleDeltas ?: emptyList(),
            wpts = wpts
        )
    }

    fun toSharedGroupTrack(payload: WireSharedTrackPayload): SharedGroupTrack? {
        val count = minOf(payload.latE5.size, payload.lonE5.size)
        if (count < 2) return null

        val points = ArrayList<GPXPoint>(count)
        val hasElevation = payload.eleM.size == count
        var accLat = 0
        var accLon = 0
        var accEle = 0

        for (i in 0 until count) {
            if (i == 0) {
                accLat = payload.latE5[0]
                accLon = payload.lonE5[0]
                if (hasElevation) accEle = payload.eleM[0]
            } else {
                accLat += payload.latE5[i]
                accLon += payload.lonE5[i]
                if (hasElevation) accEle += payload.eleM[i]
            }
            points.add(
                GPXPoint(
                    latitude = accLat / 100_000.0,
                    longitude = accLon / 100_000.0,
                    elevation = if (hasElevation) accEle.toDouble() else null
                )
            )
        }

        val stats = GPXParser.computeStats(points)
        val waypoints = payload.wpts.mapIndexed { index, w ->
            GPXWaypoint(
                id = "gwpt_${payload.id}_$index",
                name = w.n.ifBlank { "Punto ${index + 1}" },
                latitude = w.la / 100_000.0,
                longitude = w.lo / 100_000.0,
                elevation = w.el?.toDouble(),
                symbol = w.sy,
                createdAtEpochMs = payload.ts
            )
        }

        val route = GPXRoute(
            id = "grp_${payload.id}",
            name = payload.n.ifBlank { "Track de ${payload.sn}" },
            fileName = "${payload.n.ifBlank { "grupo_track" }}.gpx",
            points = points,
            waypoints = waypoints,
            totalDistanceKm = stats.first,
            elevationGainM = stats.second,
            importedAtEpochMs = payload.ts,
            isVisible = true,
            color = TrackColor.fromId(payload.clr),
            notes = "Compartido en grupo por ${payload.sn}"
        )

        return SharedGroupTrack(
            id = payload.id,
            name = route.name,
            senderId = payload.sid,
            senderName = payload.sn.ifBlank { "Compañero" },
            sentAtEpochMs = payload.ts,
            distanceKm = stats.first,
            pointCount = points.size,
            gpxXml = route.toGPXXml()
        )
    }
}
