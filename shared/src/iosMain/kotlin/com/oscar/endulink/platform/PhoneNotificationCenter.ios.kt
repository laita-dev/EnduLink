package com.oscar.endulink.platform

import com.oscar.endulink.bike.IosQrScannerBridgeHolder

actual fun isPhoneNotificationAccessGranted(): Boolean = true

actual fun openPhoneNotificationAccessSettings() {
    IosQrScannerBridgeHolder.bridge?.openSettings()
}

actual fun supportsSystemWideNotificationCapture(): Boolean = false
