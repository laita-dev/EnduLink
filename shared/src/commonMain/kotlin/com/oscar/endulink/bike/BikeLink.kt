package com.oscar.endulink.bike

import com.oscar.endulink.platform.SynchronizedLock
import com.oscar.endulink.platform.platformSleep
import com.oscar.endulink.platform.withLock
import kotlin.concurrent.Volatile
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*

data class PhoneCard(
    val uuid: String,
    val brand: String = "Google",
    val model: String = "Pixel",
    val osVersion: String = "14",
    val bluetoothName: String = "EnduLink",
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2400,
    val publicKey: String,
    val sign: (String) -> String,
    val packageName: String = "net.easyconn.easyride.wws",
    val versionCode: Int = 47,
    val fingerprint: String = "V:2.4(47)-eee8ab8d1-ONLINE"
)

interface BikePicture {
    fun start(request: PictureRequest): Boolean
    fun fresh()
    fun next(waitMs: Int): ByteArray?
    fun stop()
}

enum class BikeState(val title: String) {
    LISTENING("Esperando moto"),
    GREETED("Conectado"),
    SHOWING("Transmitiendo"),
    CLOSED("Desconectado")
}

interface BikeEvents {
    fun log(line: String)
    fun state(state: BikeState)
    fun gone(why: String)
}

