package com.oscar.endulink.trail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoadRouterTest {

    @Test
    fun testGenerateLoopWaypointsScalesWithDistanceAndProfile() {
        val origin = GeoCoord(40.4168, -3.7038)
        val wps60 = RoadRouter.generateLoopWaypoints(
            origin = origin,
            targetDistanceKm = 60.0,
            bearingDeg = 0.0,
            windingFactor = RouteTerrainProfile.TRAIL_EASY.windingFactor
        )
        val wps120 = RoadRouter.generateLoopWaypoints(
            origin = origin,
            targetDistanceKm = 120.0,
            bearingDeg = 0.0,
            windingFactor = RouteTerrainProfile.TRAIL_EASY.windingFactor
        )

        assertEquals(3, wps60.size)
        assertEquals(3, wps120.size)
        // Rumbo Norte (0°): los vértices deben quedar al norte del origen
        assertTrue(wps60[1].latitude > origin.latitude)
        val apex60 = origin.distanceTo(wps60[1])
        val apex120 = origin.distanceTo(wps120[1])
        assertTrue(apex120 > apex60 * 1.9 && apex120 < apex60 * 2.1)

        // Perfil Enduro tiene mayor sinuosidad (windingFactor mayor -> radio euclídeo menor)
        val wpsEnduro = RoadRouter.generateLoopWaypoints(
            origin = origin,
            targetDistanceKm = 60.0,
            bearingDeg = 90.0,
            windingFactor = RouteTerrainProfile.ENDURO_HARD.windingFactor
        )
        val wpsRoad = RoadRouter.generateLoopWaypoints(
            origin = origin,
            targetDistanceKm = 60.0,
            bearingDeg = 90.0,
            windingFactor = RouteTerrainProfile.ROAD_FAST.windingFactor
        )
        assertTrue(origin.distanceTo(wpsEnduro[1]) < origin.distanceTo(wpsRoad[1]))
    }

    @Test
    fun testParseBRouterGeoJsonWithElevationAndStats() {
        val sampleGeoJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "properties": {
                    "track-length": "4250",
                    "filtered ascend": "185",
                    "total-time": "420"
                  },
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [
                      [-4.0000, 40.7000, 1100.0],
                      [-4.0000, 40.7100, 1150.0],
                      [-3.9900, 40.7100, 1210.0],
                      [-3.9800, 40.7200, 1285.0]
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val route = RoadRouter.parseBRouterGeoJson(
            body = sampleGeoJson,
            destination = GeoCoord(40.7200, -3.9800),
            destinationName = "Puerto Trail",
            profile = RouteTerrainProfile.OFFROAD_MEDIUM,
            routeLabel = "Off-Road"
        )

        assertNotNull(route)
        assertEquals(RouteTerrainProfile.OFFROAD_MEDIUM, route.profile)
        assertEquals(TrackColor.YELLOW, route.asGPXRoute.color)
        assertEquals(4, route.coordinates.size)
        assertEquals(4.25, route.distanceKm, 0.01)
        assertEquals(7, route.expectedMinutes)
        assertEquals(185.0, route.elevationGainM, 0.1)
        assertEquals(1100.0, route.asGPXRoute.points.first().elevation)
        assertEquals(1285.0, route.asGPXRoute.points.last().elevation)
    }
}
