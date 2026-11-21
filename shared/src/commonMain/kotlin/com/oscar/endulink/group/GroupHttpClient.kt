package com.oscar.endulink.group

import io.ktor.client.HttpClient

/**
 * Crea un cliente HTTP/WebSocket optimizado para sincronización de grupo,
 * manteniendo enrutamiento por datos móviles (4G/5G) en Android aunque el teléfono
 * esté conectado al Wi-Fi sin Internet de la pantalla TFT de la moto.
 */
expect fun createGroupHttpClient(): HttpClient
