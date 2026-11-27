package com.oscar.endulink.platform

import com.oscar.endulink.tiles.PlatformPaths
import com.oscar.endulink.tiles.TileDiskStorage
import com.oscar.endulink.trail.GeoCoord
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class BugReportCategory(
    val id: String,
    val title: String,
    val subtitle: String,
    val ghLabel: String
) {
    BUG("bug", "Fallo / Error", "Algo no funciona o la app falla", "bug"),
    IMPROVEMENT("improvement", "Idea / Mejora", "Sugerencia o nueva funcionalidad", "enhancement"),
    TFT_MOTO("tft", "Pantalla Moto (TFT)", "Conexión Wi-Fi, QR o vídeo en la moto", "area:tft"),
    ROUTING_MAP("map", "Rutas, GPX o Mapas", "Generador de rutas, tracks o teselas offline", "area:mapas"),
    GROUPS("group", "Grupo en Vivo", "Posición de compañeros o sala de grupo", "area:grupos")
}

@Serializable
data class BugReportPayload(
    val reportId: String,
    val createdAtEpochMs: Long,
    val category: BugReportCategory,
    val userTitle: String,
    val userMessage: String,
    val userContact: String = "",
    val appVersion: String,
    val platformName: String,
    val isTestChannel: Boolean,
    val pinCoordinate: GeoCoord? = null,
    val pinAddress: String? = null,
    val includeDiagnostics: Boolean = true,
    val diagnosticsSummary: String = "",
    val recentBikeLogs: List<String> = emptyList(),
    val crashLog: String? = null,
    val hasMapSnapshot: Boolean = false
)

enum class BugSubmitOutcome {
    SENT_ONLINE,
    QUEUED_OFFLINE,
    ERROR
}

object BugReportManager {

    /**
     * URL del Web App de Google Apps Script (desplegado desde scripts/google-apps-script-bug-relay.js).
     * Puede fijarse aquí tras el despliegue o configurarse dinámicamente desde Ajustes -> Soporte y Reportes.
     */
    const val DEFAULT_RELAY_WEBHOOK_URL =
        "https://script.google.com/macros/s/AKfycbw37iN08qgcthmTVWmjeRQg004fqV1EDRx09wM3XP8p1qh33UvMhetZrUGQdrYE3w1O/exec"

    private const val MAX_QUEUED_REPORTS = 30
    private const val MAX_CRASH_CHARS = 4_000

    private val lock = SynchronizedLock()
    private val relayMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val httpClient = HttpClient {
        followRedirects = false
    }

    private fun pendingFilePath(): String {
        return PlatformPaths.getAppDataDir() + "/pending_bug_reports.json"
    }

    private fun crashFilePath(): String {
        return PlatformPaths.getAppDataDir() + "/last_crash_log.txt"
    }

    fun resolveWebhookUrl(customUrl: String = ""): String {
        val trimmedCustom = customUrl.trim()
        if (trimmedCustom.startsWith("https://")) return trimmedCustom
        return DEFAULT_RELAY_WEBHOOK_URL.trim()
    }

