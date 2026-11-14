package com.oscar.endulink.platform

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import com.oscar.endulink.bike.BikePicture
import com.oscar.endulink.bike.PictureRequest
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

actual class PlatformH264Encoder actual constructor(
    private val customBitrateKbps: () -> Int,
    private val renderFrameRgba: (width: Int, height: Int) -> ByteArray?,
    private val onStats: ((EncoderStats) -> Unit)?,
    private val log: (String) -> Unit
) : BikePicture {

    private val codecLock = Any()
    private var codec: MediaCodec? = null
    @kotlin.concurrent.Volatile
    private var isRunning = false
    private val chunkQueue = LinkedBlockingQueue<ByteArray>(MAX_BACKLOG)
    private var encoderJob: Job? = null
    private var spsPps: ByteArray? = null
    private var bytesEncodedWindow = 0L
    private var framesEncodedWindow = 0
    private var windowStartMs = 0L

    companion object {
        private const val MIME_TYPE = "video/avc"
        private const val MAX_BACKLOG = 15
        private const val FRAME_INTERVAL_MS = 66L // ~15 FPS
    }

    actual override fun start(request: PictureRequest): Boolean {
        stop()
        val width = request.encodedWidth
        val height = request.encodedHeight
        val fps = if (request.fps in 5..30) request.fps else 15
        val customBit = customBitrateKbps()
        val bitrate = if (customBit > 0) customBit * 1000 else if (request.bitRate > 500_000) request.bitRate else 2_500_000

        return try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                try {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
                } catch (_: Exception) {}
            }

            val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            codec = encoder
            isRunning = true
            chunkQueue.clear()

            encoderJob = CoroutineScope(Dispatchers.Default).launch {
                runEncodingLoop(width, height, fps)
            }
            log("MediaCodec H.264 iniciado: ${width}x${height} a $fps FPS, ${bitrate / 1000} kbps")
            true
        } catch (e: Exception) {
            log("Fallo al iniciar MediaCodec H.264: ${e.message}")
            stop()
            false
        }
    }

    actual override fun fresh() {
        chunkQueue.clear()
        forceKeyFrame()
    }

    actual override fun next(waitMs: Int): ByteArray? {
        return try {
            chunkQueue.poll(waitMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            null
        }
    }

    actual override fun stop() {
        isRunning = false
        encoderJob?.cancel()
        encoderJob = null
        synchronized(codecLock) {
            val active = codec
            codec = null
            if (active != null) {
                try {
                    active.stop()
                } catch (_: Exception) {}
                try {
                    active.release()
                } catch (_: Exception) {}
            }
        }
        chunkQueue.clear()
    }

    private fun forceKeyFrame() {
        synchronized(codecLock) {
            if (!isRunning) return
            try {
                val params = Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
                codec?.setParameters(params)
            } catch (_: Exception) {}
        }
    }

    private suspend fun runEncodingLoop(width: Int, height: Int, fps: Int) {
        var frameIndex = 0L
        val yuvBuffer = ByteArray(width * height * 3 / 2)
        val bufferInfo = MediaCodec.BufferInfo()
        val frameIntervalMs = (1000L / fps.coerceAtLeast(5)).coerceAtLeast(16L)
        windowStartMs = SystemClock.elapsedRealtime()
        var nextTargetMs = windowStartMs
        bytesEncodedWindow = 0L
        framesEncodedWindow = 0

        try {
            while (isRunning && currentCoroutineContext().isActive) {
                val rgba = try {
                    renderFrameRgba(width, height)
                } catch (_: Exception) {
                    null
                }

                if (!isRunning) break

                if (rgba != null && rgba.size >= width * height * 4) {
                    rgbaToYuv420SemiPlanar(rgba, yuvBuffer, width, height)
                    feedInput(yuvBuffer, frameIndex * 1_000_000L / fps)
                    frameIndex++
                }

                drainOutput(bufferInfo, initialWaitUs = 6_000L)

                val now = SystemClock.elapsedRealtime()
                val windowElapsed = now - windowStartMs
                if (windowElapsed >= 1000L) {
                    val actualFps = (framesEncodedWindow * 1000.0) / windowElapsed.toDouble()
                    val actualKbps = ((bytesEncodedWindow * 8L) / windowElapsed).toInt()
                    onStats?.invoke(EncoderStats(fps = actualFps, bitrateKbps = actualKbps))
                    windowStartMs = now
                    bytesEncodedWindow = 0L
                    framesEncodedWindow = 0
                }

                nextTargetMs += frameIntervalMs
                if (now > nextTargetMs + frameIntervalMs) {
                    nextTargetMs = now + frameIntervalMs
                }
                val sleepTime = nextTargetMs - now
                if (sleepTime > 0) {
                    delay(sleepTime)
                }
            }
        } catch (_: CancellationException) {
            // Cierre normal de la corrutina
        } catch (e: Exception) {
            if (isRunning) {
                log("Error en bucle H.264: ${e.message}")
            }
        }
    }

    private fun feedInput(yuv: ByteArray, ptsUs: Long) {
        synchronized(codecLock) {
            if (!isRunning) return
            val encoder = codec ?: return
            try {
                val inIndex = encoder.dequeueInputBuffer(10_000L)
                if (inIndex >= 0) {
                    val buffer: ByteBuffer = encoder.getInputBuffer(inIndex) ?: return
                    buffer.clear()
                    buffer.put(yuv)
                    encoder.queueInputBuffer(inIndex, 0, yuv.size, ptsUs, 0)
                }
            } catch (_: Exception) {}
        }
    }

    private fun drainOutput(bufferInfo: MediaCodec.BufferInfo, initialWaitUs: Long = 0L) {
        synchronized(codecLock) {
            if (!isRunning) return
            val encoder = codec ?: return
            var waitUs = initialWaitUs
            try {
                while (isRunning) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, waitUs)
                    waitUs = 0L
                    if (outIndex >= 0) {
                        val outBuffer = encoder.getOutputBuffer(outIndex)
                        if (outBuffer == null) {
                            encoder.releaseOutputBuffer(outIndex, false)
                            continue
                        }
                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outBuffer.get(chunk)

                        if (isConfig) {
                            spsPps = chunk
                        } else {
                            val finalPayload = if (isKey && spsPps != null) {
                                val combined = ByteArray(spsPps!!.size + chunk.size)
                                spsPps!!.copyInto(combined, 0)
                                chunk.copyInto(combined, spsPps!!.size)
                                combined
                            } else {
                                chunk
                            }

                            if (chunkQueue.size >= MAX_BACKLOG) {
                                chunkQueue.poll() // Descartar frame viejo
                                try {
                                    val params = Bundle().apply {
                                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                                    }
                                    encoder.setParameters(params)
                                } catch (_: Exception) {}
                            }
                            chunkQueue.offer(finalPayload)
                            bytesEncodedWindow += finalPayload.size
                            framesEncodedWindow++
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                    } else {
                        break
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun rgbaToYuv420SemiPlanar(rgba: ByteArray, yuv: ByteArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var rgbaIndex = 0

        for (j in 0 until height) {
            if ((j and 1) == 0) {
                var i = 0
                while (i + 1 < width) {
                    val r0 = rgba[rgbaIndex].toInt() and 0xFF
                    val g0 = rgba[rgbaIndex + 1].toInt() and 0xFF
                    val b0 = rgba[rgbaIndex + 2].toInt() and 0xFF
                    val y0 = (66 * r0 + 129 * g0 + 25 * b0 + 128 shr 8) + 16
                    yuv[yIndex++] = (if (y0 > 255) 255 else if (y0 < 0) 0 else y0).toByte()

                    val u = (-38 * r0 - 74 * g0 + 112 * b0 + 128 shr 8) + 128
                    val v = (112 * r0 - 94 * g0 - 18 * b0 + 128 shr 8) + 128
                    yuv[uvIndex++] = (if (u > 255) 255 else if (u < 0) 0 else u).toByte()
                    yuv[uvIndex++] = (if (v > 255) 255 else if (v < 0) 0 else v).toByte()

                    val r1 = rgba[rgbaIndex + 4].toInt() and 0xFF
                    val g1 = rgba[rgbaIndex + 5].toInt() and 0xFF
                    val b1 = rgba[rgbaIndex + 6].toInt() and 0xFF
                    val y1 = (66 * r1 + 129 * g1 + 25 * b1 + 128 shr 8) + 16
                    yuv[yIndex++] = (if (y1 > 255) 255 else if (y1 < 0) 0 else y1).toByte()

                    rgbaIndex += 8
                    i += 2
                }
                if (i < width) {
                    val r0 = rgba[rgbaIndex].toInt() and 0xFF
                    val g0 = rgba[rgbaIndex + 1].toInt() and 0xFF
                    val b0 = rgba[rgbaIndex + 2].toInt() and 0xFF
                    val y0 = (66 * r0 + 129 * g0 + 25 * b0 + 128 shr 8) + 16
                    yuv[yIndex++] = (if (y0 > 255) 255 else if (y0 < 0) 0 else y0).toByte()

                    val u = (-38 * r0 - 74 * g0 + 112 * b0 + 128 shr 8) + 128
                    val v = (112 * r0 - 94 * g0 - 18 * b0 + 128 shr 8) + 128
                    yuv[uvIndex++] = (if (u > 255) 255 else if (u < 0) 0 else u).toByte()
                    yuv[uvIndex++] = (if (v > 255) 255 else if (v < 0) 0 else v).toByte()
                    rgbaIndex += 4
                }
            } else {
                for (i in 0 until width) {
                    val r = rgba[rgbaIndex].toInt() and 0xFF
                    val g = rgba[rgbaIndex + 1].toInt() and 0xFF
                    val b = rgba[rgbaIndex + 2].toInt() and 0xFF
                    rgbaIndex += 4

                    val y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                    yuv[yIndex++] = (if (y > 255) 255 else if (y < 0) 0 else y).toByte()
                }
            }
        }
    }
}

