package com.oscar.endulink.bike

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import kotlin.concurrent.Volatile

interface IosQrScannerCallback {
    fun onQrScanned(rawText: String)
    fun onManualFallback()
    fun onError(message: String)
}

interface IosQrScannerBridge {
    fun startQrScan(callback: IosQrScannerCallback)
    fun openSettings()
}

object IosQrScannerBridgeHolder {
    @Volatile
    var bridge: IosQrScannerBridge? = null
}

@Composable
actual fun rememberBikeQrScanner(
    onQrScanned: (String) -> Unit,
    onManualInputRequested: () -> Unit,
    onScannerError: (String) -> Unit
): () -> Unit {
    val currentScanned = rememberUpdatedState(onQrScanned)
    val currentManual = rememberUpdatedState(onManualInputRequested)
    val currentError = rememberUpdatedState(onScannerError)

    return {
        val bridge = IosQrScannerBridgeHolder.bridge
        if (bridge == null) {
            currentError.value("Escáner de cámara iOS no inicializado. Introduce el código manualmente.")
            currentManual.value()
        } else {
            bridge.startQrScan(object : IosQrScannerCallback {
                override fun onQrScanned(rawText: String) {
                    currentScanned.value(rawText)
                }

                override fun onManualFallback() {
                    currentManual.value()
                }

                override fun onError(message: String) {
                    currentError.value(message)
                }
            })
        }
    }
}

actual fun openPlatformWifiSettings() {
    IosQrScannerBridgeHolder.bridge?.openSettings()
}
