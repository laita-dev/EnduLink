package com.oscar.endulink.bike

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher

object AndroidBikeKeysStorage {
    var appContext: Context? = null

    private fun getStorageDir(): File {
        val dir = appContext?.filesDir ?: File(System.getProperty("user.home", ".") + "/.endulink")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getFile(name: String): File = File(getStorageDir(), name)
}

actual class BikeCrypto actual constructor() {

    private val lock = Any()
    private var cachedUuid: String? = null
    private var cachedPublicKeyBase64: String? = null
    private var cachedPrivateKey: PrivateKey? = null

    actual val uuid: String
        get() = synchronized(lock) {
            cachedUuid?.let { return it }
            val uuidFile = AndroidBikeKeysStorage.getFile("motios_uuid.txt")
            if (uuidFile.exists()) {
                val saved = uuidFile.readText().trim()
                if (saved.isNotEmpty()) {
                    cachedUuid = saved
                    return saved
                }
            }
            val newUuid = UUID.randomUUID().toString().lowercase()
            uuidFile.writeText(newUuid)
            cachedUuid = newUuid
            newUuid
        }

    actual val publicKeyBase64: String
        get() = synchronized(lock) {
            cachedPublicKeyBase64?.let { return it }
            ensureKeys()
            cachedPublicKeyBase64 ?: ""
        }

    actual fun sign(bikeId: String): String {
        val privateKey = synchronized(lock) {
            ensureKeys()
            cachedPrivateKey ?: throw BikeWireException.Io("No hay clave RSA privada disponible")
        }

        // RSA/ECB/PKCS1Padding con clave privada en Java produce exactamente el bloque PKCS#1 v1.5 crudo
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        val signedBytes = cipher.doFinal(bikeId.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signedBytes, Base64.NO_WRAP)
    }

    private fun ensureKeys() {
        if (cachedPublicKeyBase64 != null && cachedPrivateKey != null) return

        val pubFile = AndroidBikeKeysStorage.getFile("motios_rsa_public.b64")
        val privFile = AndroidBikeKeysStorage.getFile("motios_rsa_private.pk8")

        if (pubFile.exists() && privFile.exists()) {
            try {
                val pubB64 = pubFile.readText().trim()
                val privBytes = privFile.readBytes()
                val keyFactory = KeyFactory.getInstance("RSA")
                val privKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                cachedPublicKeyBase64 = pubB64
                cachedPrivateKey = privKey
                return
            } catch (e: Exception) {
                // Si están corruptas, regenerar
            }
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(1024)
        val keyPair: KeyPair = kpg.generateKeyPair()

        val pubSpkiB64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val privPkcs8Bytes = keyPair.private.encoded

        pubFile.writeText(pubSpkiB64)
        privFile.writeBytes(privPkcs8Bytes)

        cachedPublicKeyBase64 = pubSpkiB64
        cachedPrivateKey = keyPair.private
    }
}

