package hu.bme.aut.android.demo

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.send
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Duration

// ----- ÚJ DTO AZ FCM TOKEN FOGADÁSÁRA -----
@Serializable
data class FcmTokenRegistration(
    @SerialName("fcm_token") val fcmToken: String
)

// ----- DTO-k (megtartva) -----
@Serializable
data class PlayerDTO(val id: Int, val name: String, val age: Int?)

@Serializable
data class NewPlayerDTO(val name: String, val age: Int?)

// ----- WebSocket események (megtartva) -----
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class WsEvent {
    @Serializable
    @SerialName("PlayerAdded")
    data class PlayerAdded(val player: PlayerDTO) : WsEvent()

    @Serializable
    @SerialName("PlayerDeleted")
    data class PlayerDeleted(val id: Int) : WsEvent()

    @Serializable
    @SerialName("PlayerUpdated")
    data class PlayerUpdated(val player: PlayerDTO) : WsEvent()
}

// ----- Exposed táblák (Players megtartva) -----
object Players : Table("players") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", length = 100)
    val age = integer("age").nullable()
    override val primaryKey = PrimaryKey(id)
}

// ----- ÚJ Exposed tábla az FCM Tokenekhez -----
object FcmTokens : Table("fcm_tokens") {
    // A token maga az elsődleges kulcs, és egyedi (UNIQUE)
    val token = varchar("token", length = 255).uniqueIndex()
    // A regisztráció/utolsó frissítés ideje
    // JAVÍTVA: CurrentDateTime() helyett CurrentDateTime singleton
    val registeredAt = datetime("registered_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(token)
}


// ----- DB init (frissítve az FcmTokens táblával) -----
fun initDataSource(): HikariDataSource {
    val dbUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/demo"
    val dbUser = System.getenv("DB_USER") ?: "demo"
    val dbPassword = System.getenv("DB_PASSWORD") ?: "demo"

    val cfg = HikariConfig().apply {
        jdbcUrl = dbUrl
        username = dbUser
        password = dbPassword
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 3
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
    }

    return HikariDataSource(cfg)
}

fun initDatabase(ds: HikariDataSource): Database {
    val db = Database.connect(ds)
    transaction(db) {
        // Létrehozzuk az FcmTokens táblát is!
        SchemaUtils.create(Players, FcmTokens)
    }
    return db
}

// ----- Main (megtartva) -----
fun main() {
    val ds = initDataSource()
    val db = initDatabase(ds)
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module(db)
    }.start(wait = true)
}

