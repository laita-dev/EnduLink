package com.oscar.endulink.tiles

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*

@OptIn(ExperimentalForeignApi::class)
actual object PlatformPaths {
    actual fun getTileCacheDir(): String {
        val fm = NSFileManager.defaultManager
        val appSupportPaths = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        val appSupportBase = appSupportPaths.firstOrNull() as? String ?: (NSHomeDirectory() + "/Library/Application Support")
        fm.createDirectoryAtPath(appSupportBase, withIntermediateDirectories = true, attributes = null, error = null)

        val full = "$appSupportBase/endulink_tiles"
        if (!fm.fileExistsAtPath(full)) {
            val cachePaths = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
            val cacheBase = cachePaths.firstOrNull() as? String
            val legacyDir = cacheBase?.let { "$it/endulink_tiles" }
            if (legacyDir != null && fm.fileExistsAtPath(legacyDir)) {
                fm.moveItemAtPath(legacyDir, toPath = full, error = null)
            }
        }
        if (!fm.fileExistsAtPath(full)) {
            fm.createDirectoryAtPath(full, withIntermediateDirectories = true, attributes = null, error = null)
        }
        NSURL.fileURLWithPath(full).setResourceValue(
            NSNumber.numberWithBool(true),
            forKey = NSURLIsExcludedFromBackupKey,
            error = null
        )
        return full
    }

    actual fun getAppDataDir(): String {
        val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        val docBase = paths.firstOrNull() as? String ?: (NSHomeDirectory() + "/Documents")
        return docBase
    }
}

