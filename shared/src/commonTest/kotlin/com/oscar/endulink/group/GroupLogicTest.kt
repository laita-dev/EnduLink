package com.oscar.endulink.group

import com.oscar.endulink.trail.GPXParser
import com.oscar.endulink.trail.GPXPoint
import com.oscar.endulink.trail.GPXRoute
import com.oscar.endulink.trail.GPXWaypoint
import com.oscar.endulink.trail.GeoCoord
import com.oscar.endulink.trail.SettingsStore
import com.oscar.endulink.trail.TrackColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupLogicTest {

    @Test
    fun testGroupInviteGenerationAndParsing() {
        val code = GroupInvite.generateRoomCode()
        assertEquals(6, code.length)
        assertTrue(code.all { it in 'A'..'Z' || it in '2'..'9' })

        val formatted = GroupInvite.formatForDisplay(code)
        assertEquals(7, formatted.length)
        assertEquals('-', formatted[3])

        assertEquals("K7M9P2", GroupInvite.parseRoomCode("k7m-9p2"))
        assertEquals("K7M9P2", GroupInvite.parseRoomCode("endulink://group/K7M-9P2?ref=wa"))
        assertNull(GroupInvite.parseRoomCode("http://www.carbit.com.cn/down6/index.html?name=HJ-123"))
        assertNull(GroupInvite.parseQrInvite("HJ-064441"))
        assertNull(GroupInvite.parseQrInvite("WIFI:T:WPA;S:DIRECT-HJ-123;P:12345678;;"))
        assertEquals("K7M9P2", GroupInvite.parseQrInvite("endulink://group/K7M9P2"))
    }

    @Test
    fun testCompactTrackCodecRoundTripAndDownsampling() {
        val pts = (0 until 4500).map { i ->
            GPXPoint(
                latitude = 40.40000 + i * 0.0001,
                longitude = -3.70000 + i * 0.00012,
                elevation = 700.0 + (i % 80)
            )
        }
        val route = GPXRoute(
            id = "r1",
            name = "Sierra Norte",
            fileName = "sierra.gpx",
            points = pts,
            waypoints = listOf(
                GPXWaypoint(id = "w1", name = "Fuente", latitude = 40.42, longitude = -3.68, elevation = 850.0, symbol = "water")
            ),
            totalDistanceKm = 70.0,
            elevationGainM = 500.0,
            importedAtEpochMs = 1000L,
            color = TrackColor.ORANGE
        )

        val wire = CompactGroupTrackCodec.encodeRoute(route, senderId = "m_1", senderName = "Carlos", sentAtEpochMs = 2000L)
        assertNotNull(wire)
        assertEquals(3000, wire.latE5.size)
        assertEquals(1, wire.wpts.size)

        val decoded = CompactGroupTrackCodec.toSharedGroupTrack(wire)
        assertNotNull(decoded)
        assertEquals("Sierra Norte", decoded.name)
        assertEquals(3000, decoded.pointCount)
        assertTrue(decoded.distanceKm > 50.0)

        val reparsed = GPXParser.parse(decoded.gpxXml, fallbackName = "test.gpx")
        assertEquals(3000, reparsed.points.size)
        assertEquals(1, reparsed.waypoints.size)
        assertTrue(reparsed.points.first().coordinate.distanceTo(pts.first().coordinate) < 2.0)
        assertTrue(reparsed.points.last().coordinate.distanceTo(pts.last().coordinate) < 2.0)
    }

    @Test
    fun testAdaptiveTxPolicy() {
        val base = GeoCoord(40.4168, -3.7038)
        val moved20m = GeoCoord(40.41698, -3.7038)

        // Bloqueado antes de 2 s salvo forceImmediate
        assertFalse(GroupTxPolicy.shouldTransmitPosition(10_000L, 9_000L, base, moved20m, 25.0, false, false))
        assertTrue(GroupTxPolicy.shouldTransmitPosition(10_000L, 9_000L, base, base, 0.0, false, forceImmediate = true))

        // Normal: 4s en movimiento, 25s parado
        assertTrue(GroupTxPolicy.shouldTransmitPosition(14_100L, 10_000L, base, moved20m, 15.0, false, false))
        assertFalse(GroupTxPolicy.shouldTransmitPosition(15_000L, 10_000L, base, base, 0.0, false, false))
        assertTrue(GroupTxPolicy.shouldTransmitPosition(35_500L, 10_000L, base, base, 0.0, false, false))

        // Ahorro batería: 10s en movimiento, 45s parado
        assertFalse(GroupTxPolicy.shouldTransmitPosition(15_000L, 10_000L, base, moved20m, 20.0, true, false))
        assertTrue(GroupTxPolicy.shouldTransmitPosition(20_500L, 10_000L, base, moved20m, 20.0, true, false))
        assertFalse(GroupTxPolicy.shouldTransmitPosition(40_000L, 10_000L, base, base, 0.0, true, false))
        assertTrue(GroupTxPolicy.shouldTransmitPosition(56_000L, 10_000L, base, base, 0.0, true, false))
    }

    @Test
    fun testTftGroupLayoutProjectionAndEdgeRadar() {
        val center = GeoCoord(40.4168, -3.7038)
        val nearMember = GroupMember(
            id = "m_near",
            name = "Luis",
            color = RiderColor.LIME,
            latitude = 40.4172,
            longitude = -3.7038,
            speedKmh = 25.0,
            headingDegrees = 45.0,
            receivedAtEpochMs = 100_000L
        )
        val farSosMember = GroupMember(
            id = "m_sos",
            name = "Carlos",
            color = RiderColor.CYAN,
            latitude = 40.4800,
            longitude = -3.6500,
            status = RiderStatus.SOS,
            receivedAtEpochMs = 100_000L
        )

        val plan = GroupTftLayout.compute(
            members = listOf(nearMember, farSosMember),
            mapCenter = center,
            zoom = 15,
            tileSizePx = 256f * 1.45f,
            mapHeadingDeg = 0.0,
            anchorX = 592f * 0.5f,
            anchorY = 816f * 0.67f,
            width = 592f,
            height = 816f,
            scale = 1f,
            nowMs = 105_000L
        )

        assertEquals(1, plan.inViewMembers.size)
        assertEquals("m_near", plan.inViewMembers[0].member.id)
        assertEquals(1, plan.edgeIndicators.size)
        assertEquals("m_sos", plan.edgeIndicators[0].member.id)
        assertTrue(plan.edgeIndicators[0].edgeX in 50f..542f)
        assertTrue(plan.edgeIndicators[0].edgeY in 100f..720f)
        assertNotNull(plan.alertBanner)
        assertTrue(plan.alertBanner!!.text.startsWith("SOS: CARLOS"))
    }

    @Test
    fun testSettingsBackwardCompatibilityWithLegacyJson() {
        val legacyJson = """
            {
              "selectedLayerRaw": "trail_osm",
              "bikeMapConfig": { "zoom": 16, "orientation": "north" },
              "lanTestingDefaultApplied": true,
              "allowLocalLanTesting": true
            }
        """.trimIndent()
        val decoded = SettingsStore.decodeSettings(legacyJson)
        assertTrue(decoded.bikeMapConfig.showGroupMembersOnTft)
        assertTrue(decoded.bikeMapConfig.showGroupNamesOnTft)
        assertTrue(decoded.bikeMapConfig.showGroupEdgeRadarOnTft)
        assertTrue(decoded.bikeMapConfig.showGroupHudBadgeOnTft)
        assertTrue(decoded.bikeMapConfig.showGroupAlertsOnTft)
        assertTrue(decoded.groupConfig.enableOnlineSync)
        assertTrue(decoded.groupConfig.enableOfflineLanSync)
        assertFalse(decoded.groupConfig.batterySaverMode)
    }
}
