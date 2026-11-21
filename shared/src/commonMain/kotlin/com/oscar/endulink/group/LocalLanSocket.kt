package com.oscar.endulink.group

data class LanDatagram(
    val data: ByteArray,
    val senderIp: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LanDatagram) return false
        return senderIp == other.senderIp && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + senderIp.hashCode()
        return result
    }
}

/**
 * Socket UDP broadcast multiplataforma para sincronización local sin Internet
 * mediante punto de acceso compartido o red Wi-Fi común.
 */
expect class LocalLanUdpSocket() {
    fun open(port: Int): Boolean
    fun sendBroadcast(data: ByteArray, port: Int): Boolean
    fun receive(maxBytes: Int, timeoutMs: Int): LanDatagram?
    fun getLocalLanIps(): Set<String>
    fun close()
}