class BikeLink(
    private val card: PhoneCard,
    private val picture: BikePicture,
    private val events: BikeEvents,
    private val pxcPort: Int = 10922,
    private val controlPort: Int = 10921,
    private val dataPort: Int = 10920,
    private val allow: (fromIP: String, toIP: String?) -> Boolean = { _, _ -> true }
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var isClosed = false
    private val doorsLock = SynchronizedLock()
    private val doors = mutableListOf<BikeServerSocket>()
    private val socketsLock = SynchronizedLock()
    private val openSockets = mutableListOf<BikeSocket>()
    private val saidOnceLock = SynchronizedLock()
    private val saidOnce = mutableSetOf<String>()
    private val refusedLock = SynchronizedLock()
    private val refusedIPs = mutableSetOf<String>()

    @Volatile
    private var goneReported = false

    @Volatile
    private var controlTurn = 0

    @Volatile
    private var dataTurn = 0

    @Volatile
    private var currentDataSocket: BikeSocket? = null

    @Volatile
    private var currentRequest: PictureRequest? = null

    @Volatile
    private var bikeTimeoutMs = 9000

    @Volatile
    var bikeInfoJson: String? = null
        private set

    private val toAsk = ArrayDeque<PxcMessage>()
    private val askLock = SynchronizedLock()

    companion object {
        private const val FRAME_WAIT_MS = 40
        private const val MAX_FPS = 60
        private const val MAX_BIKE_TIMEOUT_S = 120
        private const val MAX_OPEN_SOCKETS = 16
    }

    val isOpen: Boolean get() = !isClosed

    fun open() {
        try {
            listen("pxc", pxcPort, ::handlePxc)
            listen("control", controlPort, ::handleControl)
            listen("data", dataPort, ::handleData)
            events.state(BikeState.LISTENING)
            events.log("Esperando conexión de la pantalla en puertos $pxcPort (PXC), $controlPort (Control), $dataPort (Vídeo)...")
        } catch (e: Exception) {
            events.log("Error abriendo sockets servidores: ${e.message}")
            close()
        }
    }

    /**
     * Llama al puerto 10930 (Knock) de la pantalla TFT de la moto para forzarla a conectarse.
     */
    fun knock(bikeIP: String, interfaceName: String? = null, port: Int = 10930): Boolean {
        return try {
            val sock = BikeSocketFactory.connectTCP(bikeIP, port, interfaceName, 2000)
            try {
                val helloObj = buildJsonObject {
                    put("phoneType", "Android")
                    put("packageName", card.packageName)
                }
                val body = helloObj.toString().encodeToByteArray()
                Pxc.write(sock, Pxc.knock, body)
                val answer = Pxc.read(sock)
                events.log("Llamada knock responde: 0x${answer.command.toString(16)}")

                if (answer.command == Pxc.knock + 1u) {
                    val root = parseJson(answer.text)
                    val status = root?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
                    status.equals("true", ignoreCase = true)
                } else {
                    false
                }
            } finally {
                sock.close()
            }
        } catch (e: Exception) {
            events.log("Knock a $bikeIP falló: ${e.message}")
            false
        }
    }

    fun close() {
        if (isClosed) return
        isClosed = true

        val allDoors = doorsLock.withLock {
            val list = doors.toList()
            doors.clear()
            list
        }
        val allSockets = socketsLock.withLock {
            val list = openSockets.toList()
            openSockets.clear()
            list
        }

        allDoors.forEach { it.close() }
        allSockets.forEach { it.close() }
        picture.stop()
        events.state(BikeState.CLOSED)
        scope.cancel()
    }

    private fun listen(name: String, port: Int, serve: (BikeSocket) -> Unit) {
        val server = BikeSocketFactory.createServer(port, backlog = 50)
        doorsLock.withLock { doors.add(server) }

        scope.launch(Dispatchers.Default) {
            while (!isClosed) {
                val client = try {
                    server.acceptClient()
                } catch (_: Exception) {
                    break
                }

                val remote = client.remoteIP ?: ""
                if (remote.isNotEmpty() && !allow(remote, client.localIP)) {
                    val isNew = refusedLock.withLock { refusedIPs.add(remote) }
                    if (isNew) {
                        events.log("Conexión rechazada de $remote")
                    }
                    client.close()
                    continue
                }

                var shouldBreak = false
                var shouldContinue = false
                socketsLock.withLock {
                    if (isClosed) {
                        client.close()
                        shouldBreak = true
                    } else if (openSockets.size >= MAX_OPEN_SOCKETS) {
                        events.log("$name: demasiadas conexiones abiertas, rechazando $remote")
                        client.close()
                        shouldContinue = true
                    } else {
                        openSockets.add(client)
                    }
                }
                if (shouldBreak) break
                if (shouldContinue) continue

                launch(Dispatchers.Default) {
                    try {
                        serve(client)
                    } finally {
                        socketsLock.withLock { openSockets.remove(client) }
                        client.close()
                    }
                }
            }
        }
    }

    // MARK: - PXC Conversation (Port 10922)

    private fun handlePxc(socket: BikeSocket) {
        try {
            val opening = Pxc.read(socket)
            Pxc.write(socket, opening.command + 1u)

            when (opening.command) {
                Pxc.openBikeAsks -> {
                    events.log("La moto abre canal de sus preguntas")
                    while (!isClosed) {
                        socket.setReceiveTimeout(3 * bikeTimeoutMs)
                        val asked = Pxc.read(socket)
                        answerBike(asked, socket)
                    }
                }
                Pxc.openPhoneAsks -> {
                    events.log("La moto abre canal de nuestras preguntas")
                    askBike(socket)
                }
                else -> {
                    events.log("PXC: apertura desconocida 0x${opening.command.toString(16)}")
                }
            }
        } catch (e: Exception) {
            if (!isClosed) {
                reportLost("PXC cerrado: ${e.message}")
            }
        }
    }

    private fun answerBike(asked: PxcMessage, socket: BikeSocket) {
        // Respuestas de la moto son órdenes impares
        if ((asked.command and 1u) != 0u) return
        val reply = asked.command + 1u

        when (asked.command) {
            Pxc.heartbeat -> {
                once("latido") { "La moto late (heartbeat)" }
                Pxc.write(socket, reply)
            }
            Pxc.bikeInfo -> {
                val text = asked.text
                bikeInfoJson = text
                val parsed = parseJson(text)?.jsonObject
                val period = parsed?.get("socketTimeoutPeriodWifi")?.jsonPrimitive?.intOrNull
                if (period != null && period > 0) {
                    bikeTimeoutMs = minOf(period, MAX_BIKE_TIMEOUT_S) * 1000
                }
                events.log("La moto se presenta: $text")

                val greetData = buildGreetingJson(parsed)
                Pxc.write(socket, reply, greetData)
                events.state(BikeState.GREETED)
            }
            Pxc.whatTime -> {
                Pxc.write(socket, reply, buildTimeJson())
            }
            Pxc.checkSerial -> {
                val parsed = parseJson(asked.text)?.jsonObject
                val serial = parsed?.get("sn")?.jsonPrimitive?.contentOrNull ?: ""
                events.log("La moto entrega número de serie: $serial")
                Pxc.write(socket, reply)

                val verdict = buildJsonObject {
                    put("isOk", true)
                    put("errCode", -100)
                    put("errMsg", "Network Not Available")
                    put("id", serial)
                }
                askLock.withLock {
                    toAsk.addLast(PxcMessage(Pxc.serialChecked, verdict.toString().encodeToByteArray()))
                }
            }
            Pxc.hudIcons, Pxc.weather, Pxc.extra0740 -> {
                once("c${asked.command}") { "La moto reporta 0x${asked.command.toString(16)}" }
                Pxc.write(socket, reply)
            }
            Pxc.encrypt -> {
                events.log("La moto solicita cifrado PXC -> respondiendo no soportado")
                Pxc.write(socket, Pxc.notSupported)
            }
            else -> {
                events.log("La moto solicita comando nuevo: 0x${asked.command.toString(16)}")
                Pxc.write(socket, Pxc.notSupported)
            }
        }
    }

    private fun askBike(socket: BikeSocket) {
        socket.setReceiveTimeout(bikeTimeoutMs)

        val inFrontObj = buildJsonObject {
            put("displayRotation", 0)
            put("width", card.screenWidth)
            put("height", card.screenHeight)
            put("enableAccessibility", false)
            put("enableAOAHid", true)
            put("mode", 2)
        }
        val inFront = PxcMessage(Pxc.appInFront, inFrontObj.toString().encodeToByteArray())

        var next = inFront
        while (!isClosed) {
            Pxc.write(socket, next.command, next.body)
            val answer = Pxc.read(socket)
            if (answer.command != next.command + 1u) {
                once("r${next.command}") { "A 0x${next.command.toString(16)} la moto responde 0x${answer.command.toString(16)}" }
            } else {
                once("ok${next.command}") { "La moto acepta comando 0x${next.command.toString(16)}" }
            }

            next = askLock.withLock {
                if (toAsk.isNotEmpty()) toAsk.removeFirst() else inFront
            }
            // Pequeña pausa entre preguntas
            platformSleep(1000)
        }
    }

    // MARK: - RV Control (Port 10921)

    private fun handleControl(socket: BikeSocket) {
        controlTurn++
        val myTurn = controlTurn

        try {
            while (!isClosed) {
                val asked = Rv.read(socket)
                when (asked.command) {
                    Rv.configure -> {
                        val wanted = PictureRequest.parse(asked.body)
                        currentRequest = wanted
                        events.log("La moto solicita configuración de imagen: $wanted")
                        Rv.write(socket, (Rv.configure + 1u).toUShort(), wanted.reply())
                    }
                    Rv.configureMore -> {
                        events.log("La moto detalla configuración: ${asked.body.decodeToString()}")
                        val resp = buildJsonObject {
                            put("supportFunction", 0)
                            putJsonObject("viewAreaConfig") {
                                put("state", 0)
                            }
                        }
                        Rv.write(socket, (Rv.configureMore + 1u).toUShort(), resp.toString().encodeToByteArray())
                    }
                    Rv.version -> {
                        val ver = ByteArrayOutput()
                        ver.writeInt32LE(5002)
                        ver.writeInt32LE(1)
                        Rv.write(socket, (Rv.version + 1u).toUShort(), ver.toByteArray())
                    }
                    Rv.heartbeat -> {
                        Rv.write(socket, (Rv.heartbeat + 1u).toUShort())
                    }
                    Rv.start -> {
                        val wanted = currentRequest
                        val started = wanted != null && wanted.encoder == 2 && picture.start(wanted)
                        events.log(if (started) "Codificador H.264 iniciado correctamente" else "Fallo al iniciar codificador H.264 ($wanted)")
                        Rv.write(socket, if (started) (Rv.start + 1u).toUShort() else Rv.startFailed)
                    }
                    Rv.keyOrTouch -> {
                        val input = BikeInput.parse(asked.body)
                        if (input != null) {
                            events.log("Pulsación táctil/tecla TFT: $input")
                        }
                    }
                    Rv.shell -> {
                        val cmd = asked.body.decodeToString().trim('\u0000', ' ', '\t', '\r', '\n')
                        val answer = when (cmd) {
                            "getprop ro.build.version.release" -> card.osVersion
                            "getprop ro.product.model" -> card.model
                            else -> "OK"
                        }
                        val out = (answer + "\u0000").encodeToByteArray()
                        Rv.write(socket, (Rv.shell + 1u).toUShort(), out)
                    }
                    else -> {
                        events.log("RV Control: orden desconocida 0x${asked.command.toString(16)}")
                        Rv.write(socket, Rv.notSupported)
                    }
                }
            }
        } catch (e: Exception) {
            if (!isClosed) {
                events.log("Canal RV Control cerrado: ${e.message}")
            }
        } finally {
            if (controlTurn == myTurn) {
                currentRequest = null
                picture.stop()
                if (!isClosed) {
                    events.state(BikeState.GREETED)
                }
            }
        }
    }

    // MARK: - RV Video Data (Port 10920)

    private fun handleData(socket: BikeSocket) {
        val wanted = currentRequest ?: run {
            events.log("Datos de vídeo solicitados antes de configurar la imagen")
            return
        }

        val previous = currentDataSocket
        currentDataSocket = socket
        dataTurn++
        val myTurn = dataTurn
        previous?.close()

        try {
            sendPictureLoop(socket, wanted, myTurn)
        } catch (e: Exception) {
            if (!isClosed && dataTurn == myTurn) {
                events.log("Canal RV Data cerrado: ${e.message}")
            }
        } finally {
            if (currentDataSocket === socket) {
                currentDataSocket = null
            }
        }
    }

    private fun sendPictureLoop(socket: BikeSocket, request: PictureRequest, turn: Int) {
        events.log("Iniciando transmisión de frames H.264...")
        picture.fresh()

        var shown = false
        var sent = 0
        var sentBytes = 0L

        while (!isClosed && dataTurn == turn) {
            val wanted = currentRequest ?: break
            val waitMs = if (wanted.fps <= 0) {
                val next = Rv.read(socket)
                if (next.command != Rv.nextFrame) {
                    Rv.write(socket, Rv.notSupported)
                    return
                }
                FRAME_WAIT_MS
            } else {
                val clamped = minOf(maxOf(wanted.fps, 1), MAX_FPS)
                1000 / clamped
            }

            val chunk = picture.next(waitMs)
            if (chunk != null) {
                val packet = ByteArrayOutput()
                packet.writeInt32LE(chunk.size)
                packet.writeBytes(chunk)
                socket.writeAll(packet.toByteArray())
                sent++
                sentBytes += chunk.size

                if (!shown) {
                    shown = true
                    events.log("Primer fotograma H.264 enviado a la moto (${chunk.size} bytes)")
                    events.state(BikeState.SHOWING)
                }
            } else {
                val zero = ByteArrayOutput()
                zero.writeInt32LE(0)
                socket.writeAll(zero.toByteArray())
            }
        }
    }

    // MARK: - Payloads

    private fun buildGreetingJson(bike: JsonObject?): ByteArray {
        val bikeId = bike?.get("HUID")?.jsonPrimitive?.contentOrNull ?: ""
        var signed = ""
        if (bikeId.isNotEmpty()) {
            try {
                signed = card.sign(bikeId)
            } catch (e: Exception) {
                events.log("Error firmando HUID: ${e.message}")
            }
        }
        val supportFunc = bike?.get("supportFunction")?.jsonPrimitive?.longOrNull ?: 0L

        val payload = buildJsonObject {
            put("pxcVersion", "1.0.2")
            put("phoneUUID", card.uuid)
            put("phoneBrand", card.brand)
            put("phoneModel", card.model)
            put("phoneOsVersion", card.osVersion)
            put("phoneOs", "Android")
            put("package", card.packageName)
            put("versionCode", card.versionCode)
            put("token", 0)
            put("pubkey", card.publicKey)
            put("encryptedHUID", signed)
            put("bluetoothName", card.bluetoothName)
            put("supportH264IFrame", true)
            put("supportFunction", supportFunc)
            put("appVersionFingerPrint", card.fingerprint)
            put("speechEngineType", 2)
        }
        return payload.toString().encodeToByteArray()
    }

    private fun buildTimeJson(): ByteArray {
        val now = Clock.System.now()
        val nowMs = now.toEpochMilliseconds()
        val tz = TimeZone.currentSystemDefault()
        val local = now.toLocalDateTime(tz)

        val formattedDate = "${local.dayOfMonth.toString().padStart(2, '0')}." +
                "${local.monthNumber.toString().padStart(2, '0')}." +
                "${local.year} " +
                "${local.hour.toString().padStart(2, '0')}:" +
                "${local.minute.toString().padStart(2, '0')}:" +
                "${local.second.toString().padStart(2, '0')}:" +
                "${(local.nanosecond / 1_000_000).toString().padStart(3, '0')}"

        val payload = buildJsonObject {
            put("time", nowMs)
            put("currentTime", nowMs)
            put("currentTimeZone", tz.id)
            put("dateTime", formattedDate)
        }
        return payload.toString().encodeToByteArray()
    }

    private fun reportLost(why: String) {
        if (!isClosed && !goneReported) {
            goneReported = true
            events.log("Sesión con la moto perdida: $why")
            events.gone(why)
        }
    }

    private fun parseJson(text: String): JsonElement? {
        return try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun once(key: String, message: () -> String) {
        val isNew = saidOnceLock.withLock { saidOnce.add(key) }
        if (isNew) {
            events.log(message())
        }
    }
}

