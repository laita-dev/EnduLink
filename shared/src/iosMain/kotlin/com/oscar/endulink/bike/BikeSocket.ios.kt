package com.oscar.endulink.bike

import kotlinx.cinterop.*
import platform.posix.*
import platform.darwin.*

private fun htons(value: UShort): UShort {
    val v = value.toInt()
    return (((v and 0xFF) shl 8) or ((v ushr 8) and 0xFF)).toUShort()
}

@OptIn(ExperimentalForeignApi::class)
class IosBikeSocket(
    private var fd: Int,
    override val remoteIP: String? = null,
    override val localIP: String? = null
) : BikeSocket {

    init {
        disableSigPipe(fd)
    }

    override fun readFully(length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        val buffer = ByteArray(length)
        var offset = 0
        buffer.usePinned { pinned ->
            val basePtr = pinned.addressOf(0)
            while (offset < length) {
                val currentFd = fd
                if (currentFd < 0) throw BikeWireException.Closed()

                val n = recv(currentFd, basePtr + offset, (length - offset).toULong(), 0)
                if (n > 0) {
                    offset += n.toInt()
                } else if (n == 0L) {
                    throw BikeWireException.Eof()
                } else {
                    val err = errno
                    if (err == EINTR) continue
                    if (err == EAGAIN || err == EWOULDBLOCK) {
                        throw BikeWireException.Timeout("Tiempo de lectura agotado")
                    }
                    throw BikeWireException.Io("recv falló: $err")
                }
            }
        }
        return buffer
    }

    override fun writeAll(data: ByteArray) {
        if (data.isEmpty()) return
        var offset = 0
        val total = data.size
        data.usePinned { pinned ->
            val basePtr = pinned.addressOf(0)
            while (offset < total) {
                val currentFd = fd
                if (currentFd < 0) throw BikeWireException.Closed()

                val n = send(currentFd, basePtr + offset, (total - offset).toULong(), 0)
                if (n > 0) {
                    offset += n.toInt()
                } else {
                    val err = errno
                    if (err == EINTR) continue
                    if (err == EAGAIN || err == EWOULDBLOCK) {
                        throw BikeWireException.Timeout("Tiempo de escritura agotado")
                    }
                    throw BikeWireException.Io("send falló: $err")
                }
            }
        }
    }

    override fun setReceiveTimeout(ms: Int) {
        if (fd < 0) return
        memScoped {
            val tv = alloc<timeval>()
            tv.tv_sec = (ms / 1000).toLong()
            tv.tv_usec = ((ms % 1000) * 1000)
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().toUInt())
        }
    }

    override fun setSendTimeout(ms: Int) {
        if (fd < 0) return
        memScoped {
            val tv = alloc<timeval>()
            tv.tv_sec = (ms / 1000).toLong()
            tv.tv_usec = ((ms % 1000) * 1000)
            setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, tv.ptr, sizeOf<timeval>().toUInt())
        }
    }

    override fun close() {
        val currentFd = fd
        fd = -1
        if (currentFd >= 0) {
            shutdown(currentFd, SHUT_RDWR)
            platform.posix.close(currentFd)
        }
    }

    companion object {
        fun disableSigPipe(sock: Int) {
            if (sock < 0) return
            memScoped {
                val one = alloc<IntVar>()
                one.value = 1
                setsockopt(sock, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().toUInt())
                setsockopt(sock, SOL_SOCKET, SO_KEEPALIVE, one.ptr, sizeOf<IntVar>().toUInt())
                setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, one.ptr, sizeOf<IntVar>().toUInt())
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
class IosBikeServerSocket(private var serverFd: Int) : BikeServerSocket {
    override fun acceptClient(): BikeSocket {
        memScoped {
            val clientAddr = alloc<sockaddr_in>()
            val addrLen = alloc<socklen_tVar>()
            addrLen.value = sizeOf<sockaddr_in>().toUInt()

            val clientFd = accept(serverFd, clientAddr.ptr.reinterpret(), addrLen.ptr)
            if (clientFd < 0) {
                throw BikeWireException.Io("accept falló en iOS")
            }

            IosBikeSocket.disableSigPipe(clientFd)

            val ipInt = clientAddr.sin_addr.s_addr
            val b1 = (ipInt and 0xFFu).toUInt()
            val b2 = ((ipInt shr 8) and 0xFFu).toUInt()
            val b3 = ((ipInt shr 16) and 0xFFu).toUInt()
            val b4 = ((ipInt shr 24) and 0xFFu).toUInt()
            val remoteIp = "$b1.$b2.$b3.$b4"

            return IosBikeSocket(clientFd, remoteIP = remoteIp)
        }
    }

    override fun close() {
        val current = serverFd
        serverFd = -1
        if (current >= 0) {
            shutdown(current, SHUT_RDWR)
            platform.posix.close(current)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual object BikeSocketFactory {
    actual fun createServer(port: Int, backlog: Int): BikeServerSocket {
        val sock = socket(AF_INET, SOCK_STREAM, 0)
        if (sock < 0) throw BikeWireException.Io("No se pudo crear socket servidor")

        IosBikeSocket.disableSigPipe(sock)

        memScoped {
            val reuse = alloc<IntVar>()
            reuse.value = 1
            setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, reuse.ptr, sizeOf<IntVar>().toUInt())
            setsockopt(sock, SOL_SOCKET, SO_REUSEPORT, reuse.ptr, sizeOf<IntVar>().toUInt())

            val addr = alloc<sockaddr_in>()
            addr.sin_len = sizeOf<sockaddr_in>().toUByte()
            addr.sin_family = AF_INET.toUByte()
            addr.sin_port = htons(port.toUShort())
            addr.sin_addr.s_addr = INADDR_ANY

            if (bind(sock, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt()) != 0) {
                platform.posix.close(sock)
                throw BikeWireException.Io("Puerto $port ocupado en iOS")
            }

            if (listen(sock, backlog) != 0) {
                platform.posix.close(sock)
                throw BikeWireException.Io("listen falló en puerto $port")
            }

            return IosBikeServerSocket(sock)
        }
    }

    actual fun connectTCP(host: String, port: Int, interfaceName: String?, timeoutMs: Int): BikeSocket {
        val sock = socket(AF_INET, SOCK_STREAM, 0)
        if (sock < 0) throw BikeWireException.Io("No se pudo crear socket TCP")

        IosBikeSocket.disableSigPipe(sock)

        // Atar socket a interfaz Wi-Fi (ej. "en0") para mantener 4G/5G
        if (!interfaceName.isNullOrEmpty()) {
            val ifIndex = if_nametoindex(interfaceName)
            if (ifIndex != 0u) {
                memScoped {
                    val idxVar = alloc<UIntVar>()
                    idxVar.value = ifIndex
                    setsockopt(sock, IPPROTO_IP, IP_BOUND_IF, idxVar.ptr, sizeOf<UIntVar>().toUInt())
                }
            }
        }

        memScoped {
            val addr = alloc<sockaddr_in>()
            addr.sin_len = sizeOf<sockaddr_in>().toUByte()
            addr.sin_family = AF_INET.toUByte()
            addr.sin_port = htons(port.toUShort())
            if (inet_pton(AF_INET, host, addr.sin_addr.ptr) != 1) {
                platform.posix.close(sock)
                throw BikeWireException.Io("IP inválida: $host")
            }

            // Non-blocking connect con timeout de poll
            val flags = fcntl(sock, F_GETFL, 0)
            fcntl(sock, F_SETFL, flags or O_NONBLOCK)

            val res = connect(sock, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt())
            if (res != 0 && errno != EINPROGRESS) {
                platform.posix.close(sock)
                throw BikeWireException.Io("connect falló a $host:$port")
            }

            if (res != 0) {
                val pfd = alloc<pollfd>()
                pfd.fd = sock
                pfd.events = POLLOUT.toShort()
                val pollRes = poll(pfd.ptr, 1u, timeoutMs)
                if (pollRes <= 0) {
                    platform.posix.close(sock)
                    throw BikeWireException.Timeout("Tiempo agotado conectando a $host:$port")
                }
            }

            fcntl(sock, F_SETFL, flags)
            val wrapped = IosBikeSocket(sock, remoteIP = host)
            wrapped.setReceiveTimeout(timeoutMs)
            wrapped.setSendTimeout(timeoutMs)
            return wrapped
        }
    }
}

