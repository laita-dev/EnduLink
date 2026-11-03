package com.oscar.endulink.bike

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*

@OptIn(ExperimentalForeignApi::class)
actual class BikeCrypto actual constructor() {

    private val defaults = NSUserDefaults.standardUserDefaults
    private val keyUUID = "motios_uuid"
    private val keyPrivateDER = "motios_rsa_private_pkcs1"
    private val keyPublicSPKI = "motios_rsa_public_spki_b64"

    actual val uuid: String
        get() {
            val saved = defaults.stringForKey(keyUUID)
            if (!saved.isNullOrEmpty()) return saved
            val created = NSUUID().UUIDString.lowercase()
            defaults.setObject(created, forKey = keyUUID)
            return created
        }

    actual val publicKeyBase64: String
        get() {
            ensureKeys()
            return defaults.stringForKey(keyPublicSPKI) ?: ""
        }

    actual fun sign(bikeId: String): String {
        ensureKeys()
        val privData = defaults.dataForKey(keyPrivateDER)
            ?: throw BikeWireException.Io("No hay clave RSA privada generada")

        memScoped {
            val keys = allocArray<COpaquePointerVar>(3)
            val values = allocArray<COpaquePointerVar>(3)

            keys[0] = kSecAttrKeyType
            values[0] = kSecAttrKeyTypeRSA

            keys[1] = kSecAttrKeyClass
            values[1] = kSecAttrKeyClassPrivate

            val bits = alloc<IntVar>()
            bits.value = 1024
            keys[2] = kSecAttrKeySizeInBits
            values[2] = CFNumberCreate(null, kCFNumberIntType, bits.ptr)

            val attrs = CFDictionaryCreate(
                null,
                keys,
                values,
                3,
                null,
                null
            )

            val error = alloc<CFErrorRefVar>()
            val cfPrivData = CFBridgingRetain(privData)!!.reinterpret<cnames.structs.__CFData>()
            val privateKey = SecKeyCreateWithData(
                cfPrivData,
                attrs,
                error.ptr
            )
            CFRelease(cfPrivData)

            if (privateKey == null) {
                throw BikeWireException.Io("SecKeyCreateWithData falló al cargar clave privada")
            }

            val payloadBytes = bikeId.encodeToByteArray()
            val cfPayload = CFDataCreate(null, payloadBytes.usePinned { it.addressOf(0).reinterpret() }, payloadBytes.size.toLong())!!

            val sigData = SecKeyCreateSignature(
                privateKey,
                kSecKeyAlgorithmRSASignatureDigestPKCS1v15Raw,
                cfPayload,
                error.ptr
            )
            CFRelease(cfPayload)

            if (sigData == null) {
                throw BikeWireException.Io("SecKeyCreateSignature falló")
            }

            val nsSigData = CFBridgingRelease(sigData) as NSData
            return nsSigData.base64EncodedStringWithOptions(0u)
        }
    }

    private fun ensureKeys() {
        if (defaults.dataForKey(keyPrivateDER) != null &&
            !defaults.stringForKey(keyPublicSPKI).isNullOrEmpty()
        ) {
            return
        }

        memScoped {
            val keys = allocArray<COpaquePointerVar>(2)
            val values = allocArray<COpaquePointerVar>(2)

            keys[0] = kSecAttrKeyType
            values[0] = kSecAttrKeyTypeRSA

            val bits = alloc<IntVar>()
            bits.value = 1024
            keys[1] = kSecAttrKeySizeInBits
            values[1] = CFNumberCreate(null, kCFNumberIntType, bits.ptr)

            val params = CFDictionaryCreate(
                null,
                keys,
                values,
                2,
                null,
                null
            )

            val error = alloc<CFErrorRefVar>()
            val privateKey = SecKeyCreateRandomKey(params, error.ptr)
                ?: throw BikeWireException.Io("No se pudo crear par RSA-1024 en iOS")

            val publicKey = SecKeyCopyPublicKey(privateKey)
                ?: throw BikeWireException.Io("No se pudo extraer clave pública en iOS")

            val privPKCS1 = CFBridgingRelease(SecKeyCopyExternalRepresentation(privateKey, error.ptr)) as? NSData
                ?: throw BikeWireException.Io("No se pudo exportar clave privada")

            val pubPKCS1 = CFBridgingRelease(SecKeyCopyExternalRepresentation(publicKey, error.ptr)) as? NSData
                ?: throw BikeWireException.Io("No se pudo exportar clave pública")

            val pkcs1Bytes = ByteArray(pubPKCS1.length.toInt())
            if (pkcs1Bytes.isNotEmpty()) {
                pkcs1Bytes.usePinned { pinned ->
                    platform.posix.memcpy(pinned.addressOf(0), pubPKCS1.bytes, pubPKCS1.length)
                }
            }

            val spkiBytes = wrapPKCS1InX509SPKI(pkcs1Bytes)
            val spkiData = spkiBytes.usePinned { pinned ->
                NSData.dataWithBytes(pinned.addressOf(0), spkiBytes.size.toULong())
            }

            val spkiBase64 = spkiData.base64EncodedStringWithOptions(0u)

            defaults.setObject(privPKCS1, forKey = keyPrivateDER)
            defaults.setObject(spkiBase64, forKey = keyPublicSPKI)
        }
    }

    private fun wrapPKCS1InX509SPKI(pkcs1: ByteArray): ByteArray {
        val algorithmIdentifier = byteArrayOf(
            0x30.toByte(), 0x0D.toByte(),
            0x06.toByte(), 0x09.toByte(), 0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0x86.toByte(), 0xF7.toByte(), 0x0D.toByte(), 0x01.toByte(), 0x01.toByte(), 0x01.toByte(),
            0x05.toByte(), 0x00.toByte()
        )

        val bitStringPayload = ByteArray(1 + pkcs1.size)
        bitStringPayload[0] = 0x00.toByte()
        pkcs1.copyInto(bitStringPayload, destinationOffset = 1)

        val bitStringLen = derLength(bitStringPayload.size)
        val bitString = ByteArray(1 + bitStringLen.size + bitStringPayload.size)
        bitString[0] = 0x03.toByte()
        bitStringLen.copyInto(bitString, destinationOffset = 1)
        bitStringPayload.copyInto(bitString, destinationOffset = 1 + bitStringLen.size)

        val seqBodyLen = algorithmIdentifier.size + bitString.size
        val seqHeaderLen = derLength(seqBodyLen)

        val result = ByteArray(1 + seqHeaderLen.size + seqBodyLen)
        result[0] = 0x30.toByte()
        seqHeaderLen.copyInto(result, destinationOffset = 1)
        algorithmIdentifier.copyInto(result, destinationOffset = 1 + seqHeaderLen.size)
        bitString.copyInto(result, destinationOffset = 1 + seqHeaderLen.size + algorithmIdentifier.size)

        return result
    }

    private fun derLength(length: Int): ByteArray {
        return when {
            length < 0x80 -> byteArrayOf(length.toByte())
            length <= 0xFF -> byteArrayOf(0x81.toByte(), length.toByte())
            else -> byteArrayOf(0x82.toByte(), ((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte())
        }
    }
}

