package com.oscar.endulink.group

import kotlin.random.Random

/**
 * Primitivas criptográficas en Kotlin puro (`commonMain`) sin dependencias de plataforma:
 * - SHA-256 (FIPS 180-4)
 * - HMAC-SHA256 (RFC 2104)
 * - ChaCha20 (RFC 8439)
 * - Envoltorio autenticado Encrypt-then-MAC derivado del código del grupo
 */
object GroupCrypto {

    private val SHA256_K = intArrayOf(
        0x428a2f98.toInt(), 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt()
    )

    private val SHA256_INIT = intArrayOf(
        0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
        0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19
    )

    private const val ENVELOPE_VERSION: Byte = 1
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16

    fun sha256(data: ByteArray): ByteArray {
        val bitLen = data.size.toLong() * 8L
        val rem = (data.size + 1) % 64
        val zeroPad = if (rem <= 56) 56 - rem else 120 - rem
        val padded = ByteArray(data.size + 1 + zeroPad + 8)
        data.copyInto(padded)
        padded[data.size] = 0x80.toByte()

        val lenOffset = padded.size - 8
        for (i in 0 until 8) {
            padded[lenOffset + i] = ((bitLen ushr ((7 - i) * 8)) and 0xFFL).toByte()
        }

        val h = SHA256_INIT.copyOf()
        val w = IntArray(64)

        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) {
                val base = offset + i * 4
                w[i] = ((padded[base].toInt() and 0xFF) shl 24) or
                    ((padded[base + 1].toInt() and 0xFF) shl 16) or
                    ((padded[base + 2].toInt() and 0xFF) shl 8) or
                    (padded[base + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotr(7) xor w[i - 15].rotr(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotr(17) xor w[i - 2].rotr(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hh = h[7]

            for (i in 0 until 64) {
                val s1 = e.rotr(6) xor e.rotr(11) xor e.rotr(25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = hh + s1 + ch + SHA256_K[i] + w[i]
                val s0 = a.rotr(2) xor a.rotr(13) xor a.rotr(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj

                hh = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }

            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            h[5] += f
            h[6] += g
            h[7] += hh

            offset += 64
        }

        val out = ByteArray(32)
        for (i in 0 until 8) {
            val v = h[i]
            out[i * 4] = ((v ushr 24) and 0xFF).toByte()
            out[i * 4 + 1] = ((v ushr 16) and 0xFF).toByte()
            out[i * 4 + 2] = ((v ushr 8) and 0xFF).toByte()
            out[i * 4 + 3] = (v and 0xFF).toByte()
        }
        return out
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val blockKey = ByteArray(64)
        val normalizedKey = if (key.size > 64) sha256(key) else key
        normalizedKey.copyInto(blockKey)

        val oPad = ByteArray(64) { i -> (blockKey[i].toInt() xor 0x5c).toByte() }
        val iPad = ByteArray(64) { i -> (blockKey[i].toInt() xor 0x36).toByte() }

        val innerInput = ByteArray(64 + data.size)
        iPad.copyInto(innerInput)
        data.copyInto(innerInput, destinationOffset = 64)
        val innerHash = sha256(innerInput)

        val outerInput = ByteArray(64 + 32)
        oPad.copyInto(outerInput)
        innerHash.copyInto(outerInput, destinationOffset = 64)
        return sha256(outerInput)
    }

    fun chacha20Xor(key: ByteArray, nonce: ByteArray, counter: Int, input: ByteArray): ByteArray {
        require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
        require(nonce.size == 12) { "ChaCha20 nonce must be 12 bytes" }

        val out = ByteArray(input.size)
        val state = IntArray(16)
        val working = IntArray(16)
        val keystream = ByteArray(64)

        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574

        for (i in 0 until 8) {
            state[4 + i] = readIntLE(key, i * 4)
        }
        state[13] = readIntLE(nonce, 0)
        state[14] = readIntLE(nonce, 4)
        state[15] = readIntLE(nonce, 8)

        var blockCounter = counter
        var offset = 0
        while (offset < input.size) {
            state[12] = blockCounter++
            state.copyInto(working)

            repeat(10) {
                quarterRound(working, 0, 4, 8, 12)
                quarterRound(working, 1, 5, 9, 13)
                quarterRound(working, 2, 6, 10, 14)
                quarterRound(working, 3, 7, 11, 15)

                quarterRound(working, 0, 5, 10, 15)
                quarterRound(working, 1, 6, 11, 12)
                quarterRound(working, 2, 7, 8, 13)
                quarterRound(working, 3, 4, 9, 14)
            }

            for (i in 0 until 16) {
                val sum = working[i] + state[i]
                keystream[i * 4] = (sum and 0xFF).toByte()
                keystream[i * 4 + 1] = ((sum ushr 8) and 0xFF).toByte()
                keystream[i * 4 + 2] = ((sum ushr 16) and 0xFF).toByte()
                keystream[i * 4 + 3] = ((sum ushr 24) and 0xFF).toByte()
            }

            val blockLen = minOf(64, input.size - offset)
            for (i in 0 until blockLen) {
                out[offset + i] = (input[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            offset += blockLen
        }
        return out
    }

    fun deriveTopicHash(roomCode: String): String {
        val normalized = GroupInvite.parseRoomCode(roomCode) ?: roomCode.trim().uppercase()
        val hash = sha256("endulink-group-topic-v1:$normalized".encodeToByteArray())
        return buildString(32) {
            for (i in 0 until 16) {
                val b = hash[i].toInt() and 0xFF
                append(HEX_CHARS[b ushr 4])
                append(HEX_CHARS[b and 0x0F])
            }
        }
    }

    fun deriveRoomTag8(roomCode: String): ByteArray {
        val normalized = GroupInvite.parseRoomCode(roomCode) ?: roomCode.trim().uppercase()
        return sha256("endulink-group-topic-v1:$normalized".encodeToByteArray()).copyOfRange(0, 8)
    }

    fun encryptEnvelope(
        roomCode: String,
        plaintext: ByteArray,
        nonceOverride: ByteArray? = null
    ): ByteArray {
        val normalized = GroupInvite.parseRoomCode(roomCode) ?: roomCode.trim().uppercase()
        val encKey = sha256("endulink-group-enc-v1:$normalized".encodeToByteArray())
        val macKey = sha256("endulink-group-mac-v1:$normalized".encodeToByteArray())
        val nonce = nonceOverride ?: Random.nextBytes(NONCE_SIZE)
        require(nonce.size == NONCE_SIZE) { "Nonce must be 12 bytes" }

        val ciphertext = chacha20Xor(encKey, nonce, 1, plaintext)
        val headerAndCipher = ByteArray(1 + NONCE_SIZE + ciphertext.size)
        headerAndCipher[0] = ENVELOPE_VERSION
        nonce.copyInto(headerAndCipher, destinationOffset = 1)
        ciphertext.copyInto(headerAndCipher, destinationOffset = 1 + NONCE_SIZE)

        val macFull = hmacSha256(macKey, headerAndCipher)
        val out = ByteArray(headerAndCipher.size + TAG_SIZE)
        headerAndCipher.copyInto(out)
        macFull.copyInto(out, destinationOffset = headerAndCipher.size, startIndex = 0, endIndex = TAG_SIZE)
        return out
    }

    fun decryptEnvelope(roomCode: String, envelope: ByteArray): ByteArray? {
        if (envelope.size < 1 + NONCE_SIZE + TAG_SIZE) return null
        if (envelope[0] != ENVELOPE_VERSION) return null

        val normalized = GroupInvite.parseRoomCode(roomCode) ?: roomCode.trim().uppercase()
        val encKey = sha256("endulink-group-enc-v1:$normalized".encodeToByteArray())
        val macKey = sha256("endulink-group-mac-v1:$normalized".encodeToByteArray())

        val bodyLen = envelope.size - TAG_SIZE
        val headerAndCipher = envelope.copyOfRange(0, bodyLen)
        val expectedMac = hmacSha256(macKey, headerAndCipher)

        var diff = 0
        for (i in 0 until TAG_SIZE) {
            diff = diff or ((envelope[bodyLen + i].toInt() and 0xFF) xor (expectedMac[i].toInt() and 0xFF))
        }
        if (diff != 0) return null

        val nonce = envelope.copyOfRange(1, 1 + NONCE_SIZE)
        val ciphertext = envelope.copyOfRange(1 + NONCE_SIZE, bodyLen)
        return chacha20Xor(encKey, nonce, 1, ciphertext)
    }

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotl(16)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotl(12)
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotl(8)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotl(7)
    }

    private fun Int.rotl(bits: Int): Int = (this shl bits) or (this ushr (32 - bits))
    private fun Int.rotr(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))

    private fun readIntLE(buf: ByteArray, offset: Int): Int {
        return (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)
    }

    private val HEX_CHARS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )
}
