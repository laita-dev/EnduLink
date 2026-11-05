package com.oscar.endulink.platform

import com.oscar.endulink.bike.BikePicture
import com.oscar.endulink.bike.PictureRequest

/**
 * En iOS, la codificación por hardware se delega a la capa nativa de Swift
 * (VideoToolbox / H264Encoder.swift) con BikeMapCanvas nativo, manteniendo el framework
 * KMP ligero, modular y 100% estable en tiempo de compilación.
 */
actual class PlatformH264Encoder actual constructor(
    private val customBitrateKbps: () -> Int,
    private val renderFrameRgba: (width: Int, height: Int) -> ByteArray?,
    private val onStats: ((EncoderStats) -> Unit)?,
    private val log: (String) -> Unit
) : BikePicture {

    actual override fun start(request: PictureRequest): Boolean {
        log("H264Encoder iOS: Inicializado (modo KMP / nativo Swift)")
        return true
    }

    actual override fun next(waitMs: Int): ByteArray? = null

    actual override fun fresh() {}

    actual override fun stop() {}
}
