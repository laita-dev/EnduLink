package com.oscar.endulink.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlin.concurrent.Volatile

enum class TftNotificationCategory(val defaultAccentArgb: Long) {
    MESSAGE(0xFF25D366L),
    CALL(0xFF30D158L),
    NAVIGATION(0xFF00D4FFL),
    ALERT(0xFFFF453AL),
    APP(0xFFFF6B00L)
}

data class TftPhoneNotification(
    val id: String,
    val appName: String,
    val title: String,
    val text: String,
    val category: TftNotificationCategory = TftNotificationCategory.APP,
    val accentArgb: Long = category.defaultAccentArgb,
    val postedAtEpochMs: Long,
    val durationMs: Long
) {
    fun isActive(nowMs: Long = Clock.System.now().toEpochMilliseconds()): Boolean {
        return nowMs in postedAtEpochMs..(postedAtEpochMs + durationMs)
    }

    fun elapsedMs(nowMs: Long = Clock.System.now().toEpochMilliseconds()): Long {
        return (nowMs - postedAtEpochMs).coerceAtLeast(0L)
    }

    fun remainingMs(nowMs: Long = Clock.System.now().toEpochMilliseconds()): Long {
        return (postedAtEpochMs + durationMs - nowMs).coerceAtLeast(0L)
    }

    fun remainingProgress(nowMs: Long = Clock.System.now().toEpochMilliseconds()): Float {
        if (durationMs <= 0L) return 0f
        val elapsed = (nowMs - postedAtEpochMs).toFloat()
        return (1f - elapsed / durationMs.toFloat()).coerceIn(0f, 1f)
    }
}

object PhoneNotificationCenter {
    private const val DEDUPE_WINDOW_MS = 2_500L
    private const val MIN_DURATION_SEC = 3
    private const val MAX_DURATION_SEC = 15

    private val lock = SynchronizedLock()
    private val _activeNotification = MutableStateFlow<TftPhoneNotification?>(null)
    val activeNotification: StateFlow<TftPhoneNotification?> = _activeNotification.asStateFlow()

    @Volatile
    var enabledOnTft: Boolean = true

    @Volatile
    var defaultDurationSeconds: Int = 6

    private var lastSignature: String? = null
    private var lastSignatureAtMs: Long = 0L
    private var sampleIndex: Int = 0
    private var seq: Long = 0L

    fun postNotification(
        appName: String,
        title: String,
        text: String,
        category: TftNotificationCategory = TftNotificationCategory.APP,
        accentArgb: Long? = null,
        durationSeconds: Int? = null,
        key: String? = null,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
        bypassDedupe: Boolean = false
    ) {
        if (!enabledOnTft) return

        val cleanApp = sanitizeLine(appName).ifEmpty { "Móvil" }.take(28)
        val cleanTitle = sanitizeLine(title).take(80)
        val cleanText = sanitizeLine(text).take(180)
        if (cleanTitle.isEmpty() && cleanText.isEmpty()) return

        val finalTitle = cleanTitle.ifEmpty { cleanApp }
        val signature = "$cleanApp|$finalTitle|$cleanText"
        val resolvedSeconds = (durationSeconds ?: defaultDurationSeconds).coerceIn(MIN_DURATION_SEC, MAX_DURATION_SEC)
        val durationMs = resolvedSeconds * 1000L

        lock.withLock {
            if (!bypassDedupe &&
                signature == lastSignature &&
                (nowMs - lastSignatureAtMs) in 0L..DEDUPE_WINDOW_MS
            ) {
                return
            }
            lastSignature = signature
            lastSignatureAtMs = nowMs
            seq += 1L

            val resolvedId = key?.takeIf { it.isNotBlank() } ?: "notif_${nowMs}_$seq"
            _activeNotification.value = TftPhoneNotification(
                id = resolvedId,
                appName = cleanApp,
                title = finalTitle,
                text = cleanText,
                category = category,
                accentArgb = accentArgb ?: category.defaultAccentArgb,
                postedAtEpochMs = nowMs,
                durationMs = durationMs
            )
        }
    }

    fun getActiveNotification(nowMs: Long = Clock.System.now().toEpochMilliseconds()): TftPhoneNotification? {
        return lock.withLock {
            val cur = _activeNotification.value ?: return@withLock null
            if (!cur.isActive(nowMs)) {
                _activeNotification.value = null
                null
            } else {
                cur
            }
        }
    }

    fun dismissByKey(key: String) {
        if (key.isBlank()) return
        lock.withLock {
            if (_activeNotification.value?.id == key) {
                _activeNotification.value = null
            }
        }
    }

    fun dismissActive() {
        lock.withLock {
            _activeNotification.value = null
        }
    }

