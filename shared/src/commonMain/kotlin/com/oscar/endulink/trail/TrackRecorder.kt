package com.oscar.endulink.trail

import com.oscar.endulink.platform.SynchronizedLock
import com.oscar.endulink.platform.withLock
import kotlin.concurrent.Volatile
import kotlinx.datetime.Clock
import kotlin.math.max
import kotlin.math.roundToInt

data class RecordedPoint(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double?,
    val speedKmh: Double,
    val timestampEpochMs: Long
) {
    val coordinate: GeoCoord get() = GeoCoord(latitude, longitude)
}

class TrackRecorder {

    private val lock = SynchronizedLock()
    private val points = mutableListOf<RecordedPoint>()

    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    var isPaused: Boolean = false
        private set

    var startTimestampEpochMs: Long = 0L
        private set

    private var stopTimestampEpochMs: Long = 0L
    private var pauseStartEpochMs: Long = 0L
    private var pausedAccumulatedMs: Long = 0L

    var totalDistanceKm: Double = 0.0
        private set

    val recordedPoints: List<RecordedPoint>
        get() = lock.withLock { points.toList() }

    val pointsCount: Int
        get() = lock.withLock { points.size }

    val elevationGainM: Double
        get() = lock.withLock {
            var gain = 0.0
            var lastEle: Double? = points.firstOrNull()?.elevation
            for (i in 1 until points.size) {
                val e = points[i].elevation ?: continue
                val le = lastEle
                if (le != null && e > le + 1.5) {
                    gain += (e - le)
                    lastEle = e
                } else if (le == null || e < le - 1.5) {
                    lastEle = e
                }
            }
            gain
        }

    val durationSeconds: Long
        get() = lock.withLock {
            if (startTimestampEpochMs == 0L) return@withLock 0L
            val now = Clock.System.now().toEpochMilliseconds()
            val endRef = when {
                !isRecording && stopTimestampEpochMs > 0L -> stopTimestampEpochMs
                isPaused && pauseStartEpochMs > 0L -> pauseStartEpochMs
                else -> now
            }
            max(0L, (endRef - startTimestampEpochMs - pausedAccumulatedMs) / 1000L)
        }

    fun reset() {
        lock.withLock {
            points.clear()
            isRecording = false
            isPaused = false
            totalDistanceKm = 0.0
            startTimestampEpochMs = 0L
            stopTimestampEpochMs = 0L
            pauseStartEpochMs = 0L
            pausedAccumulatedMs = 0L
        }
    }

    fun start() {
        lock.withLock {
            points.clear()
            isRecording = true
            isPaused = false
            startTimestampEpochMs = Clock.System.now().toEpochMilliseconds()
            stopTimestampEpochMs = 0L
            pauseStartEpochMs = 0L
            pausedAccumulatedMs = 0L
            totalDistanceKm = 0.0
        }
    }

    fun pause() {
        lock.withLock {
            if (isRecording && !isPaused) {
                isPaused = true
                pauseStartEpochMs = Clock.System.now().toEpochMilliseconds()
            }
        }
    }

    fun resume() {
        lock.withLock {
            if (isRecording && isPaused) {
                if (pauseStartEpochMs > 0L) {
                    pausedAccumulatedMs += max(0L, Clock.System.now().toEpochMilliseconds() - pauseStartEpochMs)
                }
                pauseStartEpochMs = 0L
                isPaused = false
            }
        }
    }

    fun stop(): List<RecordedPoint> {
        return lock.withLock {
            if (isPaused && pauseStartEpochMs > 0L) {
                pausedAccumulatedMs += max(0L, Clock.System.now().toEpochMilliseconds() - pauseStartEpochMs)
                pauseStartEpochMs = 0L
            }
            stopTimestampEpochMs = Clock.System.now().toEpochMilliseconds()
            isRecording = false
            isPaused = false
            points.toList()
        }
    }

    /**
     * Añade un punto GPS con filtro de parada (< 1.5 m de desplazamiento con velocidad baja).
     */
    fun addPoint(latitude: Double, longitude: Double, elevation: Double?, speedKmh: Double): Boolean {
        if (!isRecording || isPaused) return false
        val newCoord = GeoCoord(latitude, longitude)
        val now = Clock.System.now().toEpochMilliseconds()

        return lock.withLock {
            if (points.isNotEmpty()) {
                val last = points.last()
                val distM = last.coordinate.distanceTo(newCoord)
                // Filtrar puntos erráticos en parado
                if (distM < 1.5 && speedKmh < 1.2) {
                    return@withLock false
                }
                totalDistanceKm += (distM / 1000.0)
            }
            points.add(
                RecordedPoint(
                    latitude = latitude,
                    longitude = longitude,
                    elevation = elevation,
                    speedKmh = speedKmh,
                    timestampEpochMs = now
                )
            )
            true
        }
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun toGPXString(trackName: String = "Track Grabado EnduLink"): String {
        val pts = lock.withLock { points.toList() }
        val safeName = escapeXml(trackName)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"EnduLink KMP\">\n")
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(safeName).append("</name>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(safeName).append("</name>\n")
        sb.append("    <trkseg>\n")

        for (p in pts) {
            sb.append("      <trkpt lat=\"").append(p.latitude).append("\" lon=\"").append(p.longitude).append("\">\n")
            if (p.elevation != null) {
                sb.append("        <ele>").append((p.elevation * 10.0).roundToInt() / 10.0).append("</ele>\n")
            }
            sb.append("      </trkpt>\n")
        }

        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }
}
