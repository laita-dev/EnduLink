package com.oscar.endulink.group

import com.oscar.endulink.bike.BikeServerSocket
import com.oscar.endulink.bike.BikeSocketFactory
import com.oscar.endulink.bike.ByteArrayOutput
import com.oscar.endulink.bike.readInt32LE
import com.oscar.endulink.platform.SynchronizedLock
import com.oscar.endulink.platform.withLock
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile
import kotlin.random.Random

internal object GroupTransportConstants {
    val DEFAULT_MQTT_BROKERS = listOf(
        "wss://broker.emqx.io:8084/mqtt",
        "wss://broker.hivemq.com:8884/mqtt"
    )
    const val LAN_UDP_PORT = 19847
    const val LAN_GPX_TCP_PORT = 19848
    val LAN_MAGIC = byteArrayOf('E'.code.toByte(), 'L'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte())
}

internal interface GroupTransport {
    fun start(roomCode: String, memberId: String)
    fun publishPosition(payload: WirePositionPayload)
    fun publishSharedTrack(track: WireSharedTrackPayload)
    suspend fun leaveAndStop(roomCode: String, selfId: String)
    fun stop()
}

private val wireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

internal class MqttWebSocketTransport(
    private val customBrokerUrlProvider: () -> String,
    private val onConnectionChanged: (Boolean) -> Unit,
    private val onConnectedReady: () -> Unit,
    private val onPositionReceived: (WirePositionPayload, GroupTransportSource) -> Unit,
    private val onMemberLeft: (String) -> Unit,
    private val onTrackReceived: (WireSharedTrackPayload) -> Unit
) : GroupTransport {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var runJob: Job? = null
    private var outgoingChannel = Channel<MqttOutgoing>(capacity = 32)
    private var currentClient: HttpClient? = null

    @Volatile
    private var activeRoomCode: String? = null
    @Volatile
    private var activeTopicPrefix: String = ""
    @Volatile
    private var activeSelfId: String = ""

    private data class MqttOutgoing(val topic: String, val payload: ByteArray, val retain: Boolean)

    override fun start(roomCode: String, memberId: String) {
        stop()
        val topicHash = GroupCrypto.deriveTopicHash(roomCode)
        activeRoomCode = roomCode
        activeTopicPrefix = "endulink/g1/$topicHash"
        activeSelfId = memberId
        outgoingChannel = Channel(capacity = 32)

        runJob = scope.launch {
            val backoffMs = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L)
            var attempt = 0

            while (isActive && activeRoomCode == roomCode) {
                val brokers = resolveBrokerList()
                val brokerUrl = brokers[attempt % brokers.size]
                val client = createGroupHttpClient()
                currentClient = client
                var wasConnected = false

                try {
                    client.webSocket(
                        urlString = brokerUrl,
                        request = {
                            header("Sec-WebSocket-Protocol", "mqtt")
                        }
                    ) {
                        val decoder = MqttStreamDecoder()
                        val clientId = "el_${memberId.take(12)}_${Random.nextInt(1000, 9999)}"
                        send(Frame.Binary(true, MqttCodec.encodeConnect(clientId, keepAliveSeconds = 60)))

                        var connAckReceived = false
                        val startWaitMs = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                        while (!connAckReceived && isActive) {
                            val frame = incoming.receiveCatching().getOrNull() ?: break
                            if (frame is Frame.Binary || frame is Frame.Text) {
                                for (pkt in decoder.feed(frame.readBytes())) {
                                    if (pkt is MqttPacket.ConnAck) {
                                        if (pkt.returnCode == 0) {
                                            connAckReceived = true
                                        } else {
                                            throw IllegalStateException("MQTT CONNACK code ${pkt.returnCode}")
                                        }
                                    }
                                }
                            } else if (frame is Frame.Close) {
                                break
                            }
                            if (kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - startWaitMs > 10_000L) {
                                break
                            }
                        }

                        if (!connAckReceived) {
                            throw IllegalStateException("MQTT CONNACK timeout")
                        }

                        send(Frame.Binary(true, MqttCodec.encodeSubscribe(1, "$activeTopicPrefix/#")))
                        wasConnected = true
                        attempt = 0
                        onConnectionChanged(true)
                        onConnectedReady()

                        val writerJob = launch {
                            for (msg in outgoingChannel) {
                                val encoded = MqttCodec.encodePublish(msg.topic, msg.payload, msg.retain)
                                send(Frame.Binary(true, encoded))
                            }
                        }
                        val pingJob = launch {
                            while (isActive) {
                                delay(35_000L)
                                send(Frame.Binary(true, MqttCodec.encodePingReq()))
                            }
                        }

                        try {
                            for (frame in incoming) {
                                when (frame) {
                                    is Frame.Binary, is Frame.Text -> {
                                        for (pkt in decoder.feed(frame.readBytes())) {
                                            if (pkt is MqttPacket.Publish) {
                                                handleIncomingPublish(roomCode, pkt.topic, pkt.payload)
                                            }
                                        }
                                    }
                                    is Frame.Close -> break
                                    else -> Unit
                                }
                            }
                        } finally {
                            writerJob.cancel()
                            pingJob.cancel()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                } finally {
                    onConnectionChanged(false)
                    runCatching { client.close() }
                    if (currentClient === client) currentClient = null
                }

                if (!isActive || activeRoomCode != roomCode) break
                val delayTime = if (wasConnected) 2_000L else backoffMs[minOf(attempt, backoffMs.lastIndex)]
                attempt++
                delay(delayTime)
            }
        }
    }

    override fun publishPosition(payload: WirePositionPayload) {
        val room = activeRoomCode ?: return
        val prefix = activeTopicPrefix
        if (prefix.isEmpty()) return
        val jsonBytes = wireJson.encodeToString(payload).encodeToByteArray()
        val encrypted = GroupCrypto.encryptEnvelope(room, jsonBytes)
        outgoingChannel.trySend(
            MqttOutgoing(
                topic = "$prefix/m/${payload.id}",
                payload = encrypted,
                retain = true
            )
        )
    }

    override fun publishSharedTrack(track: WireSharedTrackPayload) {
        val room = activeRoomCode ?: return
        val prefix = activeTopicPrefix
        if (prefix.isEmpty()) return
        val jsonBytes = wireJson.encodeToString(track).encodeToByteArray()
        val encrypted = GroupCrypto.encryptEnvelope(room, jsonBytes)
        outgoingChannel.trySend(
            MqttOutgoing(
                topic = "$prefix/gpx",
                payload = encrypted,
                retain = true
            )
        )
    }

    override suspend fun leaveAndStop(roomCode: String, selfId: String) {
        val prefix = activeTopicPrefix
        if (activeRoomCode == roomCode && prefix.isNotEmpty()) {
            val leavePayload = WirePositionPayload(
                id = selfId,
                n = "",
                c = RiderColor.CYAN.raw,
                la = 0.0,
                lo = 0.0,
                st = RiderStatus.LEFT.raw,
                sq = Long.MAX_VALUE
            )
            val encrypted = GroupCrypto.encryptEnvelope(
                roomCode,
                wireJson.encodeToString(leavePayload).encodeToByteArray()
            )
            outgoingChannel.trySend(MqttOutgoing("$prefix/m/$selfId", encrypted, retain = false))
            // Vaciar mensaje retenido en el broker
            outgoingChannel.trySend(MqttOutgoing("$prefix/m/$selfId", ByteArray(0), retain = true))
            delay(250L)
        }
        stop()
    }

    override fun stop() {
        activeRoomCode = null
        activeTopicPrefix = ""
        onConnectionChanged(false)
        runJob?.cancel()
        runJob = null
        runCatching { currentClient?.close() }
        currentClient = null
        outgoingChannel.close()
    }

    private fun resolveBrokerList(): List<String> {
        val custom = customBrokerUrlProvider().trim()
        return if (custom.startsWith("ws://", ignoreCase = true) || custom.startsWith("wss://", ignoreCase = true)) {
            listOf(custom) + GroupTransportConstants.DEFAULT_MQTT_BROKERS
        } else {
            GroupTransportConstants.DEFAULT_MQTT_BROKERS
        }
    }

    private fun handleIncomingPublish(roomCode: String, topic: String, payload: ByteArray) {
        val prefix = activeTopicPrefix
        if (prefix.isEmpty() || !topic.startsWith(prefix)) return
        val suffix = topic.removePrefix(prefix)

        if (suffix.startsWith("/m/")) {
            val senderId = suffix.removePrefix("/m/")
            if (senderId == activeSelfId) return
            if (payload.isEmpty()) {
                onMemberLeft(senderId)
                return
            }
            val plain = GroupCrypto.decryptEnvelope(roomCode, payload) ?: return
            val parsed = runCatching {
                wireJson.decodeFromString<WirePositionPayload>(plain.decodeToString())
            }.getOrNull() ?: return
            onPositionReceived(parsed, GroupTransportSource.ONLINE_MQTT)
        } else if (suffix == "/gpx" && payload.isNotEmpty()) {
            val plain = GroupCrypto.decryptEnvelope(roomCode, payload) ?: return
            val parsed = runCatching {
                wireJson.decodeFromString<WireSharedTrackPayload>(plain.decodeToString())
            }.getOrNull() ?: return
            onTrackReceived(parsed)
        }
    }
}

internal class LocalLanUdpTransport(
    private val gossipPeersProvider: () -> List<WirePeerGossip>,
    private val onStatusChanged: (Boolean) -> Unit,
    private val onPositionReceived: (WirePositionPayload, GroupTransportSource) -> Unit,
    private val onTrackReceived: (WireSharedTrackPayload) -> Unit
) : GroupTransport {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var rxJob: Job? = null
    private var gpxServerJob: Job? = null
    private var udpSocket: LocalLanUdpSocket? = null
    private var gpxServerSocket: BikeServerSocket? = null

    @Volatile
    private var activeRoomCode: String? = null
    @Volatile
    private var activeRoomTag8: ByteArray = ByteArray(0)
    @Volatile
    private var activeSelfId: String = ""
    @Volatile
    private var hostedTrackId: String? = null
    @Volatile
    private var hostedEncryptedTrack: ByteArray? = null

    private val fetchedLock = SynchronizedLock()
    private val fetchedTrackIds = mutableSetOf<String>()

    override fun start(roomCode: String, memberId: String) {
        stop()
        activeRoomCode = roomCode
        activeRoomTag8 = GroupCrypto.deriveRoomTag8(roomCode)
        activeSelfId = memberId
        hostedTrackId = null
        hostedEncryptedTrack = null
        fetchedLock.withLock { fetchedTrackIds.clear() }

        rxJob = scope.launch {
            while (isActive && activeRoomCode == roomCode) {
                val sock = LocalLanUdpSocket()
                if (!sock.open(GroupTransportConstants.LAN_UDP_PORT)) {
                    onStatusChanged(false)
                    delay(5_000L)
                    continue
                }
                udpSocket = sock
                onStatusChanged(true)
                try {
                    while (isActive && activeRoomCode == roomCode) {
                        val dg = sock.receive(maxBytes = 4096, timeoutMs = 1000) ?: continue
                        handleDatagram(roomCode, dg, sock.getLocalLanIps())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                } finally {
                    onStatusChanged(false)
                    sock.close()
                    if (udpSocket === sock) udpSocket = null
                }
                if (isActive && activeRoomCode == roomCode) {
                    delay(3_000L)
                }
            }
        }
    }

    override fun publishPosition(payload: WirePositionPayload) {
        val room = activeRoomCode ?: return
        val sock = udpSocket ?: return
        val tag8 = activeRoomTag8
        if (tag8.size != 8) return

        val peers = gossipPeersProvider().take(8)
        val enriched = payload.copy(
            gpxId = hostedTrackId,
            gpxPort = if (hostedTrackId != null) GroupTransportConstants.LAN_GPX_TCP_PORT else null,
            peers = peers
        )
        val jsonBytes = wireJson.encodeToString(enriched).encodeToByteArray()
        val encrypted = GroupCrypto.encryptEnvelope(room, jsonBytes)
        val packet = ByteArray(4 + 8 + encrypted.size)
        GroupTransportConstants.LAN_MAGIC.copyInto(packet, destinationOffset = 0)
        tag8.copyInto(packet, destinationOffset = 4)
        encrypted.copyInto(packet, destinationOffset = 12)

        scope.launch {
            sock.sendBroadcast(packet, GroupTransportConstants.LAN_UDP_PORT)
        }
    }

    override fun publishSharedTrack(track: WireSharedTrackPayload) {
        val room = activeRoomCode ?: return
        val jsonBytes = wireJson.encodeToString(track).encodeToByteArray()
        hostedEncryptedTrack = GroupCrypto.encryptEnvelope(room, jsonBytes)
        hostedTrackId = track.id
        ensureGpxServerStarted()
    }

    override suspend fun leaveAndStop(roomCode: String, selfId: String) {
        if (activeRoomCode == roomCode) {
            val leavePayload = WirePositionPayload(
                id = selfId,
                n = "",
                c = RiderColor.CYAN.raw,
                la = 0.0,
                lo = 0.0,
                st = RiderStatus.LEFT.raw,
                sq = Long.MAX_VALUE
            )
            publishPosition(leavePayload)
            delay(150L)
        }
        stop()
    }

    override fun stop() {
        activeRoomCode = null
        activeRoomTag8 = ByteArray(0)
        hostedTrackId = null
        hostedEncryptedTrack = null
        onStatusChanged(false)
        rxJob?.cancel()
        rxJob = null
        gpxServerJob?.cancel()
        gpxServerJob = null
        runCatching { udpSocket?.close() }
        udpSocket = null
        runCatching { gpxServerSocket?.close() }
        gpxServerSocket = null
    }

    private fun handleDatagram(roomCode: String, dg: LanDatagram, localIps: Set<String>) {
        if (dg.senderIp.isNotEmpty() && dg.senderIp in localIps) return
        val data = dg.data
        if (data.size < 12 + 29) return

        val magic = GroupTransportConstants.LAN_MAGIC
        for (i in 0 until 4) {
            if (data[i] != magic[i]) return
        }
        val tag8 = activeRoomTag8
        if (tag8.size != 8) return
        for (i in 0 until 8) {
            if (data[4 + i] != tag8[i]) return
        }

        val envelope = data.copyOfRange(12, data.size)
        val plain = GroupCrypto.decryptEnvelope(roomCode, envelope) ?: return
        val payload = runCatching {
            wireJson.decodeFromString<WirePositionPayload>(plain.decodeToString())
        }.getOrNull() ?: return

        if (payload.id != activeSelfId) {
            onPositionReceived(payload, GroupTransportSource.OFFLINE_LAN)
        }

        // Retransmisión gossip de compañeros recientes en LAN
        for (peer in payload.peers) {
            if (peer.id != activeSelfId) {
                onPositionReceived(
                    WirePositionPayload(
                        id = peer.id,
                        n = peer.n,
                        c = peer.c,
                        la = peer.la,
                        lo = peer.lo,
                        al = peer.al.toDouble(),
                        sp = peer.sp.toDouble(),
                        hd = peer.hd.toDouble(),
                        bt = peer.bt,
                        st = peer.st,
                        sm = peer.sm,
                        sq = peer.sq,
                        ts = peer.ts
                    ),
                    GroupTransportSource.OFFLINE_LAN
                )
            }
        }

        val advGpxId = payload.gpxId
        val advGpxPort = payload.gpxPort
        if (!advGpxId.isNullOrBlank() &&
            advGpxPort != null &&
            advGpxId != hostedTrackId &&
            dg.senderIp.isNotBlank() &&
            fetchedLock.withLock { fetchedTrackIds.add(advGpxId) }
        ) {
            fetchLanGpx(roomCode, dg.senderIp, advGpxPort, advGpxId)
        }
    }

    private fun ensureGpxServerStarted() {
        if (gpxServerJob?.isActive == true) return
        gpxServerJob = scope.launch {
            try {
                val srv = BikeSocketFactory.createServer(GroupTransportConstants.LAN_GPX_TCP_PORT, backlog = 8)
                gpxServerSocket = srv
                while (isActive && activeRoomCode != null) {
                    val client = try {
                        srv.acceptClient()
                    } catch (_: Exception) {
                        break
                    }
                    launch {
                        try {
                            client.setSendTimeout(4000)
                            val bytes = hostedEncryptedTrack
                            if (bytes != null) {
                                val out = ByteArrayOutput(4 + bytes.size)
                                out.writeInt32LE(bytes.size)
                                out.writeBytes(bytes)
                                client.writeAll(out.toByteArray())
                            }
                        } catch (_: Exception) {
                        } finally {
                            client.close()
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { gpxServerSocket?.close() }
                gpxServerSocket = null
            }
        }
    }

    private fun fetchLanGpx(roomCode: String, hostIp: String, port: Int, trackId: String) {
        scope.launch {
            var sock: com.oscar.endulink.bike.BikeSocket? = null
            try {
                sock = BikeSocketFactory.connectTCP(hostIp, port, timeoutMs = 4000)
                sock.setReceiveTimeout(4000)
                val lenBytes = sock.readFully(4)
                val len = lenBytes.readInt32LE(0)
                if (len in 1..2_000_000) {
                    val encrypted = sock.readFully(len)
                    val plain = GroupCrypto.decryptEnvelope(roomCode, encrypted)
                    if (plain != null) {
                        val track = wireJson.decodeFromString<WireSharedTrackPayload>(plain.decodeToString())
                        onTrackReceived(track)
                    } else {
                        fetchedLock.withLock { fetchedTrackIds.remove(trackId) }
                    }
                }
            } catch (_: Exception) {
                fetchedLock.withLock { fetchedTrackIds.remove(trackId) }
            } finally {
                runCatching { sock?.close() }
            }
        }
    }
}
