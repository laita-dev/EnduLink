package com.oscar.endulink.group

import kotlin.concurrent.Volatile
import kotlinx.cinterop.*
import platform.posix.*

interface IosLanNetworkBridge {
    fun localLanIpsCsv(): String
    fun broadcastIpsCsv(): String
}

object IosLanNetworkBridgeHolder {
    @Volatile
    var bridge: IosLanNetworkBridge? = null
}

private fun htonsUdp(value: UShort): UShort {
    val v = value.toInt()
    return (((v and 0xFF) shl 8) or ((v ushr 8) and 0xFF)).toUShort()
}

private fun ipv4UIntToString(ipHostOrNetOrder: UInt): String {
    val b1 = (ipHostOrNetOrder and 0xFFu).toUInt()
    val b2 = ((ipHostOrNetOrder shr 8) and 0xFFu).toUInt()
    val b3 = ((ipHostOrNetOrder shr 16) and 0xFFu).toUInt()
    val b4 = ((ipHostOrNetOrder shr 24) and 0xFFu).toUInt()
    return "$b1.$b2.$b3.$b4"
}

@OptIn(ExperimentalForeignApi::class)
actual class LocalLanUdpSocket actual constructor() {
    @Volatile
    private var fd: Int = -1

    actual fun open(port: Int): Boolean {
        close()
        val sock = socket(AF_INET, SOCK_DGRAM, 0)
        if (sock < 0) return false

        memScoped {
            val one = alloc<IntVar>()
            one.value = 1
            setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().toUInt())
            setsockopt(sock, SOL_SOCKET, SO_REUSEPORT, one.ptr, sizeOf<IntVar>().toUInt())
            setsockopt(sock, SOL_SOCKET, SO_BROADCAST, one.ptr, sizeOf<IntVar>().toUInt())
            setsockopt(sock, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().toUInt())

            val addr = alloc<sockaddr_in>()
            addr.sin_len = sizeOf<sockaddr_in>().toUByte()
            addr.sin_family = AF_INET.toUByte()
            addr.sin_port = htonsUdp(port.toUShort())
            addr.sin_addr.s_addr = INADDR_ANY

            if (bind(sock, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt()) != 0) {
                platform.posix.close(sock)
                return false
            }
        }
        fd = sock
        return true
    }

    actual fun sendBroadcast(data: ByteArray, port: Int): Boolean {
        val currentFd = fd
        if (currentFd < 0 || data.isEmpty()) return false
        val targets = collectBroadcastIps()
        if (targets.isEmpty()) return false

        var sentAny = false
        data.usePinned { pinned ->
            val bufPtr = pinned.addressOf(0)
            memScoped {
                val addr = alloc<sockaddr_in>()
                for (ip in targets) {
                    addr.sin_len = sizeOf<sockaddr_in>().toUByte()
                    addr.sin_family = AF_INET.toUByte()
                    addr.sin_port = htonsUdp(port.toUShort())
                    if (inet_pton(AF_INET, ip, addr.sin_addr.ptr) == 1) {
                        val n = sendto(
                            currentFd,
                            bufPtr,
                            data.size.toULong(),
                            0,
                            addr.ptr.reinterpret(),
                            sizeOf<sockaddr_in>().toUInt()
                        )
                        if (n > 0) sentAny = true
                    }
                }
            }
        }
        return sentAny
    }

    actual fun receive(maxBytes: Int, timeoutMs: Int): LanDatagram? {
        val currentFd = fd
        if (currentFd < 0 || maxBytes <= 0) return null

        val buf = ByteArray(maxBytes)
        memScoped {
            val tv = alloc<timeval>()
            tv.tv_sec = (timeoutMs / 1000).toLong()
            tv.tv_usec = ((timeoutMs % 1000) * 1000)
            setsockopt(currentFd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().toUInt())

            val senderAddr = alloc<sockaddr_in>()
            val senderLen = alloc<socklen_tVar>()
            senderLen.value = sizeOf<sockaddr_in>().toUInt()

            val n = buf.usePinned { pinned ->
                recvfrom(
                    currentFd,
                    pinned.addressOf(0),
                    maxBytes.toULong(),
                    0,
                    senderAddr.ptr.reinterpret(),
                    senderLen.ptr
                )
            }
            if (n <= 0) return null
            val senderIp = ipv4UIntToString(senderAddr.sin_addr.s_addr)
            return LanDatagram(
                data = buf.copyOf(n.toInt()),
                senderIp = senderIp
            )
        }
    }

    actual fun getLocalLanIps(): Set<String> {
        val csv = IosLanNetworkBridgeHolder.bridge?.localLanIpsCsv().orEmpty()
        if (csv.isBlank()) return emptySet()
        return csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    actual fun close() {
        val currentFd = fd
        fd = -1
        if (currentFd >= 0) {
            shutdown(currentFd, SHUT_RDWR)
            platform.posix.close(currentFd)
        }
    }

    private fun collectBroadcastIps(): List<String> {
        val csv = IosLanNetworkBridgeHolder.bridge?.broadcastIpsCsv().orEmpty()
        val parsed = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (parsed.isNotEmpty()) parsed else listOf("255.255.255.255")
    }
}
