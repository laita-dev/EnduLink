package com.oscar.endulink.group

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GroupCryptoTest {

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "").replace(":", "").lowercase()
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun testSha256FipsVector() {
        val hash = GroupCrypto.sha256("abc".encodeToByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            bytesToHex(hash)
        )
    }

    @Test
    fun testHmacSha256Rfc4231Vector() {
        val mac = GroupCrypto.hmacSha256(
            key = "Jefe".encodeToByteArray(),
            data = "what do ya want for nothing?".encodeToByteArray()
        )
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            bytesToHex(mac)
        )
    }

    @Test
    fun testChaCha20Rfc8439Vector() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = hexToBytes("000000000000004a00000000")
        val plaintext = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
            .encodeToByteArray()
        val ciphertext = GroupCrypto.chacha20Xor(key, nonce, counter = 1, input = plaintext)

        assertEquals(
            "6e2e359a2568f98041ba0728dd0d6981",
            bytesToHex(ciphertext.copyOfRange(0, 16))
        )
        val roundTrip = GroupCrypto.chacha20Xor(key, nonce, counter = 1, input = ciphertext)
        assertContentEquals(plaintext, roundTrip)
    }

    @Test
    fun testEnvelopeEncryptDecryptAndTamperRejection() {
        val room = "K7M-9P2"
        val secret = """{"id":"m_1","la":40.4168,"lo":-3.7038}""".encodeToByteArray()
        val sealed = GroupCrypto.encryptEnvelope(room, secret)

        val opened = GroupCrypto.decryptEnvelope("k7m9p2", sealed)
        assertNotNull(opened)
        assertContentEquals(secret, opened)

        // Sala distinta no descifra
        assertNull(GroupCrypto.decryptEnvelope("WRONG9", sealed))

        // Bit modificado en ciphertext
        val tamperedBody = sealed.copyOf()
        tamperedBody[15] = (tamperedBody[15].toInt() xor 0x01).toByte()
        assertNull(GroupCrypto.decryptEnvelope(room, tamperedBody))

        // Bit modificado en HMAC tag
        val tamperedMac = sealed.copyOf()
        tamperedMac[tamperedMac.lastIndex] = (tamperedMac.last().toInt() xor 0x80).toByte()
        assertNull(GroupCrypto.decryptEnvelope(room, tamperedMac))
    }
}
