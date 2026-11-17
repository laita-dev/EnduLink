package com.oscar.endulink.bike

import kotlinx.serialization.Serializable

/**
 * Datos contenidos en el código QR de la pantalla EasyConn de la moto (ej. Rieju Aventura 500, CFMoto, etc.):
 * `http://www.carbit.com.cn/down6/...?modelid=37512&sn=XXXX&action=9&ssid=DIRECT-HJ-5G-XXXXXX&pwd=xxxxxxxx&auth=WPA-PSK&name=HJ-5G-XXXXXX`
 * También soporta códigos estándar `WIFI:T:WPA;S:...;P:...;;`.
 */
@Serializable
data class BikeQr(
    val name: String,
    val ssid: String,
    val password: String,
    val model: String,
    val serial: String
) {
    companion object {
        fun parse(text: String): BikeQr? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null

            if (trimmed.startsWith("WIFI:", ignoreCase = true)) {
                return parseStandardWifiQr(trimmed)
            }

            val qIndex = trimmed.indexOf('?')
            val hashIndex = trimmed.indexOf('#')
            val queryStart = when {
                qIndex != -1 -> qIndex + 1
                hashIndex != -1 && trimmed.indexOf('=', hashIndex) != -1 -> hashIndex + 1
                else -> -1
            }
            if (queryStart == -1) {
                return parseAccountOrSsidCode(trimmed)
            }
            val query = trimmed.substring(queryStart)
            if (query.isEmpty()) return null

            val fields = mutableMapOf<String, String>()
            for (pair in query.split("&")) {
                val parts = pair.split("=", limit = 2)
                if (parts.isEmpty() || parts[0].isEmpty()) continue
                val rawVal = if (parts.size > 1) parts[1] else ""
                val decoded = decodeUrlComponent(rawVal.replace("+", " "))
                fields[parts[0].lowercase()] = decoded
            }

            val ssidRaw = fields["ssid"]?.trim().orEmpty()
            val nameRaw = fields["name"]?.trim().orEmpty()
            val resolvedName = when {
                nameRaw.isNotEmpty() -> nameRaw
                ssidRaw.startsWith("DIRECT-", ignoreCase = true) -> ssidRaw.substring(7)
                else -> ssidRaw
            }

            if (resolvedName.isEmpty()) return null
            val resolvedSsid = when {
                ssidRaw.isNotEmpty() -> ssidRaw
                resolvedName.startsWith("HJ-", ignoreCase = true) &&
                    !resolvedName.startsWith("HJ-5G-", ignoreCase = true) ->
                    "DIRECT-HJ-5G-${resolvedName.substring(3)}"
                else -> "DIRECT-$resolvedName"
            }

            val rawPassword = (fields["pwd"] ?: fields["password"] ?: fields["pw"]).orEmpty()
            val authMode = fields["auth"]?.trim()?.uppercase().orEmpty()
            val resolvedPassword = if (rawPassword.isEmpty() && authMode != "OPEN" && authMode != "NONE") {
                "12345678"
            } else {
                rawPassword
            }

            return BikeQr(
                name = resolvedName,
                ssid = resolvedSsid,
                password = resolvedPassword,
                model = fields["modelid"] ?: "",
                serial = fields["sn"] ?: ""
            )
        }

        private fun parseAccountOrSsidCode(input: String): BikeQr? {
            val cleaned = input.trim().uppercase()
            if (cleaned.startsWith("DIRECT-")) {
                val displayName = cleaned.removePrefix("DIRECT-").ifEmpty { cleaned }
                return BikeQr(
                    name = displayName,
                    ssid = cleaned,
                    password = "12345678",
                    model = "",
                    serial = ""
                )
            }
            val hjMatch = Regex("^HJ-(?:5G-)?([A-Z0-9]{4,12})$").matchEntire(cleaned)
            if (hjMatch != null) {
                val suffix = hjMatch.groupValues[1]
                return BikeQr(
                    name = "HJ-$suffix",
                    ssid = "DIRECT-HJ-5G-$suffix",
                    password = "12345678",
                    model = "",
                    serial = ""
                )
            }
            if (Regex("^[0-9]{6}$").matches(cleaned)) {
                return BikeQr(
                    name = "HJ-$cleaned",
                    ssid = "DIRECT-HJ-5G-$cleaned",
                    password = "12345678",
                    model = "",
                    serial = ""
                )
            }
            return null
        }

        private fun parseStandardWifiQr(input: String): BikeQr? {
            val body = input.substring(5)
            val fields = mutableMapOf<String, String>()
            val token = StringBuilder()
            var escaped = false

            fun flushToken() {
                val part = token.toString()
                token.setLength(0)
                val colon = part.indexOf(':')
                if (colon > 0) {
                    val key = part.substring(0, colon).uppercase()
                    val value = part.substring(colon + 1)
                    fields[key] = value
                }
            }

            for (ch in body) {
                if (escaped) {
                    token.append(ch)
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == ';') {
                    flushToken()
                } else {
                    token.append(ch)
                }
            }
            if (token.isNotEmpty()) flushToken()

            val ssid = fields["S"]?.trim().orEmpty()
            if (ssid.isEmpty()) return null
            val name = ssid.removePrefix("DIRECT-").ifEmpty { ssid }
            return BikeQr(
                name = name,
                ssid = ssid,
                password = fields["P"].orEmpty(),
                model = "",
                serial = ""
            )
        }

        private fun decodeUrlComponent(input: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < input.length) {
                val c = input[i]
                if (c == '%' && i + 2 < input.length) {
                    val hex = input.substring(i + 1, i + 3)
                    val code = hex.toIntOrNull(16)
                    if (code != null) {
                        sb.append(code.toChar())
                        i += 3
                        continue
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }
    }
}

