package com.oscar.endulink.platform

actual fun showPlatformToast(message: String) {
    PlatformToastCenter.show(message)
}

actual fun closeApp() {}

