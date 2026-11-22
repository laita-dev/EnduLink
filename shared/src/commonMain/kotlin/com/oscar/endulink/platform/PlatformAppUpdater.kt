package com.oscar.endulink.platform

expect object PlatformAppUpdater {
    fun getAppVersionName(): String
    fun isTestChannel(): Boolean
    fun openExternalUrl(url: String)
    suspend fun downloadAndInstallApk(url: String, onProgress: (Float) -> Unit): Boolean
}

