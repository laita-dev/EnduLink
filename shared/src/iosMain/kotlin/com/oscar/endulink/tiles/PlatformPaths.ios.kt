package com.oscar.endulink.tiles

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*

@OptIn(ExperimentalForeignApi::class)
actual object PlatformPaths {
    actual fun getTileCacheDir(): String {
        val paths = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        val cacheBase = paths.firstOrNull() as? String ?: (NSHomeDirectory() + "/Documents")
        val full = "$cacheBase/endulink_tiles"
        NSFileManager.defaultManager.createDirectoryAtPath(full, withIntermediateDirectories = true, attributes = null, error = null)
        return full
    }

    actual fun getAppDataDir(): String {
        val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        val docBase = paths.firstOrNull() as? String ?: (NSHomeDirectory() + "/Documents")
        return docBase
    }
}

