package com.oscar.endulink.group

import com.oscar.endulink.platform.RiderTelemetry
import com.oscar.endulink.platform.SynchronizedLock
import com.oscar.endulink.platform.withLock
import com.oscar.endulink.trail.GPXRoute
import com.oscar.endulink.trail.GeoCoord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Política adaptativa de transmisión GPS para minimizar el consumo de batería:
 * - Modo Normal: 4 s en movimiento (>= 4 km/h o >= 15 m), 25 s en parado.
 * - Modo Ahorro: 10 s en movimiento (>= 4 km/h o >= 25 m), 45 s en parado.
 * - Eventos críticos (unión inicial, SOS/Avería/Esperadme): envío inmediato.
 */
object GroupTxPolicy {
    const val MIN_THROTTLE_MS = 2_000L
    const val NORMAL_MOVING_MS = 4_000L
    const val NORMAL_IDLE_MS = 25_000L
    const val SAVER_MOVING_MS = 10_000L
    const val SAVER_IDLE_MS = 45_000L
    const val NORMAL_MOVE_METERS = 15.0
    const val SAVER_MOVE_METERS = 25.0
    const val MOVING_SPEED_KMH = 4.0

    fun shouldTransmitPosition(
        nowMs: Long,
        lastSentMs: Long,
        lastSentCoord: GeoCoord?,
        currentCoord: GeoCoord,
        speedKmh: Double,
        batterySaverMode: Boolean,
        forceImmediate: Boolean
    ): Boolean {
        if (forceImmediate) return true
        val elapsed = nowMs - lastSentMs
        if (lastSentMs > 0L && elapsed < MIN_THROTTLE_MS) return false
        if (lastSentCoord == null || lastSentMs <= 0L) return true

        val movedMeters = lastSentCoord.distanceTo(currentCoord)
        val moveThreshold = if (batterySaverMode) SAVER_MOVE_METERS else NORMAL_MOVE_METERS
        val isMoving = speedKmh >= MOVING_SPEED_KMH || movedMeters >= moveThreshold

        val requiredInterval = when {
            batterySaverMode && isMoving -> SAVER_MOVING_MS
            batterySaverMode && !isMoving -> SAVER_IDLE_MS
            !batterySaverMode && isMoving -> NORMAL_MOVING_MS
            else -> NORMAL_IDLE_MS
        }
        return elapsed >= requiredInterval
    }
}

