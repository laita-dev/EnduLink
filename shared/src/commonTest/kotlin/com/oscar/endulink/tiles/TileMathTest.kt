package com.oscar.endulink.tiles

import kotlin.math.abs
import kotlin.test.*

class TileMathTest {

    @Test
    fun testWebMercatorConversion() {
        // Coordenadas conocidas: Madrid (40.4168, -3.7038) en Zoom 15
        val (tx, ty) = TileMath.latLonToTile(40.4168, -3.7038, 15)
        assertTrue(tx in 15900..16200)
        assertTrue(ty in 12300..12600)

        // Conversión inversa
        val (lat, lon) = TileMath.tileToLatLon(tx, ty, 15)
        assertTrue(abs(lat - 40.4168) < 0.1)
        assertTrue(abs(lon - -3.7038) < 0.1)
    }

    @Test
    fun testBoundingBoxEnumeration() {
        // Pequeño BoundingBox en la Sierra de Guadarrama
        val bbox = MapBoundingBox(
            minLat = 40.70,
            maxLat = 40.75,
            minLon = -4.05,
            maxLon = -4.00
        )

        val tiles = TileMath.enumerateBoundingBoxTiles(
            bbox = bbox,
            layer = MapLayer.TRAIL_OSM,
            minZ = 12,
            maxZ = 13,
            maxTiles = 100
        )

        assertTrue(tiles.isNotEmpty())
        for (t in tiles) {
            assertEquals(MapLayer.TRAIL_OSM, t.layer)
            assertTrue(t.z in 12..13)
        }
    }

    @Test
    fun testParentOverzoomMath() {
        val z = 16
        val x = 32005
        val y = 24810

        // Delta 1 (Z15)
        val pZ1 = z - 1
        val pX1 = x shr 1
        val pY1 = y shr 1
        assertEquals(15, pZ1)
        assertEquals(16002, pX1)
        assertEquals(12405, pY1)

        val subX = x and 1
        val subY = y and 1
        assertEquals(1, subX)
        assertEquals(0, subY)
    }
}

