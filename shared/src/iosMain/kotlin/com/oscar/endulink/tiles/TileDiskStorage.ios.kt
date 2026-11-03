package com.oscar.endulink.tiles

import kotlinx.cinterop.*
import platform.Foundation.*

@OptIn(ExperimentalForeignApi::class)
actual object TileDiskStorage {
    actual fun readBytes(path: String): ByteArray? {
        val nsData = NSData.dataWithContentsOfFile(path) ?: return null
        val length = nsData.length.toInt()
        val result = ByteArray(length)
        if (length > 0) {
            result.usePinned { pinned ->
                platform.posix.memcpy(pinned.addressOf(0), nsData.bytes, nsData.length)
            }
        }
        return result
    }

    actual fun writeBytes(path: String, bytes: ByteArray) {
        val lastSlash = path.lastIndexOf('/')
        if (lastSlash > 0) {
            val parentDir = path.substring(0, lastSlash)
            NSFileManager.defaultManager.createDirectoryAtPath(parentDir, withIntermediateDirectories = true, attributes = null, error = null)
        }
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                val nsData = NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
                nsData.writeToFile(path, atomically = true)
            }
        } else {
            NSData().writeToFile(path, atomically = true)
        }
    }

    actual fun fileExists(path: String): Boolean {
        return NSFileManager.defaultManager.fileExistsAtPath(path)
    }

    actual fun fileSize(path: String): Long {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return 0L
        val sizeNum = attrs[NSFileSize] as? NSNumber ?: return 0L
        return sizeNum.longLongValue
    }

    actual fun deleteDirectory(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
}

