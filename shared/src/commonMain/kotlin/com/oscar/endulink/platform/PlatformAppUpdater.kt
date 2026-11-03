package com.oscar.endulink.platform

expect object PlatformAppUpdater {
    fun getAppVersionName(): String
    fun openExternalUrl(url: String)
    suspend fun downloadAndInstallApk(url: String, onProgress: (Float) -> Unit): Boolean
}

