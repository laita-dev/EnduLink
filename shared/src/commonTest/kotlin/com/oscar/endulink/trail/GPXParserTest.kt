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

    @Test
    fun testDottedFallbackFilenameAndInvalidXmlRejection() {
        val namelessGpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <trk><trkseg>
                <trkpt lat="40.7000" lon="-4.0000" />
                <trkpt lat="40.7100" lon="-4.0000" />
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        val route = GPXParser.parse(namelessGpx, fallbackName = "Sierra.v2.gpx")
        assertEquals("Sierra.v2", route.name)
        assertEquals("Sierra.v2.gpx", route.fileName)

        assertFailsWith<Exception> {
            GPXParser.parse("<config><item>not a gpx</item></config>", fallbackName = "config.xml")
        }
    }

    @Test
    fun testParseMassiveTetStyleGpxWithoutBacktrackingOrOverflow() {
        val sb = StringBuilder(2_200_000)
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="EnduLink" xmlns="http://www.topografix.com/GPX/1/1">""")
        sb.appendLine("""  <metadata><name>TET Portugal</name><desc>Biker owned &amp; tested</desc></metadata>""")
        for (w in 0 until 220) {
            val lat = 37.2 + w * 0.02
            val lon = -8.6 + (w % 15) * 0.05
            sb.appendLine("""  <wpt lat="$lat" lon="$lon"><name>FUEL_Station_$w</name><sym>Gas Station</sym></wpt>""")
        }
        sb.appendLine("  <trk>")
        sb.appendLine("    <name>TET Portugal</name>")
        sb.appendLine("    <trkseg>")
        for (i in 0 until 40_000) {
            val lat = 37.1 + i * 0.00012
            val lon = -8.6 + (i % 200) * 0.00005
            sb.appendLine("""      <trkpt lat="$lat" lon="$lon"/>""")
        }
        sb.appendLine("    </trkseg>")
        sb.appendLine("  </trk>")
        sb.appendLine("</gpx>")

        val route = GPXParser.parse(sb.toString(), fallbackName = "TET_Portugal.gpx")

        assertEquals("TET Portugal", route.name)
        assertEquals("Biker owned & tested", route.notes)
        assertEquals(220, route.waypoints.size)
        assertEquals("FUEL_Station_0", route.waypoints.first().name)
        assertEquals(35_000, route.points.size)
        assertTrue(route.totalDistanceKm > 500.0)
        // Comprobar preservación de extremos tras downsampling
        assertEquals(37.1, route.points.first().latitude, 1e-6)
        assertEquals(37.1 + 39_999 * 0.00012, route.points.last().latitude, 1e-6)
    }
}

