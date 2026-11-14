package com.oscar.endulink.bike

import androidx.compose.runtime.Composable

/**
 * Lanza el escáner nativo de códigos QR usando la cámara del dispositivo (Android CameraX+ZXing / iOS AVFoundation).
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

/**
 * Conecta automáticamente al punto de acceso Wi-Fi de la moto usando el SSID y contraseña del QR.
 */
expect fun connectToBikeWifi(
    ssid: String,
    password: String,
    onResult: (success: Boolean, message: String) -> Unit
)

