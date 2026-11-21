package com.oscar.endulink.group

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.oscar.endulink.bike.AndroidBikeKeysStorage
import com.oscar.endulink.platform.AndroidAppContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import okhttp3.Dns
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

actual fun createGroupHttpClient(): HttpClient {
    return HttpClient(OkHttp) {
        install(WebSockets) {
            pingInterval = 30_000L
        }
        engine {
            config {
                socketFactory(SmartInternetSocketFactory())
                dns(SmartInternetDns())
                connectTimeout(12, TimeUnit.SECONDS)
                readTimeout(0, TimeUnit.MILLISECONDS)
                writeTimeout(12, TimeUnit.SECONDS)
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun findBestInternetNetwork(): Network? {
    val ctx = AndroidAppContext.appContext ?: AndroidBikeKeysStorage.appContext ?: return null
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
    val all = cm.allNetworks

    // 1. Preferir cualquier red con Internet validado (p.ej. 4G/5G cuando el Wi-Fi es el de la moto)
    val validated = all.firstOrNull { net ->
        val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    if (validated != null) return validated

    // 2. Si el Wi-Fi activo es de la moto (sin validación), forzar enlace por red celular si existe
    val cellular = all.firstOrNull { net ->
        val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    return cellular
}

private class SmartInternetSocketFactory : SocketFactory() {
    private val defaultFactory: SocketFactory = getDefault()

    private fun delegate(): SocketFactory {
        return findBestInternetNetwork()?.socketFactory ?: defaultFactory
    }

    override fun createSocket(): Socket = delegate().createSocket()

    override fun createSocket(host: String, port: Int): Socket =
        delegate().createSocket(host, port)

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        delegate().createSocket(host, port, localHost, localPort)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        delegate().createSocket(host, port)

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        delegate().createSocket(address, port, localAddress, localPort)
}

private class SmartInternetDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val net = findBestInternetNetwork()
        if (net != null) {
            try {
                val resolved = net.getAllByName(hostname)
                if (resolved.isNotEmpty()) return resolved.toList()
            } catch (_: Exception) {}
        }
        return Dns.SYSTEM.lookup(hostname)
    }
}
