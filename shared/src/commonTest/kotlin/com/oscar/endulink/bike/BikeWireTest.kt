package com.oscar.endulink.bike

import kotlin.test.*

class MockBikeSocket(val incomingData: ByteArray = ByteArray(0)) : BikeSocket {
    override val remoteIP: String? = "192.168.1.1"
    override val localIP: String? = "192.168.1.2"

    private var readOffset = 0
    val writtenBytes = ArrayList<Byte>()

    override fun readFully(length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        if (readOffset + length > incomingData.size) {
            throw BikeWireException.Eof("Not enough mock data")
        }
        val slice = incomingData.copyOfRange(readOffset, readOffset + length)
        readOffset += length
        return slice
    }

    override fun writeAll(data: ByteArray) {
        for (b in data) writtenBytes.add(b)
    }

    override fun setReceiveTimeout(ms: Int) {}
    override fun setSendTimeout(ms: Int) {}
    override fun close() {}
}

class BikeWireTest {

    @Test
    fun testPxcReadAndWrite() {
        val outSock = MockBikeSocket()
        val payload = "Hello Moto".encodeToByteArray()
        val cmd = Pxc.bikeInfo

        Pxc.write(outSock, cmd, payload)
        val rawWritten = outSock.writtenBytes.toByteArray()

        assertEquals(Pxc.HEADER_SIZE + payload.size, rawWritten.size)

        val inSock = MockBikeSocket(rawWritten)
        val readMsg = Pxc.read(inSock)

        assertEquals(cmd, readMsg.command)
        assertEquals("Hello Moto", readMsg.text)
    }

    @Test
    fun testPxcInvalidChecksumThrows() {
        val badHeader = ByteArray(Pxc.HEADER_SIZE)
        // command = 1
        badHeader[0] = 1
        // length = 16
        badHeader[4] = 16
        // check = 0 (debería ser 1 ^ 16 = 17)
        badHeader[8] = 0

        val inSock = MockBikeSocket(badHeader)
        assertFailsWith<BikeWireException.ProtocolMismatch> {
            Pxc.read(inSock)
        }
    }

    @Test
    fun testRvReadAndWrite() {
        val outSock = MockBikeSocket()
        val payload = byteArrayOf(1, 2, 3, 4)
        val cmd = Rv.configure

        Rv.write(outSock, cmd, payload)
        val rawWritten = outSock.writtenBytes.toByteArray()

        assertEquals(Rv.HEADER_SIZE + payload.size, rawWritten.size)

        val inSock = MockBikeSocket(rawWritten)
        val readMsg = Rv.read(inSock)

        assertEquals(cmd, readMsg.command)
        assertTrue(payload.contentEquals(readMsg.body))
    }

    @Test
    fun testPictureRequestParsingAndReply() {
        val body = ByteArrayOutput()
        body.writeInt16LE(592.toShort())  // width
        body.writeInt16LE(816.toShort())  // height
        body.writeInt32LE(15)            // fps
        body.writeInt32LE(2)             // encoder H.264
        body.writeInt32LE(0)             // unused
        body.writeInt32LE(3_000_000)     // bitrate
        // 24 bytes up to here
        // bytes 24..31
        body.writeBytes(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))

        val req = PictureRequest.parse(body.toByteArray())
        assertEquals(592, req.width)
        assertEquals(816, req.height)
        assertEquals(592, req.encodedWidth)
        assertEquals(816, req.encodedHeight)
        assertEquals(15, req.fps)
        assertEquals(2, req.encoder)

        val reply = req.reply()
        assertEquals(8, reply.size)
        assertEquals(2, reply.readInt32LE(0))
        assertEquals(592.toShort(), reply.readInt16LE(4))
        assertEquals(816.toShort(), reply.readInt16LE(6))
    }

    @Test
    fun testBikeQrParsing() {
        val url = "http://www.carbit.com.cn/down6/index.html?modelid=37512&sn=ABC12345&action=9&ssid=DIRECT-HJ-5G-123456&pwd=password99&auth=WPA-PSK&name=HJ-5G-123456"
        val qr = BikeQr.parse(url)

        assertNotNull(qr)
        assertEquals("HJ-5G-123456", qr.name)
        assertEquals("DIRECT-HJ-5G-123456", qr.ssid)
        assertEquals("password99", qr.password)
        assertEquals("37512", qr.model)
        assertEquals("ABC12345", qr.serial)
    }
}

