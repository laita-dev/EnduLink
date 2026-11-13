package com.oscar.endulink.bike

import androidx.compose.runtime.Composable

/**
 * Lanza el escáner nativo de códigos QR usando la cámara del dispositivo (Android CameraX+MLKit / iOS AVFoundation).
 */
@Composable
expect fun rememberBikeQrScanner(
    onQrScanned: (String) -> Unit,
    onManualInputRequested: () -> Unit,
    onScannerError: (String) -> Unit
): () -> Unit

/**
 * Abre los ajustes Wi-Fi (o de la app en iOS) para conectarse rápidamente tras escanear el QR.
 */
expect fun openPlatformWifiSettings()

