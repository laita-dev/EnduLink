package com.oscar.endulink.bike

sealed class BikeWireException(message: String) : Exception(message) {
    class Eof(message: String = "La moto ha cerrado la conexión (EOF)") : BikeWireException(message)
    class Closed(message: String = "Socket cerrado") : BikeWireException(message)
    class Timeout(message: String) : BikeWireException(message)
    class ProtocolMismatch(message: String) : BikeWireException(message)
    class Io(message: String) : BikeWireException(message)
}

// MARK: - Little Endian Buffer Helpers

fun ByteArray.readUInt16LE(offset: Int): UShort {
    val b0 = this[offset].toInt() and 0xFF
    val b1 = (this[offset + 1].toInt() and 0xFF) shl 8
    return (b0 or b1).toUShort()
}

fun ByteArray.readInt16LE(offset: Int): Short = readUInt16LE(offset).toShort()

fun ByteArray.readUInt32LE(offset: Int): UInt {
    val b0 = (this[offset].toLong() and 0xFF)
    val b1 = (this[offset + 1].toLong() and 0xFF) shl 8
    val b2 = (this[offset + 2].toLong() and 0xFF) shl 16
    val b3 = (this[offset + 3].toLong() and 0xFF) shl 24
    return (b0 or b1 or b2 or b3).toUInt()
}

fun ByteArray.readInt32LE(offset: Int): Int = readUInt32LE(offset).toInt()

fun ByteArrayBuilder(initialCapacity: Int = 64): ByteArrayOutput = ByteArrayOutput(initialCapacity)

class ByteArrayOutput(initialCapacity: Int = 64) {
    private var buffer = ByteArray(initialCapacity.coerceAtLeast(16))
    private var size = 0

    private fun ensureCapacity(additional: Int) {
        val needed = size + additional
        if (needed <= buffer.size) return
        var newCap = buffer.size * 2
        if (newCap < needed) newCap = needed
        buffer = buffer.copyOf(newCap)
    }

    fun writeUInt16LE(value: UShort) {
        ensureCapacity(2)
        val v = value.toInt()
        buffer[size++] = (v and 0xFF).toByte()
        buffer[size++] = ((v shr 8) and 0xFF).toByte()
    }

    fun writeInt16LE(value: Short) = writeUInt16LE(value.toUShort())

    fun writeUInt32LE(value: UInt) {
        ensureCapacity(4)
        val v = value.toLong()
        buffer[size++] = (v and 0xFF).toByte()
        buffer[size++] = ((v shr 8) and 0xFF).toByte()
        buffer[size++] = ((v shr 16) and 0xFF).toByte()
        buffer[size++] = ((v shr 24) and 0xFF).toByte()
    }

    fun writeInt32LE(value: Int) = writeUInt32LE(value.toUInt())

    fun writeBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        ensureCapacity(bytes.size)
        bytes.copyInto(buffer, destinationOffset = size)
        size += bytes.size
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

// MARK: - Framing Messages

data class PxcMessage(
    val command: UInt,
    val body: ByteArray = ByteArray(0)
) {
    val text: String get() = body.decodeToString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PxcMessage) return false
        return command == other.command && body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = command.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }

    override fun toString(): String = "PxcMessage(0x${command.toString(16).padStart(8, '0')}, ${body.size} bytes)"
}

data class RvMessage(
    val command: UShort,
    val body: ByteArray = ByteArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RvMessage) return false
        return command == other.command && body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = command.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }

    override fun toString(): String = "RvMessage(0x${command.toString(16).padStart(4, '0')}, ${body.size} bytes)"
}

// MARK: - PXC Protocol

object Pxc {
    const val HEADER_SIZE = 16
    private const val LARGEST_BODY = 4 * 1024 * 1024

    val openBikeAsks: UInt  = 0x00010000u
    val openPhoneAsks: UInt = 0x00020000u

    val bikeInfo: UInt    = 0x00010010u
    val hudIcons: UInt    = 0x00010040u
    val checkSerial: UInt = 0x000103E0u
    val whatTime: UInt    = 0x00010450u
    val weather: UInt     = 0x00010630u
    val extra0740: UInt   = 0x00010740u
    val encrypt: UInt     = 0x000107C0u

    val appInFront: UInt    = 0x00020020u
    val serialChecked: UInt = 0x000201C0u

    val heartbeat: UInt = 0x70000000u
    val knock: UInt     = 0x70000010u

    val notSupported: UInt = 0x80000010u
    val failed: UInt       = 0x80000020u

