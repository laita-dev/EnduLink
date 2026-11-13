package com.oscar.endulink.bike

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

object AndroidSocketBinder {
    @Volatile
    var wifiNetwork: Network? = null
}

class AndroidBikeSocket(private val socket: Socket) : BikeSocket {
    private val inputStream: InputStream = socket.getInputStream()
    private val outputStream: OutputStream = socket.getOutputStream()

    override val remoteIP: String?
        get() = socket.inetAddress?.hostAddress

    override val localIP: String?
        get() = socket.localAddress?.hostAddress

    override fun readFully(length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val bytesRead = try {
                inputStream.read(buffer, offset, length - offset)
            } catch (e: SocketTimeoutException) {
                throw BikeWireException.Timeout("Tiempo de lectura agotado")
            }
            if (bytesRead == -1) {
                throw BikeWireException.Eof()
            }
            offset += bytesRead
        }
        return buffer
    }

    override fun writeAll(data: ByteArray) {
        if (data.isEmpty()) return
        try {
            outputStream.write(data)
            outputStream.flush()
        } catch (e: SocketTimeoutException) {
            throw BikeWireException.Timeout("Tiempo de escritura agotado")
        } catch (e: Exception) {
            throw BikeWireException.Io("Error enviando datos: ${e.message}")
        }
    }

    override fun setReceiveTimeout(ms: Int) {
        socket.soTimeout = ms
    }

    override fun setSendTimeout(ms: Int) {
        // En sockets Java estándar, SO_TIMEOUT aplica a lectura
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {}
    }
}

class AndroidBikeServerSocket(private val serverSocket: ServerSocket) : BikeServerSocket {
    override fun acceptClient(): BikeSocket {
        val client = serverSocket.accept()
        client.tcpNoDelay = true
        client.keepAlive = true
        return AndroidBikeSocket(client)
    }

    override fun close() {
        try {
            serverSocket.close()
        } catch (_: Exception) {}
    }
}

actual object BikeSocketFactory {
    actual fun createServer(port: Int, backlog: Int): BikeServerSocket {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(port), backlog)
        return AndroidBikeServerSocket(server)
    }

    actual fun connectTCP(host: String, port: Int, interfaceName: String?, timeoutMs: Int): BikeSocket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.keepAlive = true

        // En Android, si estamos conectados a un Wi-Fi sin Internet (la pantalla de la moto),
        // atamos el socket TCP saliente a la interfaz Wi-Fi para evitar que el sistema lo desvíe al 4G/5G.
        if (!host.startsWith("127.")) {
            try {
                val targetNetwork = AndroidSocketBinder.wifiNetwork ?: findActiveWifiNetwork()
                targetNetwork?.bindSocket(socket)
            } catch (_: Exception) {}
        }

        socket.connect(InetSocketAddress(host, port), timeoutMs)
        return AndroidBikeSocket(socket)
    }

    @Suppress("DEPRECATION")
    private fun findActiveWifiNetwork(): Network? {
        val ctx = AndroidBikeKeysStorage.appContext ?: return null
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }
}

