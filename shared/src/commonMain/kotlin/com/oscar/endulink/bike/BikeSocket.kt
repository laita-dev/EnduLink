package com.oscar.endulink.bike

interface BikeSocket {
    val remoteIP: String?
    val localIP: String?
    fun readFully(length: Int): ByteArray
    fun writeAll(data: ByteArray)
    fun setReceiveTimeout(ms: Int)
    fun setSendTimeout(ms: Int)
    fun close()
}

interface BikeServerSocket {
    fun acceptClient(): BikeSocket
    fun close()
}

expect object BikeSocketFactory {
    fun createServer(port: Int, backlog: Int = 50): BikeServerSocket
    fun connectTCP(host: String, port: Int, interfaceName: String? = null, timeoutMs: Int = 2000): BikeSocket
}

