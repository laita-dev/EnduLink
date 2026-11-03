package com.oscar.endulink.platform

import com.oscar.endulink.bike.BikePicture
import com.oscar.endulink.bike.PictureRequest

/**
 * Interfaz nativa de codificación de vídeo H.264 por hardware a 15 FPS para la pantalla TFT de la moto.
 * - Android: Acelerado por MediaCodec (OMX/C2).
 * - iOS: Acelerado por VideoToolbox (VTCompressionSession).
 */
expect class PlatformH264Encoder(
    customBitrateKbps: () -> Int = { 0 },
    renderFrameRgba: (width: Int, height: Int) -> ByteArray?,
    onStats: ((EncoderStats) -> Unit)? = null,
    log: (String) -> Unit = {}
) : BikePicture
