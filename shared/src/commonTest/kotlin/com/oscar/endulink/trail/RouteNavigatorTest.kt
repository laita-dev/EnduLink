package com.oscar.endulink.trail

import kotlin.math.abs
import kotlin.test.*

class RouteNavigatorTest {

    @Test
    fun testCompassBearing() {
        // De sur a norte = 0º / 360º
        val p1 = GeoCoord(40.0, 0.0)
        val p2 = GeoCoord(41.0, 0.0)
        val bNorth = RouteNavigator.compassBearing(p1, p2)
        assertTrue(abs(bNorth - 0.0) < 0.1 || abs(bNorth - 360.0) < 0.1)

        // De oeste a este = 90º
        val p3 = GeoCoord(40.0, 1.0)
        val bEast = RouteNavigator.compassBearing(p1, p3)
        assertTrue(abs(bEast - 90.0) < 1.0)
    }

    @Test
    fun testDMD2OrthogonalProjection() {
        val pts = mutableListOf(
            GPXPoint(40.000, 0.000),
            GPXPoint(40.010, 0.000),
            GPXPoint(40.020, 0.000)
        )
        val stats = GPXParser.computeStats(pts)
        val route = GPXRoute(
            id = "test_1",
            name = "Test Route",
            fileName = "test.gpx",
            points = pts,
            waypoints = emptyList(),
            totalDistanceKm = stats.first,
            elevationGainM = 0.0,
            importedAtEpochMs = 0L
        )

        // Posición del piloto ligeramente al este del segmento (desviado ~100m)
        val rider = GeoCoord(40.005, 0.001)
        val progress = RouteNavigator.calculateProgress(route, rider)

        assertEquals(0, progress.nearestIndex)
        assertTrue(progress.offTrackMeters > 50.0)
        assertTrue(progress.remainingKm > 1.0)

        // Proyección sobre la línea (lon debería ser prácticamente 0.0)
        assertTrue(abs(progress.projectedCoordinate.longitude) < 0.0001)

        // El punto de reconexión debe estar adelantado sobre la traza en sentido de avance (latitud > 40.005)
        assertTrue(progress.reconnectCoordinate.latitude > 40.005)
    }

    @Test
    fun testCrowFliesDirectHeading() {
        val from = GeoCoord(40.0, -3.0)
        val to = GeoCoord(40.0, -2.0)
        val crow = RouteNavigator.computeCrowFlies(from, to, "Pueblo")

        assertEquals("Pueblo", crow.targetName)
        assertTrue(crow.distanceKm > 70.0 && crow.distanceKm < 100.0)
        assertTrue(abs(crow.bearingDeg - 90.0) < 2.0)
    }
}

