package com.oscar.endulink.bike

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import kotlin.concurrent.Volatile

interface IosQrScannerCallback {
    fun onQrScanned(rawText: String)
    fun onManualFallback()
    fun onError(message: String)
}

interface IosWifiConnectCallback {
    fun onWifiConnectResult(success: Boolean, message: String)
}

interface IosQrScannerBridge {
    fun startQrScan(
        title: String,
        hint: String,
        manualButtonLabel: String,
        callback: IosQrScannerCallback
    )
    fun openSettings()
    fun connectToWifi(ssid: String, password: String, callback: IosWifiConnectCallback)
}

object IosQrScannerBridgeHolder {
    @Volatile
    var bridge: IosQrScannerBridge? = null
}

@Composable
actual fun rememberBikeQrScanner(
    onQrScanned: (String) -> Unit,
    onManualInputRequested: () -> Unit,
    onScannerError: (String) -> Unit,
    title: String,
    hint: String,
    manualButtonLabel: String
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
            bridge.startQrScan(
                title = title,
                hint = hint,
                manualButtonLabel = manualButtonLabel,
                callback = object : IosQrScannerCallback {
                    override fun onQrScanned(rawText: String) {
                        currentScanned.value(rawText)
                    }

                    override fun onManualFallback() {
                        currentManual.value()
                    }

                    override fun onError(message: String) {
                        currentError.value(message)
                    }
                }
            )
        }
    }
}

actual fun openPlatformWifiSettings() {
    IosQrScannerBridgeHolder.bridge?.openSettings()
}

actual fun connectToBikeWifi(
    ssid: String,
    password: String,
    onResult: (success: Boolean, message: String) -> Unit
) {
    val cleanSsid = ssid.trim()
    if (cleanSsid.isEmpty()) {
        onResult(false, "SSID de la moto vacío")
        return
    }
    val bridge = IosQrScannerBridgeHolder.bridge
    if (bridge == null) {
        onResult(false, "Conector Wi-Fi iOS no inicializado. Únete manualmente a $cleanSsid en Ajustes > Wi-Fi.")
        return
    }
    bridge.connectToWifi(cleanSsid, password, object : IosWifiConnectCallback {
        override fun onWifiConnectResult(success: Boolean, message: String) {
            onResult(success, message)
        }
    })
}
