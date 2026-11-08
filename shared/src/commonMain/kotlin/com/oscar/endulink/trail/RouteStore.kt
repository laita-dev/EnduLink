package com.oscar.endulink.trail

import com.oscar.endulink.tiles.PlatformPaths
import com.oscar.endulink.tiles.TileDiskStorage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Almacenamiento persistente en disco para la biblioteca de tracks GPX importados y grabados con REC.
 */
object RouteStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun getFilePath(): String {
        return PlatformPaths.getAppDataDir() + "/user_routes.json"
    }

    fun loadRoutes(): List<GPXRoute> {
        return try {
            val path = getFilePath()
            val bytes = TileDiskStorage.readBytes(path) ?: return emptyList()
            val text = bytes.decodeToString()
            json.decodeFromString<List<GPXRoute>>(text)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveRoutes(routes: List<GPXRoute>) {
        try {
            val path = getFilePath()
            val text = json.encodeToString(routes)
            TileDiskStorage.writeBytes(path, text.encodeToByteArray())
        } catch (_: Exception) {}
    }
}

