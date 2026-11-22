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

    fun compareVersions(v1: String, v2: String): Int {
        fun parseParts(v: String): List<Int> {
            val clean = v.trim().removePrefix("v").removePrefix("V").substringBefore("-").substringBefore("+")
            return clean.split(".").mapNotNull { it.toIntOrNull() }
        }

        fun extractSuffix(v: String): String {
            val noPrefix = v.trim().removePrefix("v").removePrefix("V").substringBefore("+")
            return if (noPrefix.contains("-")) noPrefix.substringAfter("-").lowercase() else ""
        }

        fun tokenizeSuffix(s: String): List<String> {
            val tokens = mutableListOf<String>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c.isDigit()) {
                    var j = i + 1
                    while (j < s.length && s[j].isDigit()) j++
                    tokens.add(s.substring(i, j))
                    i = j
                } else if (c.isLetter()) {
                    var j = i + 1
                    while (j < s.length && s[j].isLetter()) j++
                    tokens.add(s.substring(i, j))
                    i = j
                } else {
                    i++
                }
            }
            return tokens
        }

        val p1 = parseParts(v1)
        val p2 = parseParts(v2)

        val maxLen = maxOf(p1.size, p2.size)
        for (i in 0 until maxLen) {
            val r1 = p1.getOrElse(i) { 0 }
            val r2 = p2.getOrElse(i) { 0 }
            if (r1 > r2) return 1
            if (r1 < r2) return -1
        }

        val s1 = extractSuffix(v1)
        val s2 = extractSuffix(v2)
        if (s1 == s2) return 0
        if (s1.isEmpty()) return 1
        if (s2.isEmpty()) return -1

        val t1 = tokenizeSuffix(s1)
        val t2 = tokenizeSuffix(s2)
        val maxTok = maxOf(t1.size, t2.size)
        for (i in 0 until maxTok) {
            val a = t1.getOrNull(i) ?: return -1
            val b = t2.getOrNull(i) ?: return 1
            val aNum = a.toIntOrNull()
            val bNum = b.toIntOrNull()
            val cmp = when {
                aNum != null && bNum != null -> aNum.compareTo(bNum)
                else -> a.compareTo(b)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    fun isVersionNewer(remote: String, current: String): Boolean {
        return compareVersions(remote, current) > 0
    }

    internal fun selectReleaseForChannel(
        releases: JsonArray,
        isTestChannel: Boolean
    ): JsonObject? {
        val candidates = releases.mapNotNull { el ->
            val obj = el.jsonObject
            val isDraft = obj["draft"]?.jsonPrimitive?.booleanOrNull ?: false
            if (isDraft) return@mapNotNull null

            val isPrerelease = obj["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false
            val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val cleanTag = tag.removePrefix("v").removePrefix("V")
            val entryIsTest = isPrerelease || cleanTag.contains("-")
            if (entryIsTest == isTestChannel) obj else null
        }

        return candidates.maxWithOrNull { a, b ->
            val tagA = a["tag_name"]?.jsonPrimitive?.contentOrNull ?: ""
            val tagB = b["tag_name"]?.jsonPrimitive?.contentOrNull ?: ""
            compareVersions(tagA, tagB)
        }
    }

    suspend fun checkForUpdate(
        owner: String = "laita-dev",
        repo: String = "EnduLink"
    ): Result<AppReleaseInfo> {
        return try {
            val currentVersion = PlatformAppUpdater.getAppVersionName()
            val testChannel = PlatformAppUpdater.isTestChannel() || currentVersion.contains("-")
            val url = "https://api.github.com/repos/$owner/$repo/releases"

            val response = httpClient.get(url) {
                headers {
                    append(HttpHeaders.UserAgent, "EnduLink-App/$currentVersion")
                    append(HttpHeaders.Accept, "application/vnd.github.v3+json")
                }
            }

            if (response.status == HttpStatusCode.NotFound) {
                return Result.failure(
                    Exception("No se pudo acceder a las releases de GitHub (404). Si el repositorio es privado, hazlo Público en los ajustes de GitHub para permitir la descarga de actualizaciones.")
                )
            }

            if (response.status != HttpStatusCode.OK) {
                return Result.failure(Exception("GitHub API respondió con código: ${response.status.value}"))
            }

            val bodyText = response.bodyAsText()
            val jsonArray = Json.parseToJsonElement(bodyText).jsonArray

            val json = selectReleaseForChannel(jsonArray, isTestChannel = testChannel)
            if (json == null) {
                val emptyMsg = if (testChannel) {
                    "Aún no hay ninguna pre-release de test publicada en GitHub."
                } else {
                    "Aún no hay ninguna release oficial publicada en GitHub."
                }
                return Result.success(
                    AppReleaseInfo(
                        tagName = "v$currentVersion",
                        versionName = currentVersion,
                        title = "Versión $currentVersion",
                        releaseNotes = emptyMsg,
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

            val tagName = json["tag_name"]?.jsonPrimitive?.contentOrNull ?: ""
            val cleanVersion = tagName.removePrefix("v").removePrefix("V").trim()
            val title = json["name"]?.jsonPrimitive?.contentOrNull ?: tagName
            val rawNotes = json["body"]?.jsonPrimitive?.contentOrNull?.trim()
            val releaseNotes = if (rawNotes.isNullOrBlank() || rawNotes.equals("null", ignoreCase = true) || rawNotes.contains("Sin notas", ignoreCase = true)) {
                "• Mejoras de rendimiento en navegación y renderizado de mapas\n• Estabilidad en la conexión con la moto y cuadro TFT\n• Correcciones de errores y optimización general"
            } else {
                rawNotes
            }
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

