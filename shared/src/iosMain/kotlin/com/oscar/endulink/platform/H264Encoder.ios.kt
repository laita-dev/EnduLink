package com.oscar.endulink.platform

import com.oscar.endulink.bike.BikePicture
import com.oscar.endulink.bike.PictureRequest
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.CoreFoundation.*
import platform.CoreMedia.*
import platform.CoreVideo.*
import platform.VideoToolbox.*
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.darwin.DISPATCH_TIME_NOW

@OptIn(ExperimentalForeignApi::class)
actual class PlatformH264Encoder actual constructor(
    private val customBitrateKbps: () -> Int,
    private val renderFrameRgba: (width: Int, height: Int) -> ByteArray?,
    private val onStats: ((EncoderStats) -> Unit)?,
    private val log: (String) -> Unit
) : BikePicture {

    private var session: VTCompressionSessionRef? = null
    private var pixelBufferPool: CVPixelBufferPoolRef? = null
    private var isRunning = false
    private var workerJob: Job? = null

    private val queueLock = Any()
    private val chunks = mutableListOf<ByteArray>()
    private val queueSem = dispatch_semaphore_create(0)

    private var forceKeyframe = true
    private var spsBytes: ByteArray? = null
    private var ppsBytes: ByteArray? = null

    companion object {
        private const val MAX_BACKLOG = 15
        private const val FRAME_INTERVAL_MS = 66L // ~15 FPS
        private val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }

    override fun start(request: PictureRequest): Boolean {
        stop()
        val width = request.encodedWidth
        val height = request.encodedHeight
        val fps = if (request.fps in 5..30) request.fps else 15
        val bitrate = if (request.bitRate > 500_000) request.bitRate else 2_500_000

        return try {
            memScoped {
                val sessionVar = alloc<VTCompressionSessionRefVar>()
                val callbackRecord = alloc<VTCompressionOutputCallbackRecord>()

                // Stable pointer to this instance
                val stableRef = StableRef.create(this@PlatformH264Encoder)
                callbackRecord.outputCallback = staticCFunction { outputCallbackRefCon, _, status, flags, sampleBuffer ->
                    if (status == 0 && sampleBuffer != null && outputCallbackRefCon != null) {
                        val ref = outputCallbackRefCon.asStableRef<PlatformH264Encoder>().get()
                        ref.handleSampleBuffer(sampleBuffer, flags)
                    }
                }
                callbackRecord.outputRefCon = stableRef.asCPointer()

                val createStatus = VTCompressionSessionCreate(
                    allocator = null,
                    width = width,
                    height = height,
                    codecType = kCMVideoCodecType_H264,
                    encoderSpecification = null,
                    imageBufferAttributes = null,
                    compressedDataAllocator = null,
                    outputCallback = callbackRecord.outputCallback,
                    outputCallbackRefCon = callbackRecord.outputRefCon,
                    compressionSessionOut = sessionVar.ptr
                )

                if (createStatus != 0 || sessionVar.value == null) {
                    log("VTCompressionSessionCreate falló con código $createStatus")
                    return false
                }

                session = sessionVar.value

                // Propiedades H.264 Baseline, RealTime y Bitrate
                VTSessionSetProperty(session, kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_H264_Baseline_3_1)
                VTSessionSetProperty(session, kVTCompressionPropertyKey_RealTime, kCFBooleanTrue)
                VTSessionSetProperty(session, kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse)

                val fpsNum = alloc<IntVar>().apply { value = fps }
                val fpsVal = CFNumberCreate(null, kCFNumberIntType, fpsNum.ptr)
                VTSessionSetProperty(session, kVTCompressionPropertyKey_ExpectedFrameRate, fpsVal)

                val brNum = alloc<IntVar>().apply { value = bitrate }
                val brVal = CFNumberCreate(null, kCFNumberIntType, brNum.ptr)
                VTSessionSetProperty(session, kVTCompressionPropertyKey_AverageBitRate, brVal)

                val poolVar = alloc<CVPixelBufferPoolRefVar>()
                val poolStatus = CVPixelBufferPoolCreate(null, null, null, poolVar.ptr)
                if (poolStatus == 0) {
                    pixelBufferPool = poolVar.value
                }

                VTCompressionSessionPrepareToEncodeFrames(session)
            }

            isRunning = true
            forceKeyframe = true
            synchronized(queueLock) { chunks.clear() }

            workerJob = CoroutineScope(Dispatchers.Default).launch {
                runLoop(width, height)
            }
            log("VideoToolbox H.264 iniciado: ${width}x${height} a $fps FPS")
            true
        } catch (e: Exception) {
            log("Error iniciando VideoToolbox: ${e.message}")
            stop()
            false
        }
    }

    override fun fresh() {
        synchronized(queueLock) { chunks.clear() }
        forceKeyframe = true
    }

    override fun next(waitMs: Int): ByteArray? {
        val timeout = dispatch_time(DISPATCH_TIME_NOW, (waitMs * 1_000_000L))
        val res = dispatch_semaphore_wait(queueSem, timeout)
        if (res == 0L) {
            synchronized(queueLock) {
                return if (chunks.isNotEmpty()) chunks.removeAt(0) else null
            }
        }
        return null
    }

    override fun stop() {
        isRunning = false
        workerJob?.cancel()
        workerJob = null

        session?.let {
            VTCompressionSessionInvalidate(it)
            CFRelease(it)
        }
        session = null

        pixelBufferPool?.let {
            CFRelease(it)
        }
        pixelBufferPool = null

        synchronized(queueLock) { chunks.clear() }
    }

    private suspend fun runLoop(width: Int, height: Int) {
        var frameIndex = 0L
        while (isRunning && isActive) {
            val startMs = platform.posix.clock()
            val rgba = renderFrameRgba(width, height)
            if (rgba != null && rgba.size >= width * height * 4) {
                encodeFrame(rgba, width, height, frameIndex++)
            }
            val elapsedMs = (platform.posix.clock() - startMs) * 1000 / platform.posix.CLOCKS_PER_SEC
            val wait = FRAME_INTERVAL_MS - elapsedMs
            if (wait > 0) delay(wait)
        }
    }

    private fun encodeFrame(rgba: ByteArray, width: Int, height: Int, frameIndex: Long) {
        val sess = session ?: return
        memScoped {
            val pixelBufferVar = alloc<CVPixelBufferRefVar>()
            val attrs = alloc<CFDictionaryRefVar>()

            val status = CVPixelBufferCreate(
                null,
                width.toULong(),
                height.toULong(),
                kCVPixelFormatType_32RGBA,
                attrs.value,
                pixelBufferVar.ptr
            )

            if (status != 0 || pixelBufferVar.value == null) return
            val px = pixelBufferVar.value!!

            CVPixelBufferLockBaseAddress(px, 0u)
            val baseAddress = CVPixelBufferGetBaseAddress(px)
            val bytesPerRow = CVPixelBufferGetBytesPerRow(px).toInt()

            if (baseAddress != null) {
                rgba.usePinned { pinned ->
                    val src = pinned.addressOf(0)
                    if (bytesPerRow == width * 4) {
                        platform.posix.memcpy(baseAddress, src, (width * height * 4).toULong())
                    } else {
                        for (row in 0 until height) {
                            val rowSrc = src + (row * width * 4)
                            val rowDst = baseAddress + (row * bytesPerRow)
                            platform.posix.memcpy(rowDst, rowSrc, (width * 4).toULong())
                        }
                    }
                }
            }
            CVPixelBufferUnlockBaseAddress(px, 0u)

            val pts = CMTimeMake(frameIndex, 15)
            val duration = CMTimeMake(1, 15)

            val frameProps: CFDictionaryRef? = if (forceKeyframe) {
                forceKeyframe = false
                val key = kVTEncodeFrameOptionKey_ForceKeyFrame
                val value = kCFBooleanTrue
                val kPtr = allocArray<COpaquePointerVar>(1).apply { this[0] = key }
                val vPtr = allocArray<COpaquePointerVar>(1).apply { this[0] = value }
                CFDictionaryCreate(null, kPtr, vPtr, 1, null, null)
            } else {
                null
            }

            VTCompressionSessionEncodeFrame(
                sess,
                px,
                pts,
                duration,
                frameProps,
                null,
                null
            )

            CVPixelBufferRelease(px)
        }
    }

    private fun handleSampleBuffer(sampleBuffer: CMSampleBufferRef, flags: VTEncodeInfoFlags) {
        val attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, false)
        val notSync = attachments?.let {
            val dict = CFArrayGetValueAtIndex(it, 0) as? CFDictionaryRef
            CFDictionaryContainsKey(dict, kCMSampleAttachmentKey_NotSync)
        } ?: false
        val isKeyFrame = !notSync

        val formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer)
        if (isKeyFrame && formatDesc != null) {
            extractSpsPps(formatDesc)
        }

        val dataBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) ?: return
        val totalLength = CMBlockBufferGetDataLength(dataBuffer).toInt()
        val avccData = ByteArray(totalLength)

        avccData.usePinned { pinned ->
            CMBlockBufferCopyDataBytes(dataBuffer, 0u, totalLength.toULong(), pinned.addressOf(0))
        }

        // Conversión AVCC (longitud de 4 bytes) a Annex-B (00 00 00 01)
        val annexB = avccToAnnexB(avccData, isKeyFrame)

        synchronized(queueLock) {
            if (chunks.size >= MAX_BACKLOG) {
                chunks.removeAt(0)
                forceKeyframe = true
            }
            chunks.add(annexB)
            dispatch_semaphore_signal(queueSem)
        }
    }

    private fun extractSpsPps(format: CMFormatDescriptionRef) {
        memScoped {
            val countVar = alloc<size_tVar>()
            val status = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                format,
                0u,
                null,
                null,
                countVar.ptr,
                null
            )
            if (status != 0) return

            val spsPtr = alloc<CPointerVar<ByteVar>>()
            val spsLen = alloc<size_tVar>()
            if (CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, 0u, spsPtr.ptr.reinterpret(), spsLen.ptr, null, null) == 0) {
                spsBytes = ByteArray(spsLen.value.toInt()).apply {
                    usePinned { platform.posix.memcpy(it.addressOf(0), spsPtr.value, spsLen.value.toULong()) }
                }
            }

            val ppsPtr = alloc<CPointerVar<ByteVar>>()
            val ppsLen = alloc<size_tVar>()
            if (CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, 1u, ppsPtr.ptr.reinterpret(), ppsLen.ptr, null, null) == 0) {
                ppsBytes = ByteArray(ppsLen.value.toInt()).apply {
                    usePinned { platform.posix.memcpy(it.addressOf(0), ppsPtr.value, ppsLen.value.toULong()) }
                }
            }
        }
    }

    private fun avccToAnnexB(avcc: ByteArray, isKey: Boolean): ByteArray {
        val out = ArrayList<Byte>()
        if (isKey) {
            spsBytes?.let {
                out.addAll(START_CODE.toList())
                out.addAll(it.toList())
            }
            ppsBytes?.let {
                out.addAll(START_CODE.toList())
                out.addAll(it.toList())
            }
        }

        var offset = 0
        while (offset + 4 <= avcc.size) {
            val b0 = avcc[offset].toInt() and 0xFF
            val b1 = avcc[offset + 1].toInt() and 0xFF
            val b2 = avcc[offset + 2].toInt() and 0xFF
            val b3 = avcc[offset + 3].toInt() and 0xFF
            val naluLen = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            offset += 4

            if (offset + naluLen <= avcc.size) {
                out.addAll(START_CODE.toList())
                for (k in offset until (offset + naluLen)) {
                    out.add(avcc[k])
                }
                offset += naluLen
            } else {
                break
            }
        }
        return out.toByteArray()
    }
}

