package com.oscar.endulink.tiles

expect object TileDiskStorage {
    fun readBytes(path: String): ByteArray?
    fun writeBytes(path: String, bytes: ByteArray)
    fun fileExists(path: String): Boolean
    fun fileSize(path: String): Long
    fun directorySizeBytes(path: String): Long
    fun deleteDirectory(path: String)
}

