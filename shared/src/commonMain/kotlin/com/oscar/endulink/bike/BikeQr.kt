package com.oscar.endulink.bike

/**
 * Datos contenidos en el código QR de la pantalla EasyConn de la moto (ej. Rieju Aventura 500, CFMoto, etc.):
 * `http://www.carbit.com.cn/down6/...?modelid=37512&sn=XXXX&action=9&ssid=DIRECT-HJ-5G-XXXXXX&pwd=xxxxxxxx&auth=WPA-PSK&name=HJ-5G-XXXXXX`
 */
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
                password = fields["pwd"] ?: "",
                model = fields["modelid"] ?: "",
                serial = fields["sn"] ?: ""
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