fun Application.module(db: Database) {
    // Logoló inicializálása
    val logger = LoggerFactory.getLogger("KtorApplication")

    install(ContentNegotiation) { json() }
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(10)
        timeout = Duration.ofSeconds(60)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    val clients = mutableListOf<DefaultWebSocketServerSession>()
    val serializersModule = SerializersModule {
        polymorphic(WsEvent::class) {
            subclass(WsEvent.PlayerAdded::class, WsEvent.PlayerAdded.serializer())
            subclass(WsEvent.PlayerDeleted::class, WsEvent.PlayerDeleted.serializer())
            subclass(WsEvent.PlayerUpdated::class, WsEvent.PlayerUpdated.serializer())
        }
    }

    val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
    }

    routing {
        // ---------------------------------------------------------------------
        // POST /register_fcm_token - FCM Token regisztrálása/frissítése
        // ---------------------------------------------------------------------
        post("/register_fcm_token") {
            val registrationData = try {
                call.receive<FcmTokenRegistration>()
            } catch (e: Exception) {
                logger.warn("Received invalid body for token registration: ${e.message}")
                // call.respondText a `post` korutinon belül van, így ez rendben van
                call.respondText("Invalid request body (expected fcm_token)", status = HttpStatusCode.BadRequest)
                return@post
            }

            val token = registrationData.fcmToken

            // JAVÍTÁS: Külső változók a válasz státuszának és tartalmának tárolására
            var httpStatus: HttpStatusCode = HttpStatusCode.InternalServerError
            var responseBody: Map<String, String> = mapOf("status" to "Database initialization failure")

            try {
                // A transaction blokk befejeződik, mielőtt a call.respond() meghívásra kerülne
                transaction(db) {
                    val existing = FcmTokens.select { FcmTokens.token eq token }.singleOrNull()

                    if (existing == null) {
                        // Új token beszúrása
                        FcmTokens.insert {
                            it[FcmTokens.token] = token
                            it[registeredAt] = CurrentDateTime
                        }
                        logger.info("New FCM Token registered: $token")

                        // Csak a válasz változóit állítjuk be
                        httpStatus = HttpStatusCode.Created
                        responseBody = mapOf("status" to "Token registered successfully")

                    } else {
                        // Meglévő token időbélyegének frissítése
                        FcmTokens.update({ FcmTokens.token eq token }) {
                            it[registeredAt] = CurrentDateTime
                        }
                        logger.info("Existing FCM Token updated: $token")

                        // Csak a válasz változóit állítjuk be
                        httpStatus = HttpStatusCode.OK
                        responseBody = mapOf("status" to "Token updated successfully")
                    }
                }

                // JAVÍTÁS: A call.respond HÍVÁSA a tranzakciós blokkon KÍVÜL történik
                call.respond(httpStatus, responseBody)

            } catch (e: Exception) {
                logger.error("Database error during token registration: ${e.message}", e)
                call.respondText("Internal Server Error: Database operation failed.", status = HttpStatusCode.InternalServerError)
            }
        }
        // ---------------------------------------------------------------------


        // GET /players
        get("/players") {
            logger.info("Processing GET /players request.")
            val players = transaction(db) {
                Players.selectAll().map {
                    PlayerDTO(it[Players.id], it[Players.name], it[Players.age])
                }
            }
            logger.info("Found ${players.size} players. Responding to client.")
            call.respond(players)
        }

        // POST /players
        post("/players") {
            val newPlayer = call.receive<NewPlayerDTO>()
            logger.info("Processing POST /players request. Player: ${newPlayer.name}")

            val id = transaction(db) {
                Players.insert {
                    it[name] = newPlayer.name
                    it[age] = newPlayer.age
                } get Players.id
            }
            val player = PlayerDTO(id, newPlayer.name, newPlayer.age)

            // broadcast
            val event = WsEvent.PlayerAdded(player)
            val message = json.encodeToString(WsEvent.serializer(), event)

            logger.debug("Broadcasting PlayerAdded for ID $id to ${clients.size} clients.")

            clients.forEach { session ->
                session.send(message)
            }

            call.respond(player)
        }

        // DELETE /players/{id}
        delete("/players/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            logger.info("Processing DELETE /players/${id} request.")

            if (id != null) {
                val deletedCount = transaction(db) {
                    Players.deleteWhere { Players.id eq id }
                }

                if (deletedCount > 0) {
                    // broadcast
                    val event = WsEvent.PlayerDeleted(id)
                    val message = json.encodeToString(WsEvent.serializer(), event)
                    clients.forEach { session -> session.send(message) }
                    logger.debug("Successfully deleted player ID $id. Broadcasting PlayerDeleted to ${clients.size} clients.")
                    call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
                } else {
                    logger.warn("Delete failed. Player ID $id not found.")
                    call.respondText("Player not found", status = HttpStatusCode.NotFound)
                }
            } else {
                logger.warn("Invalid ID received for delete.")
                call.respondText("Invalid id", status = HttpStatusCode.BadRequest)
            }
        }

        // PUT /players/{id}
        put("/players/{id}") {
            val id = call.parameters["id"]?.toIntOrNull() ?: run {
                call.respondText("Invalid id", status = HttpStatusCode.BadRequest)
                logger.warn("Invalid ID received for update.")
                return@put
            }

            val updatedPlayerDTO = try {
                call.receive<NewPlayerDTO>()
            } catch (e: Exception) {
                call.respondText("Invalid request body", status = HttpStatusCode.BadRequest)
                logger.error("Invalid request body for update ID $id. Error: ${e.message}")
                return@put
            }

            logger.info("Processing PUT /players/$id request. New data: Name=${updatedPlayerDTO.name}")

            val updatedCount = transaction(db) {
                Players.update({ Players.id eq id }) {
                    it[name] = updatedPlayerDTO.name
                    it[age] = updatedPlayerDTO.age
                }
            }

            if (updatedCount > 0) {
                val updatedPlayer = PlayerDTO(id, updatedPlayerDTO.name, updatedPlayerDTO.age)
                val event = WsEvent.PlayerUpdated(updatedPlayer)
                val message = json.encodeToString(WsEvent.serializer(), event)

                logger.debug("Broadcasting PlayerUpdated for ID $id to ${clients.size} clients.")

                clients.forEach { session ->
                    session.send(message)
                }

                call.respond(HttpStatusCode.OK, updatedPlayer)
            } else {
                call.respondText("Player not found", status = HttpStatusCode.NotFound)
                logger.warn("Update failed. Player ID $id not found in DB.")
            }
        }

        // WebSocket endpoint
        webSocket("/ws/players") {
            logger.info("New WebSocket client connected. Current clients: ${clients.size + 1}")
            clients.add(this)
            try {
                incoming.consumeEach {
                    // Itt lehetne kezelni a bejövő üzeneteket, pl. a keep-alive pingeket
                }
            } finally {
                clients.remove(this)
                logger.info("WebSocket client disconnected. Remaining clients: ${clients.size}")
            }
        }

        // Statikus fájlok kiszolgálása
        staticResources("/", "static") {
            default("admin_dashboard.html")
        }
    }
}
