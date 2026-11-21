package com.oscar.endulink.group

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MqttCodecTest {

    @Test
    fun testEncodeControlPackets() {
        val connect = MqttCodec.encodeConnect("endulink_test", keepAliveSeconds = 60)
        assertEquals(0x10.toByte(), connect[0])
        assertTrue(connect.decodeToString().contains("MQTT"))
        assertTrue(connect.decodeToString().contains("endulink_test"))

        val sub = MqttCodec.encodeSubscribe(packetId = 42, topicFilter = "endulink/g1/abc/#")
        assertEquals(0x82.toByte(), sub[0])

        val ping = MqttCodec.encodePingReq()
        assertContentEquals(byteArrayOf(0xC0.toByte(), 0x00), ping)

        val disc = MqttCodec.encodeDisconnect()
        assertContentEquals(byteArrayOf(0xE0.toByte(), 0x00), disc)
    }

    @Test
    fun testPublishMultiByteLengthAndFragmentedDecode() {
        val topic = "endulink/g1/0123456789abcdef/m/rider1"
        val bigPayload = ByteArray(300) { (it and 0x7F).toByte() }
        val encoded = MqttCodec.encodePublish(topic, bigPayload, retain = true)

        val decoder = MqttStreamDecoder()
        val collected = mutableListOf<MqttPacket>()
        // Alimentar byte a byte para validar reensamblado fragmentado
        for (b in encoded) {
            collected.addAll(decoder.feed(byteArrayOf(b)))
        }
        assertEquals(1, collected.size)
        val pub = collected[0] as MqttPacket.Publish
        assertEquals(topic, pub.topic)
        assertTrue(pub.retain)
        assertContentEquals(bigPayload, pub.payload)
    }

    @Test
    fun testCoalescedPacketsInSingleChunk() {
        val connAck = byteArrayOf(0x20, 0x02, 0x00, 0x00)
        val subAck = byteArrayOf(0x90.toByte(), 0x03, 0x00, 0x01, 0x00)
        val pingResp = byteArrayOf(0xD0.toByte(), 0x00)
        val pub = MqttCodec.encodePublish("endulink/g1/t/m/u2", "hola".encodeToByteArray(), retain = false)

        val combined = connAck + subAck + pingResp + pub
        val decoder = MqttStreamDecoder()
        val packets = decoder.feed(combined)

        assertEquals(4, packets.size)
        assertEquals(MqttPacket.ConnAck(0), packets[0])
        assertEquals(MqttPacket.SubAck(1), packets[1])
        assertEquals(MqttPacket.PingResp, packets[2])
        val p3 = packets[3] as MqttPacket.Publish
        assertEquals("endulink/g1/t/m/u2", p3.topic)
        assertEquals("hola", p3.payload.decodeToString())
        assertTrue(!p3.retain)
    }
}
