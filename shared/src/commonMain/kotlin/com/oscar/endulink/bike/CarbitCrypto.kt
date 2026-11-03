package com.oscar.endulink.bike

/**
 * Identidad persistente del móvil frente a la moto (UUID + par RSA-1024 compatible X.509 / PKCS#1 v1.5).
 * Produce exactamente el mismo `pubkey` (SubjectPublicKeyInfo DER en Base64) y `encryptedHUID`
 * (`RSA/ECB/PKCS1Padding` con clave privada = bloque PKCS#1 tipo 1 sin DigestInfo) que en MotIOS / Android.
 */
expect class BikeCrypto() {
    val uuid: String
    val publicKeyBase64: String
    fun sign(bikeId: String): String
}

