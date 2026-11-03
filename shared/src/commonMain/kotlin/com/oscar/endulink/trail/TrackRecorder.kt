package com.oscar.endulink.trail

import com.oscar.endulink.platform.SynchronizedLock
import com.oscar.endulink.platform.withLock
import kotlin.concurrent.Volatile
import kotlinx.datetime.Clock
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

    var totalDistanceKm: Double = 0.0
        private set

    val recordedPoints: List<RecordedPoint>
        get() = lock.withLock { points.toList() }

    val pointsCount: Int
        get() = lock.withLock { points.size }

    val elevationGainM: Double
        get() = lock.withLock {
            var gain = 0.0
            for (i in 1 until points.size) {
                val prev = points[i - 1].elevation ?: continue
                val curr = points[i].elevation ?: continue
                if (curr > prev) gain += (curr - prev)
            }
            gain
        }

    val durationSeconds: Long
        get() = if (startTimestampEpochMs == 0L) 0L else {
            (Clock.System.now().toEpochMilliseconds() - startTimestampEpochMs) / 1000L
        }

    fun reset() {
        lock.withLock {
            points.clear()
            isRecording = false
            isPaused = false
            totalDistanceKm = 0.0
            startTimestampEpochMs = 0L
        }
    }

    fun start() {
        lock.withLock {
            points.clear()
            isRecording = true
            isPaused = false
            startTimestampEpochMs = Clock.System.now().toEpochMilliseconds()
            totalDistanceKm = 0.0
        }
    }

    fun pause() {
        isPaused = true
    }

    fun resume() {
        isPaused = false
    }

    fun stop(): List<RecordedPoint> {
        return lock.withLock {
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

    fun toGPXString(trackName: String = "Track Grabado EnduLink"): String {
        val pts = lock.withLock { points.toList() }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"EnduLink KMP\">\n")
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(trackName).append("</name>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>").append(trackName).append("</name>\n")
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

