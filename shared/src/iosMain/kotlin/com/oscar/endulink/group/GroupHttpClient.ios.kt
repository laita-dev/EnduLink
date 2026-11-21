package com.oscar.endulink.group

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets

actual fun createGroupHttpClient(): HttpClient {
    return HttpClient(Darwin) {
        install(WebSockets) {
            pingInterval = 30_000L
        }
    }
}
