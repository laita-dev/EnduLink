package com.oscar.endulink.tiles

import com.oscar.endulink.bike.AndroidBikeKeysStorage
import java.io.File

actual object PlatformPaths {
    actual fun getTileCacheDir(): String {
        val base = AndroidBikeKeysStorage.appContext?.filesDir
            ?: File(System.getProperty("user.home", ".") + "/.endulink")
        val dir = File(base, "endulink_tiles")
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }

    actual fun getAppDataDir(): String {
        val base = AndroidBikeKeysStorage.appContext?.filesDir
            ?: File(System.getProperty("user.home", ".") + "/.endulink")
        if (!base.exists()) base.mkdirs()
        return base.absolutePath
    }
}

