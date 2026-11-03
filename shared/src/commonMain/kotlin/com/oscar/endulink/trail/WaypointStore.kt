package com.oscar.endulink.trail

import com.oscar.endulink.tiles.PlatformPaths
import com.oscar.endulink.tiles.TileDiskStorage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object WaypointStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun getFilePath(): String {
        return PlatformPaths.getAppDataDir() + "/user_waypoints.json"
    }

    fun loadWaypoints(): List<GPXWaypoint> {
        return try {
            val path = getFilePath()
            val bytes = TileDiskStorage.readBytes(path) ?: return emptyList()
            val text = bytes.decodeToString()
            json.decodeFromString<List<GPXWaypoint>>(text)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveWaypoints(waypoints: List<GPXWaypoint>) {
        try {
            val path = getFilePath()
            val text = json.encodeToString(waypoints)
            TileDiskStorage.writeBytes(path, text.encodeToByteArray())
        } catch (_: Exception) {}
    }
}

