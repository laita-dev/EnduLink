package com.oscar.endulink.group

import android.content.Context
import android.net.wifi.WifiManager
import com.oscar.endulink.bike.AndroidBikeKeysStorage
import com.oscar.endulink.platform.AndroidAppContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

actual class LocalLanUdpSocket actual constructor() {
    @Volatile
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    actual fun open(port: Int): Boolean {
        close()
        return try {
            acquireMulticastLock()
            val ds = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(port))
            }
            socket = ds
            true
        } catch (_: Exception) {
            close()
            false
        }
    }

    actual fun sendBroadcast(data: ByteArray, port: Int): Boolean {
        val ds = socket ?: return false
        val targets = collectBroadcastAddresses()
        if (targets.isEmpty()) return false
        var sentAny = false
        for (addr in targets) {
            try {
                val packet = DatagramPacket(data, data.size, addr, port)
                ds.send(packet)
                sentAny = true
            } catch (_: Exception) {}
        }
        return sentAny
    }

    actual fun receive(maxBytes: Int, timeoutMs: Int): LanDatagram? {
        val ds = socket ?: return null
        return try {
            ds.soTimeout = timeoutMs
            val buf = ByteArray(maxBytes)
            val packet = DatagramPacket(buf, buf.size)
            ds.receive(packet)
            val senderIp = packet.address?.hostAddress.orEmpty()
            LanDatagram(
                data = buf.copyOf(packet.length),
                senderIp = senderIp
            )
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    actual fun getLocalLanIps(): Set<String> {
        val ips = LinkedHashSet<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ips
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        addr.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        } catch (_: Exception) {}
        return ips
    }

    actual fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        releaseMulticastLock()
    }

    private fun collectBroadcastAddresses(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (iface in interfaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    val name = iface.name.lowercase()
                    if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun")) {
                        continue
                    }
                    for (ifAddr in iface.interfaceAddresses) {
                        val addr = ifAddr.address
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            ifAddr.broadcast?.let { out.add(it) }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        if (out.isEmpty()) {
            try {
                out.add(InetAddress.getByName("255.255.255.255"))
            } catch (_: Exception) {}
        }
        return out.toList()
    }

    private fun acquireMulticastLock() {
        try {
            val ctx = AndroidAppContext.appContext ?: AndroidBikeKeysStorage.appContext ?: return
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val lock = wm.createMulticastLock("EnduLinkGroupLan").apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
        } catch (_: Exception) {}
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }
}
