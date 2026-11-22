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
        assertEquals("6KV7H6", GroupInvite.parseRoomCode("https://laita-dev.github.io/EnduLink/group/?c=6KV7H6"))
        assertEquals(
            "6KV7H6",
            GroupInvite.parseRoomCode(
                "Nestor te invita a su grupo de ruta en EnduLink.\nCódigo: 6KV-7H6\nÚnete pulsando aquí: https://laita-dev.github.io/EnduLink/group/?c=6KV7H6"
            )
        )
        assertNull(GroupInvite.parseRoomCode("http://www.carbit.com.cn/down6/index.html?name=HJ-123"))
        assertNull(GroupInvite.parseQrInvite("HJ-064441"))
        assertNull(GroupInvite.parseQrInvite("WIFI:T:WPA;S:DIRECT-HJ-123;P:12345678;;"))
        assertEquals("K7M9P2", GroupInvite.parseQrInvite("endulink://group/K7M9P2"))
        assertEquals("6KV7H6", GroupInvite.parseQrInvite("https://laita-dev.github.io/EnduLink/group/?c=6KV7H6"))

        val shareMsg = GroupInvite.buildShareMessage("6KV7H6", "Nestor")
        assertTrue(shareMsg.contains("6KV-7H6"))
        assertFalse(shareMsg.contains("http", ignoreCase = true))
        assertFalse(shareMsg.contains("github.io", ignoreCase = true))
        assertEquals("6KV7H6", GroupInvite.parseRoomCode(shareMsg))
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

        val wireDest = CompactGroupTrackCodec.encodeRoute(
            route = route,
            senderId = "m_1",
            senderName = "Carlos",
            sentAtEpochMs = 2001L,
            isDestination = true,
            destinationCoord = GeoCoord(38.9201, -0.4215)
        )
        assertNotNull(wireDest)
        val decodedDest = CompactGroupTrackCodec.toSharedGroupTrack(wireDest)
        assertNotNull(decodedDest)
        assertTrue(decodedDest.isDestination)
        assertEquals(38.9201, decodedDest.destinationLat)
        assertEquals(-0.4215, decodedDest.destinationLon)

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
            statusMessage = "Pinchazo en la bajada del río",
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
        assertEquals("PINCHAZO EN LA BAJADA DEL RIO", plan.alertBanner!!.subtext)
        assertFalse(plan.alertBanner!!.isExpanded)
    }

    @Test
    fun testTftGroupAlertBannerTwoPhaseAndCustomMessageWithRiding() {
        val center = GeoCoord(40.4168, -3.7038)
        val msgMember = GroupMember(
            id = "m_msg",
            name = "Ana",
            color = RiderColor.CYAN,
            latitude = 40.4175,
            longitude = -3.7030,
            status = RiderStatus.RIDING,
            statusMessage = "Paramos en el bar de arriba",
            statusChangedAtEpochMs = 100_000L,
            receivedAtEpochMs = 100_000L
        )

        // Fase 1 (< 5 s): grande y expandido aunque el estado sea En ruta con mensaje personalizado
        val phase1 = GroupTftLayout.compute(
            members = listOf(msgMember),
            mapCenter = center,
            zoom = 15,
            tileSizePx = 256f * 1.45f,
            mapHeadingDeg = 0.0,
            anchorX = 296f,
            anchorY = 546f,
            width = 592f,
            height = 816f,
            scale = 1f,
            nowMs = 102_000L
        )
        assertNotNull(phase1.alertBanner)
        assertTrue(phase1.alertBanner!!.isExpanded)
        assertTrue(phase1.alertBanner!!.expandedProgress > 0.5f)
        assertTrue(phase1.alertBanner!!.text.startsWith("MSG: ANA"))
        assertEquals("PARAMOS EN EL BAR DE ARRIBA", phase1.alertBanner!!.subtext)

        // Fase 2 (>= 5 s): pasa a compacto en esquina y permanece hasta volver a "En ruta" sin mensaje
        val phase2 = GroupTftLayout.compute(
            members = listOf(msgMember),
            mapCenter = center,
            zoom = 15,
            tileSizePx = 256f * 1.45f,
            mapHeadingDeg = 0.0,
            anchorX = 296f,
            anchorY = 546f,
            width = 592f,
            height = 816f,
            scale = 1f,
            nowMs = 106_000L
        )
        assertNotNull(phase2.alertBanner)
        assertFalse(phase2.alertBanner!!.isExpanded)
        assertEquals("PARAMOS EN EL BAR DE ARRIBA", phase2.alertBanner!!.subtext)

        // Vuelve a "En ruta" sin mensaje -> desaparece el banner
        val cleared = GroupTftLayout.compute(
            members = listOf(msgMember.copy(status = RiderStatus.RIDING, statusMessage = "", statusChangedAtEpochMs = 0L)),
            mapCenter = center,
            zoom = 15,
            tileSizePx = 256f * 1.45f,
            mapHeadingDeg = 0.0,
            anchorX = 296f,
            anchorY = 546f,
            width = 592f,
            height = 816f,
            scale = 1f,
            nowMs = 107_000L
        )
        assertNull(cleared.alertBanner)
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
        assertTrue(decoded.bikeMapConfig.showGpxOffTrackWarning)
        assertTrue(decoded.groupConfig.enableOnlineSync)
        assertTrue(decoded.groupConfig.enableOfflineLanSync)
        assertFalse(decoded.groupConfig.batterySaverMode)
    }
}
