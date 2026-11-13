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
            if (qIndex == -1) return null
            val query = trimmed.substring(qIndex + 1)
            if (query.isEmpty()) return null

            val fields = mutableMapOf<String, String>()
            for (pair in query.split("&")) {
                val parts = pair.split("=", limit = 2)
                if (parts.isEmpty() || parts[0].isEmpty()) continue
                val rawVal = if (parts.size > 1) parts[1] else ""
                val decoded = decodeUrlComponent(rawVal.replace("+", " "))
                fields[parts[0].lowercase()] = decoded
            }

            val ssidRaw = fields["ssid"] ?: ""
            val nameRaw = fields["name"] ?: ""
            val resolvedName = when {
                nameRaw.isNotEmpty() -> nameRaw
                ssidRaw.startsWith("DIRECT-") -> ssidRaw.removePrefix("DIRECT-")
                else -> ssidRaw
            }

            if (resolvedName.isEmpty()) return null
            val resolvedSsid = if (ssidRaw.isEmpty()) "DIRECT-$resolvedName" else ssidRaw

            return BikeQr(
                name = resolvedName,
                ssid = resolvedSsid,
                password = fields["pwd"] ?: fields["password"] ?: fields["pw"] ?: "",
                model = fields["modelid"] ?: "",
                serial = fields["sn"] ?: ""
            )
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

