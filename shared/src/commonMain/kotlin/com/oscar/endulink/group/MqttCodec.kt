package com.oscar.endulink.group

import com.oscar.endulink.bike.ByteArrayOutput

/**
 * Códec binario ligero de protocolo MQTT 3.1.1 sobre WebSockets (QoS 0, Retain, Keep-Alive).
 * Implementado en Kotlin puro para `commonMain` sin dependencias nativas.
 */
object MqttCodec {

    fun encodeConnect(clientId: String, keepAliveSeconds: Int = 60): ByteArray {
        val body = ByteArrayOutput(64)
        // Protocol Name "MQTT", Level 4 (3.1.1), Flags 0x02 (Clean Session), KeepAlive
        writeUtf8String(body, "MQTT")
        body.writeBytes(
            byteArrayOf(
                0x04,
                0x02,
                ((keepAliveSeconds ushr 8) and 0xFF).toByte(),
                (keepAliveSeconds and 0xFF).toByte()
            )
        )
        writeUtf8String(body, clientId)
        return wrapPacket(0x10, body.toByteArray())
    }

    fun encodeSubscribe(packetId: Int, topicFilter: String): ByteArray {
        val body = ByteArrayOutput(64)
        body.writeBytes(
            byteArrayOf(
                ((packetId ushr 8) and 0xFF).toByte(),
                (packetId and 0xFF).toByte()
            )
        )
        writeUtf8String(body, topicFilter)
        body.writeBytes(byteArrayOf(0x00)) // QoS 0
        return wrapPacket(0x82, body.toByteArray())
    }

    fun encodePublish(topic: String, payload: ByteArray, retain: Boolean): ByteArray {
        val topicBytes = topic.encodeToByteArray()
        val body = ByteArrayOutput(2 + topicBytes.size + payload.size)
        body.writeBytes(
            byteArrayOf(
                ((topicBytes.size ushr 8) and 0xFF).toByte(),
                (topicBytes.size and 0xFF).toByte()
            )
        )
        body.writeBytes(topicBytes)
        body.writeBytes(payload)
        val headerByte = if (retain) 0x31 else 0x30
        return wrapPacket(headerByte, body.toByteArray())
    }

    fun encodePingReq(): ByteArray = byteArrayOf(0xC0.toByte(), 0x00)

    fun encodeDisconnect(): ByteArray = byteArrayOf(0xE0.toByte(), 0x00)

    private fun writeUtf8String(out: ByteArrayOutput, s: String) {
        val bytes = s.encodeToByteArray()
        out.writeBytes(
            byteArrayOf(
                ((bytes.size ushr 8) and 0xFF).toByte(),
                (bytes.size and 0xFF).toByte()
            )
        )
        out.writeBytes(bytes)
    }

    private fun wrapPacket(headerTypeAndFlags: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutput(5 + body.size)
        out.writeBytes(byteArrayOf(headerTypeAndFlags.toByte()))
        var rem = body.size
        do {
            var digit = rem % 128
            rem /= 128
            if (rem > 0) digit = digit or 0x80
            out.writeBytes(byteArrayOf(digit.toByte()))
        } while (rem > 0)
        out.writeBytes(body)
        return out.toByteArray()
    }
}

sealed interface MqttPacket {
    data class ConnAck(val returnCode: Int) : MqttPacket
    data class SubAck(val packetId: Int) : MqttPacket
    data class Publish(val topic: String, val payload: ByteArray, val retain: Boolean) : MqttPacket {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Publish) return false
            return topic == other.topic && retain == other.retain && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = topic.hashCode()
            result = 31 * result + payload.contentHashCode()
            result = 31 * result + retain.hashCode()
            return result
        }
    }
    data object PingResp : MqttPacket
    data class Other(val type: Int) : MqttPacket
}

class MqttStreamDecoder {
    private var buffer = ByteArray(0)

    fun reset() {
        buffer = ByteArray(0)
    }

    fun feed(chunk: ByteArray): List<MqttPacket> {
        if (chunk.isNotEmpty()) {
            val combined = ByteArray(buffer.size + chunk.size)
            buffer.copyInto(combined)
            chunk.copyInto(combined, destinationOffset = buffer.size)
            buffer = combined
        }

        val packets = ArrayList<MqttPacket>()
        var cursor = 0

        while (cursor < buffer.size) {
            if (buffer.size - cursor < 2) break

            val headerByte = buffer[cursor].toInt() and 0xFF
            var multiplier = 1
            var remLen = 0
            var lenBytes = 0
            var needMore = false

            while (true) {
                val idx = cursor + 1 + lenBytes
                if (idx >= buffer.size) {
                    needMore = true
                    break
                }
                val encodedByte = buffer[idx].toInt() and 0xFF
                remLen += (encodedByte and 0x7F) * multiplier
                lenBytes++
                if ((encodedByte and 0x80) == 0) break
                multiplier *= 128
                if (lenBytes >= 4) break
            }
            if (needMore) break

            val totalPacketLen = 1 + lenBytes + remLen
            if (buffer.size - cursor < totalPacketLen) break

            val bodyStart = cursor + 1 + lenBytes
            val bodyEnd = bodyStart + remLen
            parsePacket(headerByte, buffer, bodyStart, bodyEnd)?.let { packets.add(it) }
            cursor += totalPacketLen
        }

        if (cursor > 0) {
            buffer = if (cursor >= buffer.size) ByteArray(0) else buffer.copyOfRange(cursor, buffer.size)
        }
        return packets
    }

    private fun parsePacket(headerByte: Int, data: ByteArray, start: Int, end: Int): MqttPacket? {
        val packetType = (headerByte ushr 4) and 0x0F
        return when (packetType) {
            2 -> { // CONNACK
                if (end - start < 2) return null
                MqttPacket.ConnAck(returnCode = data[start + 1].toInt() and 0xFF)
            }
            3 -> { // PUBLISH
                if (end - start < 2) return null
                val topicLen = ((data[start].toInt() and 0xFF) shl 8) or (data[start + 1].toInt() and 0xFF)
                val topicStart = start + 2
                val topicEnd = topicStart + topicLen
                if (topicEnd > end) return null
                val topic = data.copyOfRange(topicStart, topicEnd).decodeToString()
                val qos = (headerByte ushr 1) and 0x03
                val payloadStart = if (qos > 0) topicEnd + 2 else topicEnd
                if (payloadStart > end) return null
                val payload = data.copyOfRange(payloadStart, end)
                val retain = (headerByte and 0x01) != 0
                MqttPacket.Publish(topic = topic, payload = payload, retain = retain)
            }
            9 -> { // SUBACK
                if (end - start < 2) return null
                val pid = ((data[start].toInt() and 0xFF) shl 8) or (data[start + 1].toInt() and 0xFF)
                MqttPacket.SubAck(packetId = pid)
            }
            13 -> MqttPacket.PingResp // PINGRESP
            else -> MqttPacket.Other(packetType)
        }
    }
}
