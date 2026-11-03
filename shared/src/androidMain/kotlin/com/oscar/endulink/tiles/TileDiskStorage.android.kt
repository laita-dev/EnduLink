package com.oscar.endulink.tiles

import java.io.File

actual object TileDiskStorage {
    actual fun readBytes(path: String): ByteArray? {
        val file = File(path)
        return if (file.exists() && file.isFile) {
            try {
                file.readBytes()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    actual fun writeBytes(path: String, bytes: ByteArray) {
        try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        } catch (_: Exception) {}
    }

    actual fun fileExists(path: String): Boolean {
        val file = File(path)
        return file.exists() && file.isFile
    }

    actual fun fileSize(path: String): Long {
        val file = File(path)
        return if (file.exists()) file.length() else 0L
    }

    actual fun deleteDirectory(path: String) {
        try {
            val dir = File(path)
            dir.deleteRecursively()
        } catch (_: Exception) {}
    }
}

