package com.oscar.endulink.platform

import com.oscar.endulink.bike.BikePicture
import com.oscar.endulink.bike.PictureRequest
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.*
import kotlinx.datetime.Clock

/**
 * Puente nativo hacia VideoToolbox (hardware H.264 de Apple en iOS).
 * Se registra al arrancar `iOSApp.swift`.
 * Usa tipos primitivos (`Int`, `Long`, `Boolean`, `String?`) y puntero en memoria fijada (zero-copy)
 * para máxima velocidad y compatibilidad 100% garantizada con Swift.
 */
interface IosH264HardwareBridge {
    fun startSession(width: Int, height: Int, fps: Int, bitrateBps: Int): Boolean
    fun encodeNv12Ptr(nv12Ptr: Long, width: Int, height: Int, ptsMs: Long, forceKeyFrame: Boolean): String?
    fun stopSession()
}

object IosH264BridgeHolder {
    @Volatile
    var bridge: IosH264HardwareBridge? = null
}

@OptIn(ExperimentalForeignApi::class)
actual class PlatformH264Encoder actual constructor(
    private val customBitrateKbps: () -> Int,
    private val renderFrameRgba: (width: Int, height: Int) -> ByteArray?,
    private val onStats: ((EncoderStats) -> Unit)?,
    private val log: (String) -> Unit
) : BikePicture {

    @Volatile
    private var isRunning = false

    @Volatile
    private var forceNextKeyFrame = true

    private val queueLock = SynchronizedLock()
    private val chunkQueue = ArrayDeque<ByteArray>()
    private var encoderJob: Job? = null

    companion object {
        private const val MAX_BACKLOG = 15
    }

    actual override fun start(request: PictureRequest): Boolean {
        stop()
        val width = request.encodedWidth
        val height = request.encodedHeight
        val fps = if (request.fps in 5..30) request.fps else 15
        val customBit = customBitrateKbps()
        val bitrate = if (customBit > 0) customBit * 1000 else if (request.bitRate > 500_000) request.bitRate else 2_500_000

        val hwBridge = IosH264BridgeHolder.bridge
        if (hwBridge == null) {
            log("Error H264Encoder iOS: Puente VideoToolbox no registrado")
            return false
        }

        val ok = hwBridge.startSession(width, height, fps, bitrate)
        if (!ok) {
            log("Fallo al iniciar VideoToolbox H.264 en iOS (${width}x${height})")
            return false
        }

        isRunning = true
        forceNextKeyFrame = true
        queueLock.withLock { chunkQueue.clear() }

        encoderJob = CoroutineScope(Dispatchers.Default).launch {
            runEncodingLoop(hwBridge, width, height, fps)
        }
        log("VideoToolbox H.264 iniciado en iOS: ${width}x${height} a $fps FPS, ${bitrate / 1000} kbps")
        return true
    }

    actual override fun fresh() {
        queueLock.withLock { chunkQueue.clear() }
        forceNextKeyFrame = true
    }

    actual override fun next(waitMs: Int): ByteArray? {
        val deadline = Clock.System.now().toEpochMilliseconds() + waitMs.coerceAtLeast(5)
        while (isRunning) {
            val item = queueLock.withLock {
                if (chunkQueue.isNotEmpty()) chunkQueue.removeFirst() else null
            }
            if (item != null) return item
            if (Clock.System.now().toEpochMilliseconds() >= deadline) break
            platformSleep(8)
        }
        return null
    }

    actual override fun stop() {
        isRunning = false
        encoderJob?.cancel()
        encoderJob = null
        try {
            IosH264BridgeHolder.bridge?.stopSession()
        } catch (_: Exception) {}
        queueLock.withLock { chunkQueue.clear() }
    }

    private suspend fun runEncodingLoop(
        hwBridge: IosH264HardwareBridge,
        width: Int,
        height: Int,
        fps: Int
    ) {
        val nv12Buffer = ByteArray(width * height * 3 / 2)
        val frameIntervalMs = (1000L / fps.coerceAtLeast(5)).coerceAtLeast(16L)
        var frameIndex = 0L
        var windowStartMs = Clock.System.now().toEpochMilliseconds()
        var bytesEncodedWindow = 0L
        var framesEncodedWindow = 0

        while (isRunning && currentCoroutineContext().isActive) {
            val startTime = Clock.System.now().toEpochMilliseconds()
            val rgba = renderFrameRgba(width, height)

            if (rgba != null && rgba.size >= width * height * 4) {
                rgbaToYuv420SemiPlanar(rgba, nv12Buffer, width, height)
                val ptsMs = frameIndex * 1000L / fps
                val wantKey = forceNextKeyFrame || (frameIndex % (fps * 2L) == 0L)
                forceNextKeyFrame = false

                val encodedB64 = nv12Buffer.usePinned { pinned ->
                    val ptrAddr = pinned.addressOf(0).toLong()
                    hwBridge.encodeNv12Ptr(ptrAddr, width, height, ptsMs, wantKey)
                }

                if (!encodedB64.isNullOrEmpty()) {
                    val chunk = decodeBase64Fast(encodedB64)
                    if (chunk.isNotEmpty()) {
                        queueLock.withLock {
                            if (chunkQueue.size >= MAX_BACKLOG) {
                                chunkQueue.removeFirst()
                                forceNextKeyFrame = true
                            }
                            chunkQueue.addLast(chunk)
                        }
                        bytesEncodedWindow += chunk.size
                        framesEncodedWindow++
                    }
                }
                frameIndex++
            }

            val now = Clock.System.now().toEpochMilliseconds()
            val windowElapsed = now - windowStartMs
            if (windowElapsed >= 1000L) {
                val actualFps = (framesEncodedWindow * 1000.0) / windowElapsed.toDouble()
                val actualKbps = ((bytesEncodedWindow * 8L) / windowElapsed).toInt()
                onStats?.invoke(EncoderStats(fps = actualFps, bitrateKbps = actualKbps))
                windowStartMs = now
                bytesEncodedWindow = 0L
                framesEncodedWindow = 0
            }

            val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
            val sleepTime = frameIntervalMs - elapsed
            if (sleepTime > 0) {
                delay(sleepTime)
            }
        }
    }

    private fun rgbaToYuv420SemiPlanar(rgba: ByteArray, yuv: ByteArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var rgbaIndex = 0

        for (j in 0 until height) {
            for (i in 0 until width) {
                val r = rgba[rgbaIndex].toInt() and 0xFF
                val g = rgba[rgbaIndex + 1].toInt() and 0xFF
                val b = rgba[rgbaIndex + 2].toInt() and 0xFF
                rgbaIndex += 4

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                    yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
    }

    private fun decodeBase64Fast(input: String): ByteArray {
        val len = input.length
        if (len == 0) return ByteArray(0)
        var pad = 0
        if (len >= 1 && input[len - 1] == '=') pad++
        if (len >= 2 && input[len - 2] == '=') pad++
        val outLen = (len * 3) / 4 - pad
        if (outLen <= 0) return ByteArray(0)
        val out = ByteArray(outLen)
        var outIdx = 0
        var i = 0
        while (i + 3 < len) {
            val b0 = b64Val(input[i])
            val b1 = b64Val(input[i + 1])
            val b2 = b64Val(input[i + 2])
            val b3 = b64Val(input[i + 3])
            i += 4
            val triplet = (b0 shl 18) or (b1 shl 12) or (b2 shl 6) or b3
            if (outIdx < outLen) out[outIdx++] = ((triplet shr 16) and 0xFF).toByte()
            if (outIdx < outLen) out[outIdx++] = ((triplet shr 8) and 0xFF).toByte()
            if (outIdx < outLen) out[outIdx++] = (triplet and 0xFF).toByte()
        }
        return out
    }

    private fun b64Val(c: Char): Int = when (c) {
        in 'A'..'Z' -> c.code - 'A'.code
        in 'a'..'z' -> c.code - 'a'.code + 26
        in '0'..'9' -> c.code - '0'.code + 52
        '+' -> 62
        '/' -> 63
        else -> 0
    }
}
