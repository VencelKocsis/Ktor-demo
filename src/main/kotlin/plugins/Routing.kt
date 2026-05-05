package hu.bme.aut.android.demo.plugins

import hu.bme.aut.android.demo.routes.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.DefaultWebSocketServerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database

// A module() függvényből kapja meg a db-t
fun Application.configureRouting(
    db: Database,
    matchWsClients: MutableList<DefaultWebSocketServerSession>,
    json: Json
) {
    // Létrehozunk egy háttérszál-kezelőt a Ktor alkalmazás életciklusához kötve
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    routing {
        // Publikus végpontok
        clubAndTeamRoutes(db, applicationScope)
        matchRoutes(db, matchWsClients, json, applicationScope)
        notificationRoutes(db)
        authRoutes(db)
        racketRoutes(db)

        staticResources("/", "static") { default("admin_dashboard.html") }
    }
}