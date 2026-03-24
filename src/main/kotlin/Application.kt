package hu.bme.aut.android.demo

import hu.bme.aut.android.demo.database.DatabaseFactory
import hu.bme.aut.android.demo.plugins.*
import hu.bme.aut.android.demo.service.FirebaseService
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.serialization.json.Json

// ---------------- Globális változók a Websockethez és a JSON-hoz ----------------
val matchWsClients = mutableListOf<DefaultWebSocketServerSession>()
val jsonFormatter = Json { classDiscriminator = "type"; encodeDefaults = true }

// ---------------- Main Indítás ----------------
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

// ---------------- Ktor Application Module ----------------
fun Application.module() {

    // 1. Háttérszolgáltatások és Adatbázis inicializálása
    FirebaseService.init()
    val db = DatabaseFactory.init()

    // 2. Ktor Pluginok betöltése a külön fájlokból (plugins mappa!)
    configureSerialization()
    configureSecurity()
    configureSockets()

    // 3. Végpontok (Routing) összekötése
    configureRouting(db, matchWsClients, jsonFormatter)
}