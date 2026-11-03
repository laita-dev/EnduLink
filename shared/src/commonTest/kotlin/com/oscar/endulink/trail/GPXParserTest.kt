package com.oscar.endulink.trail

import kotlin.test.*

class GPXParserTest {

    private val sampleGpx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="Test">
          <metadata>
            <name>Ruta Enduro Guadarrama</name>
          </metadata>
          <trk>
            <name>Ruta Enduro Guadarrama</name>
            <trkseg>
              <trkpt lat="40.7000" lon="-4.0000">
                <ele>1200.0</ele>
              </trkpt>
              <trkpt lat="40.7100" lon="-4.0000">
                <ele>1220.0</ele>
              </trkpt>
              <trkpt lat="40.7200" lon="-4.0000">
                <ele>1250.0</ele>
              </trkpt>
            </trkseg>
          </trk>
          <wpt lat="40.7150" lon="-4.0000">
            <name>Cancela Paso Vaca</name>
            <sym>gate</sym>
          </wpt>
        </gpx>
    """.trimIndent()

    @Test
    fun testParseGpxRoute() {
        val route = GPXParser.parse(sampleGpx, fallbackName = "ruta_test.gpx")

        assertEquals("Ruta Enduro Guadarrama", route.name)
        assertEquals(3, route.points.size)
        assertEquals(1, route.waypoints.size)
        assertEquals("Cancela Paso Vaca", route.waypoints[0].name)
        assertEquals("gate", route.waypoints[0].symbol)

        assertTrue(route.totalDistanceKm > 2.0)
        assertEquals(50.0, route.elevationGainM)
    }

    @Test
    fun testReverseRoute() {
        val route = GPXParser.parse(sampleGpx, fallbackName = "ruta.gpx")
        val reversed = route.reversedRoute()

        assertEquals(3, reversed.points.size)
        assertEquals(40.7200, reversed.points[0].latitude)
        assertEquals(40.7000, reversed.points[2].latitude)
        assertTrue(reversed.isInverted)
    }

    @Test
    fun testStripInvSuffix() {
        assertEquals("Ruta Trail", GPXParser.stripInvSuffix("Ruta Trail (Inv)"))
        assertEquals("Ruta Trail", GPXParser.stripInvSuffix("Ruta Trail (Inv) (Inv)"))
        assertEquals("Ruta Normal", GPXParser.stripInvSuffix("Ruta Normal"))
    }
}