class GroupManager(
    private val onConfigChanged: (GroupConfig) -> Unit = {}
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val stateLock = SynchronizedLock()

    private val _config = MutableStateFlow(ensureMemberId(GroupConfig()))
    val config: StateFlow<GroupConfig> = _config.asStateFlow()

    private val _activeRoomCode = MutableStateFlow<String?>(null)
    val activeRoomCode: StateFlow<String?> = _activeRoomCode.asStateFlow()

    private val _onlineConnected = MutableStateFlow(false)
    val onlineConnected: StateFlow<Boolean> = _onlineConnected.asStateFlow()

    private val _lanActive = MutableStateFlow(false)
    val lanActive: StateFlow<Boolean> = _lanActive.asStateFlow()

    private val _connectionState = MutableStateFlow(GroupConnectionState.DISCONNECTED)
    val connectionState: StateFlow<GroupConnectionState> = _connectionState.asStateFlow()

    private val _members = MutableStateFlow<List<GroupMember>>(emptyList())
    val members: StateFlow<List<GroupMember>> = _members.asStateFlow()

    private val _myStatus = MutableStateFlow(RiderStatus.RIDING)
    val myStatus: StateFlow<RiderStatus> = _myStatus.asStateFlow()

    private val _myStatusMessage = MutableStateFlow("")
    val myStatusMessage: StateFlow<String> = _myStatusMessage.asStateFlow()

    private val _sharedTrack = MutableStateFlow<SharedGroupTrack?>(null)
    val sharedTrack: StateFlow<SharedGroupTrack?> = _sharedTrack.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val memberMap = LinkedHashMap<String, GroupMember>()
    private var seqCounter: Long = 0L
    private var lastSentMs: Long = 0L
    private var lastSentCoord: GeoCoord? = null
    private var lastTelemetry: RiderTelemetry = RiderTelemetry()
    private var lastBatteryPct: Int = -1
    private var maintenanceJob: Job? = null
    private var lastHandledTrackId: String? = null

    private val mqttTransport = MqttWebSocketTransport(
        customBrokerUrlProvider = { _config.value.customBrokerUrl },
        onConnectionChanged = { connected ->
            _onlineConnected.value = connected
            refreshConnectionState()
        },
        onConnectedReady = {
            publishCurrentPosition(forceImmediate = true)
        },
        onPositionReceived = { payload, src ->
            handleIncomingPosition(payload, src)
        },
        onMemberLeft = { id ->
            removeMember(id)
        },
        onTrackReceived = { wireTrack ->
            handleIncomingTrack(wireTrack)
        }
    )

    private val lanTransport = LocalLanUdpTransport(
        gossipPeersProvider = { buildLanGossipPeers() },
        onStatusChanged = { active ->
            _lanActive.value = active
            refreshConnectionState()
        },
        onPositionReceived = { payload, src ->
            handleIncomingPosition(payload, src)
        },
        onTrackReceived = { wireTrack ->
            handleIncomingTrack(wireTrack)
        }
    )

    fun loadConfig(saved: GroupConfig) {
        val ensured = ensureMemberId(saved)
        _config.value = ensured
        if (ensured != saved) {
            onConfigChanged(ensured)
        }
    }

    fun updateProfile(nickname: String, color: RiderColor) {
        val cleanName = nickname.trim().take(24)
        val updated = ensureMemberId(_config.value).copy(
            nickname = cleanName,
            riderColorRaw = color.raw
        )
        _config.value = updated
        onConfigChanged(updated)
        if (_activeRoomCode.value != null) {
            publishCurrentPosition(forceImmediate = true)
        }
    }

    fun updateNetworkSettings(
        batterySaverMode: Boolean,
        enableOnlineSync: Boolean,
        enableOfflineLanSync: Boolean,
        customBrokerUrl: String
    ) {
        val prev = _config.value
        val updated = ensureMemberId(prev).copy(
            batterySaverMode = batterySaverMode,
            enableOnlineSync = enableOnlineSync,
            enableOfflineLanSync = enableOfflineLanSync,
            customBrokerUrl = customBrokerUrl.trim()
        )
        _config.value = updated
        onConfigChanged(updated)

        val room = _activeRoomCode.value
        if (room != null) {
            val selfId = updated.memberId
            if (updated.enableOnlineSync != prev.enableOnlineSync || updated.customBrokerUrl != prev.customBrokerUrl) {
                if (updated.enableOnlineSync) mqttTransport.start(room, selfId) else mqttTransport.stop()
            }
            if (updated.enableOfflineLanSync != prev.enableOfflineLanSync) {
                if (updated.enableOfflineLanSync) lanTransport.start(room, selfId) else lanTransport.stop()
            }
            refreshConnectionState()
        }
    }

    fun createGroup(): String {
        val code = GroupInvite.generateRoomCode()
        joinRoomInternal(code)
        return code
    }

    fun joinGroup(rawCodeOrLink: String): Boolean {
        val parsed = GroupInvite.parseRoomCode(rawCodeOrLink) ?: return false
        joinRoomInternal(parsed)
        return true
    }

    private fun joinRoomInternal(roomCode: String) {
        val cfg = ensureMemberId(_config.value).let { cur ->
            val defaultName = cur.nickname.ifBlank { "Piloto-${cur.memberId.takeLast(4).uppercase()}" }
            cur.copy(nickname = defaultName, lastRoomCode = roomCode)
        }
        _config.value = cfg
        onConfigChanged(cfg)

        mqttTransport.stop()
        lanTransport.stop()
        maintenanceJob?.cancel()

        stateLock.withLock {
            memberMap.clear()
            seqCounter = 0L
            lastSentMs = 0L
            lastSentCoord = null
            lastHandledTrackId = null
        }
        _members.value = emptyList()
        _sharedTrack.value = null
        _myStatus.value = RiderStatus.RIDING
        _myStatusMessage.value = ""
        _activeRoomCode.value = roomCode
        _connectionState.value = GroupConnectionState.CONNECTING

        if (cfg.enableOnlineSync) {
            mqttTransport.start(roomCode, cfg.memberId)
        }
        if (cfg.enableOfflineLanSync) {
            lanTransport.start(roomCode, cfg.memberId)
        }
        refreshConnectionState()

        maintenanceJob = scope.launch {
            while (isActive && _activeRoomCode.value == roomCode) {
                delay(5_000L)
                val nowMs = Clock.System.now().toEpochMilliseconds()
                // Refrescar para actualizar indicadores "hace X s" y limpiar desconectados de hace > 2 horas
                var changed = false
                stateLock.withLock {
                    val it = memberMap.entries.iterator()
                    while (it.hasNext()) {
                        val entry = it.next()
                        if (nowMs - entry.value.receivedAtEpochMs > 7_200_000L) {
                            it.remove()
                            changed = true
                        }
                    }
                    if (changed || memberMap.isNotEmpty()) {
                        publishSortedMembersLocked()
                    }
                }
                publishCurrentPosition(forceImmediate = false)
            }
        }

        publishCurrentPosition(forceImmediate = true)
    }

    fun leaveGroup() {
        val room = _activeRoomCode.value ?: return
        val selfId = _config.value.memberId
        maintenanceJob?.cancel()
        maintenanceJob = null
        _activeRoomCode.value = null
        _myStatus.value = RiderStatus.RIDING
        _myStatusMessage.value = ""
        _sharedTrack.value = null
        stateLock.withLock {
            memberMap.clear()
        }
        _members.value = emptyList()
        _onlineConnected.value = false
        _lanActive.value = false
        _connectionState.value = GroupConnectionState.DISCONNECTED

        scope.launch {
            mqttTransport.leaveAndStop(room, selfId)
            lanTransport.leaveAndStop(room, selfId)
        }
    }

    fun shutdown() {
        maintenanceJob?.cancel()
        maintenanceJob = null
        mqttTransport.stop()
        lanTransport.stop()
        _activeRoomCode.value = null
        _onlineConnected.value = false
        _lanActive.value = false
        _connectionState.value = GroupConnectionState.DISCONNECTED
    }

    fun setMyStatus(status: RiderStatus, message: String = "") {
        _myStatus.value = status
        _myStatusMessage.value = message.trim().take(60)
        if (_activeRoomCode.value != null) {
            publishCurrentPosition(forceImmediate = true)
        }
    }

    fun shareRoute(route: GPXRoute): Boolean {
        if (_activeRoomCode.value == null) return false
        val cfg = ensureMemberId(_config.value)
        val senderName = cfg.nickname.ifBlank { "Piloto" }
        val wire = CompactGroupTrackCodec.encodeRoute(route, cfg.memberId, senderName) ?: return false
        if (cfg.enableOnlineSync) mqttTransport.publishSharedTrack(wire)
        if (cfg.enableOfflineLanSync) lanTransport.publishSharedTrack(wire)
        publishCurrentPosition(forceImmediate = true)
        return true
    }

    fun dismissSharedTrack() {
        _sharedTrack.value = null
    }

    fun onLocationUpdate(telemetry: RiderTelemetry, batteryPct: Int) {
        stateLock.withLock {
            lastTelemetry = telemetry
            lastBatteryPct = batteryPct
        }
        if (_activeRoomCode.value != null) {
            publishCurrentPosition(forceImmediate = false)
        }
    }

    private fun publishCurrentPosition(forceImmediate: Boolean) {
        val room = _activeRoomCode.value ?: return
        if (room.isBlank()) return
        val cfg = _config.value

        val snapshot = stateLock.withLock {
            val coord = lastTelemetry.coordinate ?: return
            val nowMs = Clock.System.now().toEpochMilliseconds()
            val shouldSend = GroupTxPolicy.shouldTransmitPosition(
                nowMs = nowMs,
                lastSentMs = lastSentMs,
                lastSentCoord = lastSentCoord,
                currentCoord = coord,
                speedKmh = lastTelemetry.speedKmh,
                batterySaverMode = cfg.batterySaverMode,
                forceImmediate = forceImmediate
            )
            if (!shouldSend) return

            lastSentMs = nowMs
            lastSentCoord = coord
            seqCounter += 1L

            WirePositionPayload(
                id = cfg.memberId,
                n = cfg.nickname.ifBlank { "Piloto" }.take(24),
                c = cfg.riderColor.raw,
                la = (coord.latitude * 1_000_000.0).roundToInt() / 1_000_000.0,
                lo = (coord.longitude * 1_000_000.0).roundToInt() / 1_000_000.0,
                al = lastTelemetry.altitudeM.roundToInt().toDouble(),
                sp = (lastTelemetry.speedKmh * 10.0).roundToInt() / 10.0,
                hd = (lastTelemetry.headingDegrees * 10.0).roundToInt() / 10.0,
                bt = lastBatteryPct,
                st = _myStatus.value.raw,
                sm = _myStatusMessage.value,
                sq = seqCounter,
                ts = nowMs
            )
        }

        if (cfg.enableOnlineSync) mqttTransport.publishPosition(snapshot)
        if (cfg.enableOfflineLanSync) lanTransport.publishPosition(snapshot)
    }

    private fun handleIncomingPosition(payload: WirePositionPayload, source: GroupTransportSource) {
        val selfId = _config.value.memberId
        if (payload.id.isBlank() || payload.id == selfId) return
        val status = RiderStatus.fromRaw(payload.st)
        if (status == RiderStatus.LEFT) {
            removeMember(payload.id)
            return
        }

        val nowMs = Clock.System.now().toEpochMilliseconds()
        stateLock.withLock {
            val existing = memberMap[payload.id]
            val restarted = payload.sq in 1L..2L && (existing?.seq ?: 0L) > 4L
            if (existing != null && !restarted) {
                if (payload.sq < existing.seq) return
                if (payload.sq == existing.seq && nowMs - existing.receivedAtEpochMs < 2500L) return
            }

            memberMap[payload.id] = GroupMember(
                id = payload.id,
                name = payload.n.ifBlank { "Compañero" }.take(24),
                color = RiderColor.fromRaw(payload.c),
                latitude = payload.la,
                longitude = payload.lo,
                altitudeM = payload.al,
                speedKmh = payload.sp,
                headingDegrees = payload.hd,
                batteryPct = payload.bt,
                status = status,
                statusMessage = payload.sm.take(60),
                seq = payload.sq,
                sentAtEpochMs = payload.ts,
                receivedAtEpochMs = nowMs,
                source = source
            )
            publishSortedMembersLocked()
        }
    }

    private fun removeMember(memberId: String) {
        stateLock.withLock {
            if (memberMap.remove(memberId) != null) {
                publishSortedMembersLocked()
            }
        }
    }

    private fun handleIncomingTrack(wire: WireSharedTrackPayload) {
        val selfId = _config.value.memberId
        if (wire.sid == selfId) return
        val isNew = stateLock.withLock {
            if (lastHandledTrackId == wire.id) false
            else {
                lastHandledTrackId = wire.id
                true
            }
        }
        if (!isNew) return
        val converted = CompactGroupTrackCodec.toSharedGroupTrack(wire) ?: return
        _sharedTrack.value = converted
    }

    private fun buildLanGossipPeers(): List<WirePeerGossip> {
        val nowMs = Clock.System.now().toEpochMilliseconds()
        return stateLock.withLock {
            memberMap.values
                .filter { nowMs - it.receivedAtEpochMs <= 45_000L }
                .sortedByDescending { it.receivedAtEpochMs }
                .take(8)
                .map { m ->
                    WirePeerGossip(
                        id = m.id,
                        n = m.name,
                        c = m.color.raw,
                        la = m.latitude,
                        lo = m.longitude,
                        al = m.altitudeM.roundToInt(),
                        sp = m.speedKmh.roundToInt(),
                        hd = m.headingDegrees.roundToInt(),
                        bt = m.batteryPct,
                        st = m.status.raw,
                        sm = m.statusMessage,
                        sq = m.seq,
                        ts = m.sentAtEpochMs
                    )
                }
        }
    }

    private fun publishSortedMembersLocked() {
        _members.value = memberMap.values.sortedWith(
            compareByDescending<GroupMember> { it.status.alertPriority }
                .thenByDescending { it.receivedAtEpochMs }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun refreshConnectionState() {
        if (_activeRoomCode.value == null) {
            _connectionState.value = GroupConnectionState.DISCONNECTED
            return
        }
        val online = _onlineConnected.value
        val lan = _lanActive.value
        val cfg = _config.value
        _connectionState.value = when {
            online && lan -> GroupConnectionState.HYBRID
            online -> GroupConnectionState.ONLINE
            lan -> GroupConnectionState.OFFLINE_LAN_ONLY
            cfg.enableOnlineSync -> GroupConnectionState.RECONNECTING
            else -> GroupConnectionState.CONNECTING
        }
    }

    private fun ensureMemberId(cfg: GroupConfig): GroupConfig {
        if (cfg.memberId.isNotBlank()) return cfg
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val id = buildString(12) {
            append("m_")
            repeat(10) { append(chars[Random.nextInt(chars.length)]) }
        }
        return cfg.copy(memberId = id)
    }
}
