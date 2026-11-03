package com.oscar.endulink.platform

import com.oscar.endulink.bike.AndroidBikeKeysStorage

actual object BackgroundServiceController {
    actual fun startKeepAlive() {
        val ctx = AndroidBikeKeysStorage.appContext ?: return
        BikeForegroundService.start(ctx)
    }

    actual fun stopKeepAlive() {
        val ctx = AndroidBikeKeysStorage.appContext ?: return
        BikeForegroundService.stop(ctx)
    }
}

