package com.oscar.endulink.platform

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val releaseNotes: String,
    val publishedAt: String,
    val apkDownloadUrl: String?,
    val apkSizeMb: Double?,
    val ipaDownloadUrl: String?,
    val ipaSizeMb: Double?,
    val htmlUrl: String,
    val isNewer: Boolean
)

object AppUpdateManager {

    private val httpClient = HttpClient()

    fun isVersionNewer(remote: String, current: String): Boolean {
        fun parseParts(v: String): List<Int> {
            val clean = v.trim().removePrefix("v").removePrefix("V").substringBefore("-").substringBefore("+")
            return clean.split(".").mapNotNull { it.toIntOrNull() }
        }

        val remoteParts = parseParts(remote)
        val currentParts = parseParts(current)

        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    suspend fun checkForUpdate(
        owner: String = "laita-dev",
        repo: String = "EnduLink"
    ): Result<AppReleaseInfo> {
        return try {
            val currentVersion = PlatformAppUpdater.getAppVersionName()
            val url = "https://api.github.com/repos/$owner/$repo/releases/latest"

            val response = httpClient.get(url) {
                headers {
                    append(HttpHeaders.UserAgent, "EnduLink-App/$currentVersion")
                    append(HttpHeaders.Accept, "application/vnd.github.v3+json")
                }
            }

            if (response.status == HttpStatusCode.NotFound) {
                // Aún no hay ninguna release publicada en el repositorio de GitHub
                return Result.success(
                    AppReleaseInfo(
                        tagName = "v$currentVersion",
                        versionName = currentVersion,
                        title = "Versión $currentVersion",
                        releaseNotes = "Aún no hay ninguna release publicada en GitHub.",
                        publishedAt = "",
                        apkDownloadUrl = null,
                        apkSizeMb = null,
                        ipaDownloadUrl = null,
                        ipaSizeMb = null,
                        htmlUrl = "https://github.com/$owner/$repo/releases",
                        isNewer = false
                    )
                )
            }

            if (response.status != HttpStatusCode.OK) {
                return Result.failure(Exception("GitHub API respondió con código: ${response.status.value}"))
            }

            val bodyText = response.bodyAsText()
            val json = Json.parseToJsonElement(bodyText).jsonObject

            val tagName = json["tag_name"]?.jsonPrimitive?.contentOrNull ?: ""
            val cleanVersion = tagName.removePrefix("v").removePrefix("V").trim()
            val title = json["name"]?.jsonPrimitive?.contentOrNull ?: tagName
            val releaseNotes = json["body"]?.jsonPrimitive?.contentOrNull ?: "Sin notas de versión disponibles."
            val publishedAt = json["published_at"]?.jsonPrimitive?.contentOrNull ?: ""
            val htmlUrl = json["html_url"]?.jsonPrimitive?.contentOrNull ?: "https://github.com/$owner/$repo/releases"

            var apkUrl: String? = null
            var apkSizeMb: Double? = null
            var ipaUrl: String? = null
            var ipaSizeMb: Double? = null

            val assets = json["assets"]?.jsonArray
            assets?.forEach { element ->
                val asset = element.jsonObject
                val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val dlUrl = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: ""
                val sizeBytes = asset["size"]?.jsonPrimitive?.longOrNull

                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = dlUrl
                    apkSizeMb = sizeBytes?.let { (it / (1024.0 * 1024.0) * 10.0).toLong() / 10.0 }
                } else if (name.endsWith(".ipa", ignoreCase = true)) {
                    ipaUrl = dlUrl
                    ipaSizeMb = sizeBytes?.let { (it / (1024.0 * 1024.0) * 10.0).toLong() / 10.0 }
                }
            }

            val isNewer = isVersionNewer(cleanVersion, currentVersion)

            Result.success(
                AppReleaseInfo(
                    tagName = tagName,
                    versionName = cleanVersion,
                    title = title,
                    releaseNotes = releaseNotes,
                    publishedAt = publishedAt,
                    apkDownloadUrl = apkUrl,
                    apkSizeMb = apkSizeMb,
                    ipaDownloadUrl = ipaUrl,
                    ipaSizeMb = ipaSizeMb,
                    htmlUrl = htmlUrl,
                    isNewer = isNewer
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