    fun loadPendingReports(): List<BugReportPayload> = lock.withLock {
        try {
            val bytes = TileDiskStorage.readBytes(pendingFilePath()) ?: return@withLock emptyList()
            json.decodeFromString<List<BugReportPayload>>(bytes.decodeToString())
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun savePendingReports(reports: List<BugReportPayload>) = lock.withLock {
        try {
            val clamped = if (reports.size > MAX_QUEUED_REPORTS) {
                reports.takeLast(MAX_QUEUED_REPORTS)
            } else {
                reports
            }
            val encoded = json.encodeToString(clamped).encodeToByteArray()
            TileDiskStorage.writeBytes(pendingFilePath(), encoded)
        } catch (_: Throwable) {}
    }

    fun enqueueOffline(report: BugReportPayload) {
        val current = loadPendingReports().toMutableList()
        current.removeAll { it.reportId == report.reportId }
        current.add(report)
        savePendingReports(current)
    }

    suspend fun submitReport(
        report: BugReportPayload,
        customWebhookUrl: String = ""
    ): BugSubmitOutcome = withContext(Dispatchers.Default) {
        val webhookUrl = resolveWebhookUrl(customWebhookUrl)
        if (webhookUrl.isBlank()) {
            enqueueOffline(report)
            return@withContext BugSubmitOutcome.QUEUED_OFFLINE
        }

        relayMutex.withLock {
            val sent = postReportToRelay(webhookUrl, report)
            if (sent) {
                removePendingReport(report.reportId)
                BugSubmitOutcome.SENT_ONLINE
            } else {
                enqueueOffline(report)
                BugSubmitOutcome.QUEUED_OFFLINE
            }
        }
    }

    suspend fun flushPendingReports(customWebhookUrl: String = ""): Int = withContext(Dispatchers.Default) {
        val webhookUrl = resolveWebhookUrl(customWebhookUrl)
        if (webhookUrl.isBlank()) return@withContext 0

        relayMutex.withLock {
            val pending = loadPendingReports()
            if (pending.isEmpty()) return@withLock 0

            var sentCount = 0
            for (report in pending) {
                if (postReportToRelay(webhookUrl, report)) {
                    removePendingReport(report.reportId)
                    sentCount++
                }
            }
            sentCount
        }
    }

    private fun removePendingReport(reportId: String) {
        val current = loadPendingReports()
        val remaining = current.filterNot { it.reportId == reportId }
        if (remaining.size != current.size) {
            savePendingReports(remaining)
        }
    }

    internal fun isRelayResponseAccepted(statusCode: Int, body: String): Boolean {
        if (statusCode !in 200..299) return false
        val compact = body.replace(" ", "").replace("\n", "").replace("\r", "")
        return !compact.contains("\"ok\":false", ignoreCase = true)
    }

    private suspend fun postReportToRelay(webhookUrl: String, report: BugReportPayload): Boolean {
        return runCatching {
            withTimeoutOrNull(20_000L) {
                val payloadJson = json.encodeToString(report)
                var response = httpClient.post(webhookUrl) {
                    contentType(ContentType.Application.Json)
                    headers {
                        append(HttpHeaders.UserAgent, "EnduLink-BugReporter")
                        append(HttpHeaders.Accept, "application/json")
                    }
                    setBody(payloadJson)
                }

                // Google Apps Script ejecuta doPost(e) y responde HTTP 302 hacia script.googleusercontent.com/macros/echo,
                // que debe consultarse con GET (Ktor no redirige POST automáticamente por defecto).
                var hops = 0
                while (response.status.value in setOf(301, 302, 303, 307, 308) && hops < 4) {
                    val location = response.headers[HttpHeaders.Location]?.trim().orEmpty()
                    if (location.isBlank()) break
                    response = httpClient.get(location) {
                        headers {
                            append(HttpHeaders.UserAgent, "EnduLink-BugReporter")
                            append(HttpHeaders.Accept, "application/json")
                        }
                    }
                    hops++
                }

                val body = runCatching { response.bodyAsText() }.getOrDefault("")
                isRelayResponseAccepted(response.status.value, body)
            } ?: false
        }.getOrDefault(false)
    }

    fun recordCrashLog(threadName: String, throwableText: String) = lock.withLock {
        try {
            val sanitized = buildString {
                appendLine("Thread: $threadName")
                appendLine("Platform: ${getPlatformName()} · App: ${PlatformAppUpdater.getAppVersionName()}")
                append(throwableText.take(MAX_CRASH_CHARS))
            }
            TileDiskStorage.writeBytes(crashFilePath(), sanitized.encodeToByteArray())
        } catch (_: Throwable) {}
    }

    fun loadLastCrashLog(): String? = lock.withLock {
        try {
            val bytes = TileDiskStorage.readBytes(crashFilePath()) ?: return@withLock null
            bytes.decodeToString().trim().takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    fun clearLastCrashLog() = lock.withLock {
        try {
            TileDiskStorage.writeBytes(crashFilePath(), ByteArray(0))
        } catch (_: Throwable) {}
    }
}