    fun postSampleNotification(
        durationSeconds: Int? = null,
        nowMs: Long = Clock.System.now().toEpochMilliseconds()
    ) {
        val idx = lock.withLock {
            val current = sampleIndex
            sampleIndex = (sampleIndex + 1) % 3
            current
        }
        when (idx) {
            0 -> postNotification(
                appName = "WhatsApp",
                title = "Carlos (Grupo Sierra)",
                text = "Ojo que hay una cancela cerrada en la bajada del cortafuegos",
                category = TftNotificationCategory.MESSAGE,
                accentArgb = 0xFF25D366L,
                durationSeconds = durationSeconds,
                nowMs = nowMs,
                bypassDedupe = true
            )
            1 -> postNotification(
                appName = "Llamada entrante",
                title = "Marcos",
                text = "Llamando al móvil...",
                category = TftNotificationCategory.CALL,
                accentArgb = 0xFF30D158L,
                durationSeconds = durationSeconds,
                nowMs = nowMs,
                bypassDedupe = true
            )
            else -> postNotification(
                appName = "Telegram",
                title = "Enduro Madrid",
                text = "Parada en la gasolinera de Torrelaguna en 5 min",
                category = TftNotificationCategory.MESSAGE,
                accentArgb = 0xFF2AABEEL,
                durationSeconds = durationSeconds,
                nowMs = nowMs,
                bypassDedupe = true
            )
        }
    }

    internal fun resetForTesting() {
        lock.withLock {
            _activeNotification.value = null
            lastSignature = null
            lastSignatureAtMs = 0L
            sampleIndex = 0
            seq = 0L
            enabledOnTft = true
            defaultDurationSeconds = 6
        }
    }

    private fun sanitizeLine(raw: String): String {
        if (raw.isBlank()) return ""
        val sb = StringBuilder(raw.length)
        var prevWasSpace = true
        for (ch in raw) {
            val mapped = if (ch == '\r' || ch == '\n' || ch == '\t' || ch.isWhitespace()) ' ' else ch
            if (mapped == ' ') {
                if (!prevWasSpace) {
                    sb.append(' ')
                    prevWasSpace = true
                }
            } else {
                sb.append(mapped)
                prevWasSpace = false
            }
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
            sb.deleteAt(sb.length - 1)
        }
        return sb.toString()
    }
}

/**
 * Utilidad compartida entre Android e iOS para truncar y envolver textos en las tarjetas de notificación del TFT.
 */
internal object TftNotificationTextLayout {
    fun truncateToWidth(
        text: String,
        maxWidth: Float,
        measureWidth: (String) -> Float
    ): String {
        val clean = text.trim()
        if (clean.isEmpty() || maxWidth <= 0f) return ""
        if (measureWidth(clean) <= maxWidth) return clean

        val ellipsis = "..."
        var lo = 0
        var hi = clean.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            val candidate = clean.substring(0, mid).trimEnd() + ellipsis
            if (measureWidth(candidate) <= maxWidth) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }
        return if (lo <= 0) ellipsis else clean.substring(0, lo).trimEnd() + ellipsis
    }

    fun wrapLines(
        text: String,
        maxWidth: Float,
        maxLines: Int,
        measureWidth: (String) -> Float
    ): List<String> {
        val clean = text.trim()
        if (clean.isEmpty() || maxLines <= 0 || maxWidth <= 0f) return emptyList()
        if (maxLines == 1 || measureWidth(clean) <= maxWidth) {
            return listOf(truncateToWidth(clean, maxWidth, measureWidth))
        }

        val words = clean.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()

        val lines = ArrayList<String>(maxLines)
        var idx = 0
        while (idx < words.size && lines.size < maxLines) {
            val isLastLine = lines.size == maxLines - 1
            if (isLastLine) {
                val remainder = words.subList(idx, words.size).joinToString(" ")
                lines.add(truncateToWidth(remainder, maxWidth, measureWidth))
                break
            }

            var current = words[idx]
            idx++
            if (measureWidth(current) > maxWidth) {
                // Palabra demasiado larga: partir por ancho
                val fitted = splitPrefixForWidth(current, maxWidth, measureWidth)
                lines.add(fitted.first)
                if (fitted.second.isNotEmpty()) {
                    val restList = ArrayList<String>(words.size - idx + 1)
                    restList.add(fitted.second)
                    for (k in idx until words.size) restList.add(words[k])
                    val remainder = restList.joinToString(" ")
                    lines.add(truncateToWidth(remainder, maxWidth, measureWidth))
                    break
                }
                continue
            }

            while (idx < words.size) {
                val candidate = "$current ${words[idx]}"
                if (measureWidth(candidate) <= maxWidth) {
                    current = candidate
                    idx++
                } else {
                    break
                }
            }
            lines.add(current)
        }
        return lines
    }

    private fun splitPrefixForWidth(
        word: String,
        maxWidth: Float,
        measureWidth: (String) -> Float
    ): Pair<String, String> {
        var lo = 1
        var hi = word.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (measureWidth(word.substring(0, mid)) <= maxWidth) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }
        val splitIdx = lo.coerceIn(1, word.length)
        return word.substring(0, splitIdx) to word.substring(splitIdx)
    }
}

expect fun isPhoneNotificationAccessGranted(): Boolean
expect fun openPhoneNotificationAccessSettings()
expect fun supportsSystemWideNotificationCapture(): Boolean