    fun read(socket: BikeSocket): PxcMessage {
        val head = socket.readFully(HEADER_SIZE)
        val command = head.readUInt32LE(0)
        val length = head.readUInt32LE(4)
        val check = head.readUInt32LE(8)
        if ((command xor length) != check) {
            throw BikeWireException.ProtocolMismatch("PXC: cabecera que no cuadra (command=$command, length=$length, check=$check)")
        }
        val bodyLength = length.toInt() - HEADER_SIZE
        if (bodyLength < 0 || bodyLength > LARGEST_BODY) {
            throw BikeWireException.ProtocolMismatch("PXC: longitud imposible $length")
        }
        val body = socket.readFully(bodyLength)
        return PxcMessage(command, body)
    }

    fun write(socket: BikeSocket, command: UInt, body: ByteArray = ByteArray(0)) {
        val length = (HEADER_SIZE + body.size).toUInt()
        val out = ByteArrayOutput()
        out.writeUInt32LE(command)
        out.writeUInt32LE(length)
        out.writeUInt32LE(command xor length)
        out.writeUInt32LE(0u)
        out.writeBytes(body)
        socket.writeAll(out.toByteArray())
    }
}

// MARK: - RV Protocol

object Rv {
    const val HEADER_SIZE = 8

    val configure: UShort     = 0x0010u.toUShort()
    val keyOrTouch: UShort    = 0x0020u.toUShort()
    val version: UShort       = 0x0030u.toUShort()
    val heartbeat: UShort     = 0x0040u.toUShort()
    val configureMore: UShort = 0x0060u.toUShort()
    val start: UShort         = 0x0070u.toUShort()
    val nextFrame: UShort     = 0x0072u.toUShort()
    val shell: UShort         = 0x0110u.toUShort()

    val notSupported: UShort = 0x7F02u.toUShort()
    val startFailed: UShort  = 0x7F05u.toUShort()

    fun read(socket: BikeSocket): RvMessage {
        val head = socket.readFully(HEADER_SIZE)
        val command = head.readUInt16LE(0)
        val bodyLength = head.readUInt16LE(2).toInt()
        val body = socket.readFully(bodyLength)
        return RvMessage(command, body)
    }

    fun write(socket: BikeSocket, command: UShort, body: ByteArray = ByteArray(0)) {
        val out = ByteArrayOutput()
        out.writeUInt16LE(command)
        out.writeUInt16LE(body.size.toUShort())
        out.writeUInt32LE(0u)
        out.writeBytes(body)
        socket.writeAll(out.toByteArray())
    }
}

// MARK: - Picture Request & Reply

data class PictureRequest(
    val width: Int,
    val height: Int,
    val fps: Int,
    val encoder: Int,
    val bitRate: Int,
    val orientation: Int,
    val displayId: Int,
    val extended: Int,
    val signedId: String
) {
    val encodedWidth: Int get() = width and 15.inv()
    val encodedHeight: Int get() = height and 15.inv()

    fun reply(): ByteArray {
        val out = ByteArrayOutput()
        out.writeInt32LE(encoder)
        out.writeInt16LE(encodedWidth.toShort())
        out.writeInt16LE(encodedHeight.toShort())
        if (extended != 0) {
            out.writeBytes(byteArrayOf(extended.toByte()))
        }
        return out.toByteArray()
    }

    companion object {
        fun parse(body: ByteArray): PictureRequest {
            if (body.size < 24) {
                throw BikeWireException.ProtocolMismatch("RV: petición de imagen corta (${body.size})")
            }
            val width = body.readInt16LE(0).toInt()
            val height = body.readInt16LE(2).toInt()
            val fps = body.readInt32LE(4)
            val rawEnc = body.readInt32LE(8)
            val encoder = if (rawEnc == 0) 1 else rawEnc
            val rawBitrate = body.readInt32LE(20)
            val bitRate = if (rawBitrate == 0) 3_000_000 else rawBitrate

            var orientation = 0
            var displayId = 0
            var extended = 0
            if (body.size >= 32) {
                orientation = body[26].toInt() and 0xFF
                displayId = body[27].toInt() and 0xFF
                extended = body[29].toInt() and 0xFF
            }
            val signedId = if (body.size > 32) {
                body.copyOfRange(32, body.size).decodeToString()
            } else {
                ""
            }
            return PictureRequest(
                width = width,
                height = height,
                fps = fps,
                encoder = encoder,
                bitRate = bitRate,
                orientation = orientation,
                displayId = displayId,
                extended = extended,
                signedId = signedId
            )
        }
    }
}

// MARK: - Bike Input (Touch & Keys)

data class BikeInput(
    val type: Int,
    val x: Int,
    val y: Int,
    val slot: Int,
    val key: Int
) {
    companion object {
        fun parse(body: ByteArray): BikeInput? {
            if (body.size < 10) return null
            return BikeInput(
                type = body.readInt16LE(0).toInt(),
                x = body.readInt16LE(2).toInt(),
                y = body.readInt16LE(4).toInt(),
                slot = body.readInt16LE(6).toInt(),
                key = body.readUInt16LE(8).toInt()
            )
        }
    }
}

