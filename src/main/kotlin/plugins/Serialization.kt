package hu.bme.aut.android.demo.plugins

import hu.bme.aut.android.demo.jsonFormatter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        // A globális jsonFormatter-t használjuk az Application.kt-ból
        json(jsonFormatter)
    }
}