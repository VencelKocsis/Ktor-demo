package hu.bme.aut.android.demo.plugins

import io.ktor.server.application.*
import io.ktor.server.websocket.*
import java.time.Duration

fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(10)
        timeout = Duration.ofSeconds(60)
    }
}