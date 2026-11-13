package com.oscar.endulink.trail

import com.oscar.endulink.tiles.TileMath
import com.oscar.endulink.ui.findTappedRoute
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    @Test
    fun testFindTappedRouteRotatedHeadingAndPriority() {
        val zoom = 14.0
        val zInt = 14
        val baseTilePx = 256.0
        val center = GeoCoord(40.0, -3.0)
        val viewportW = 1000.0
        val viewportH = 1000.0

        val (cX, cY) = TileMath.latLonToFractionalTile(center.latitude, center.longitude, zInt)
        // Tramo situado ~80 px al ESTE del centro del mapa en coordenadas sin rotar
        val targetGeo = TileMath.fractionalTileToLatLon(cX + 80.0 / baseTilePx, cY, zInt)
        val targetStart = TileMath.fractionalTileToLatLon(cX + 80.0 / baseTilePx, cY - 120.0 / baseTilePx, zInt)
        val targetEnd = TileMath.fractionalTileToLatLon(cX + 80.0 / baseTilePx, cY + 120.0 / baseTilePx, zInt)

        val secondary = GPXRoute(
            id = "sec",
            name = "Secundario",
            fileName = "sec.gpx",
            points = listOf(GPXPoint(targetStart.first, targetStart.second), GPXPoint(targetEnd.first, targetEnd.second)),
            waypoints = emptyList(),
            totalDistanceKm = 1.0,
            elevationGainM = 0.0,
            importedAtEpochMs = 1L,
            color = TrackColor.BLUE,
            isVisible = true
        )
        val active = secondary.copy(id = "act", name = "Activo", color = TrackColor.ORANGE)
        val hidden = GPXRoute(
            id = "hid",
            name = "Oculto",
            fileName = "hid.gpx",
            points = listOf(GPXPoint(center.latitude, center.longitude), GPXPoint(targetGeo.first, targetGeo.second)),
            waypoints = emptyList(),
            totalDistanceKm = 0.5,
            elevationGainM = 0.0,
            importedAtEpochMs = 2L,
            isVisible = false
        )

        // Con heading = 90° (Este apunta ARRIBA en pantalla), el punto al Este cae en (500, 500 - 80) = (500, 420)
        val hitActive = findTappedRoute(
            routes = listOf(hidden, secondary, active),
            activeRouteId = "act",
            tapScreenX = 500.0,
            tapScreenY = 425.0,
            viewportWidthPx = viewportW,
            viewportHeightPx = viewportH,
            center = center,
            zoom = zoom,
            headingDeg = 90.0,
            baseTilePx = baseTilePx,
            hitRadiusPx = 24.0
        )
        assertEquals("act", hitActive?.id)

        // Pulsar en (500, 580) (Sur/Oeste tras rotar 90°) no debe capturar ni al track visible ni al oculto
        val missOpposite = findTappedRoute(
            routes = listOf(hidden, secondary, active),
            activeRouteId = "act",
            tapScreenX = 500.0,
            tapScreenY = 580.0,
            viewportWidthPx = viewportW,
            viewportHeightPx = viewportH,
            center = center,
            zoom = zoom,
            headingDeg = 90.0,
            baseTilePx = baseTilePx,
            hitRadiusPx = 24.0
        )
        assertNull(missOpposite)
    }

    @Test
    fun testGpxRouteNotesColorsAndLegacyJson() {
        val route = GPXRoute(
            id = "r_notes",
            name = "Enduro Trialera",
            fileName = "trialera.gpx",
            points = listOf(
                GPXPoint(39.15, -0.45, 120.0),
                GPXPoint(39.16, -0.44, 240.0)
            ),
            waypoints = emptyList(),
            totalDistanceKm = 1.4,
            elevationGainM = 120.0,
            importedAtEpochMs = 100L,
            color = TrackColor.YELLOW,
            notes = "Subida rota & piedra suelta"
        )

        val xml = route.toGPXXml()
        val parsed = GPXParser.parse(xml, fallbackName = "fallback", color = TrackColor.PURPLE)
        assertEquals("Enduro Trialera", parsed.name)
        assertEquals("Subida rota & piedra suelta", parsed.notes)
        assertEquals(TrackColor.PURPLE, parsed.color)

        val jsonCodec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val encoded = jsonCodec.encodeToString(route)
        val decoded = jsonCodec.decodeFromString<GPXRoute>(encoded)
        assertEquals(TrackColor.YELLOW, decoded.color)
        assertEquals("Subida rota & piedra suelta", decoded.notes)

        // Simular JSON antiguo sin el campo "notes"
        val legacyJson = """
            {"id":"legacy","name":"Antigua","fileName":"old.gpx","points":[{"latitude":40.0,"longitude":-3.0}],"waypoints":[],"totalDistanceKm":0.0,"elevationGainM":0.0,"importedAtEpochMs":1}
        """.trimIndent()
        val decodedLegacy = jsonCodec.decodeFromString<GPXRoute>(legacyJson)
        assertEquals("", decodedLegacy.notes)
        assertEquals(TrackColor.ORANGE, decodedLegacy.color)
        assertTrue(decodedLegacy.isVisible)
    }
}

