package com.oscar.endulink.platform

import com.oscar.endulink.bike.AndroidBikeKeysStorage

actual object BackgroundServiceController {
    @kotlin.concurrent.Volatile
    private var isServiceRunning = false

    actual fun startKeepAlive() {
        if (isServiceRunning) return
        val ctx = AndroidBikeKeysStorage.appContext ?: return
        try {
            BikeForegroundService.start(ctx)
            isServiceRunning = true
        } catch (_: Exception) {}
    }

    actual fun stopKeepAlive() {
        if (!isServiceRunning) return
        val ctx = AndroidBikeKeysStorage.appContext ?: return
        try {
            BikeForegroundService.stop(ctx)
        } catch (_: Exception) {}
        isServiceRunning = false
    }
}

