package com.oscar.endulink.platform

import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual object PlatformAppUpdater {

    actual fun getAppVersionName(): String {
        val info = NSBundle.mainBundle.infoDictionary
        return info?.get("CFBundleShortVersionString") as? String ?: "0.1.0"
    }

    actual fun openExternalUrl(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(nsUrl)
    }

    actual suspend fun downloadAndInstallApk(url: String, onProgress: (Float) -> Unit): Boolean {
        openExternalUrl(url)
        return true
    }
}

