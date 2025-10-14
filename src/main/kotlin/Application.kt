package hu.bme.aut.android.demo

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.MulticastMessage
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
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
import java.io.ByteArrayInputStream
import java.time.Duration

// ----- DTO-k -----
@Serializable
data class FcmTokenRegistration(
    @SerialName("fcm_token") val fcmToken: String
)

@Serializable
data class PlayerDTO(val id: Int, val name: String, val age: Int?)

@Serializable
data class NewPlayerDTO(val name: String, val age: Int?)

// ----- WebSocket események -----
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

// ----- Exposed táblák -----
object Players : Table("players") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", length = 100)
    val age = integer("age").nullable()
    override val primaryKey = PrimaryKey(id)
}

object FcmTokens : Table("fcm_tokens") {
    val token = varchar("token", length = 255).uniqueIndex()
    val registeredAt = datetime("registered_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(token)
}

// ----- DB init -----
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
        SchemaUtils.create(Players, FcmTokens)
    }
    return db
}

// ----- Main -----
fun main() {
    val ds = initDataSource()
    val db = initDatabase(ds)
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module(db)
    }.start(wait = true)
}

// Globális CoroutineScope a Firebase aszinkron műveletekhez
private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


fun Application.module(db: Database) {
    // Logoló inicializálása (az első sorba helyezve)
    val log = LoggerFactory.getLogger("KtorApplication")

    // ---------------------------------------------------------------------
    // FCM Inicializálás
    // Cél: Beolvassa a privát kulcsot egyetlen hosszú környezeti változóból (Render)
    // ---------------------------------------------------------------------
    val firebaseServiceAccountKey = System.getenv("FIREBASE_SERVICE_ACCOUNT_KEY")

    if (firebaseServiceAccountKey != null && firebaseServiceAccountKey.isNotBlank()) {
        try {
            // A JSON string beolvasása bájtfolyamba
            val credentials = GoogleCredentials.fromStream(ByteArrayInputStream(firebaseServiceAccountKey.toByteArray()))

            val options = FirebaseOptions.builder()
                .setCredentials(credentials)
                // A Database URL-t kihagyhatjuk, ha csak FCM-et használunk, ahogy helyesen tette.
                .build()

            FirebaseApp.initializeApp(options)
            log.info("Firebase Admin SDK sikeresen inicializálva a környezeti változóból (FIREBASE_SERVICE_ACCOUNT_KEY).")
        } catch (e: Exception) {
            log.error("Hiba a Firebase Admin SDK inicializálása során. Ellenőrizze, hogy a kulcs JSON formátumú és EGY SORBAN van-e!", e)
        }
    } else {
        log.warn("A FIREBASE_SERVICE_ACCOUNT_KEY környezeti változó hiányzik vagy üres. A push értesítések küldése nem fog működni!")
    }

    // ---------------------------------------------------------------------
    // FCM Push Üzenet Küldő Függvény (VÁLTOZATLAN)
    // ---------------------------------------------------------------------

    /**
     * Lekéri az összes FCM tokent az adatbázisból és push üzenetet küld nekik.
     * @param title Az értesítés címe.
     * @param body Az értesítés tartalma.
     */
    fun sendPushNotification(title: String, body: String) {
        // Ellenőrizzük, hogy inicializálva van-e a Firebase
        if (FirebaseApp.getApps().isEmpty()) {
            log.warn("A Firebase nincs inicializálva, a push üzenet küldése kihagyva.")
            return
        }

        applicationScope.launch {
            try {
                // 1. Tokenek lekérése az adatbázisból
                val tokens = transaction(db) {
                    // Megszabadulhatunk a nagyon régi vagy érvénytelen tokenektől is itt, de a selectAll() most jó.
                    FcmTokens.selectAll().map { it[FcmTokens.token] }
                }

                if (tokens.isEmpty()) {
                    log.warn("Nincs regisztrált FCM token, a push üzenet küldése kihagyva.")
                    return@launch
                }

                // 2. Push üzenet összeállítása MulticastMessage-ként
                val multicastMessage = MulticastMessage.builder()
                    .setNotification(com.google.firebase.messaging.Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                    .addAllTokens(tokens)
                    .build()

                // 3. Üzenet küldése a Firebase-nek
                val response = FirebaseMessaging.getInstance().sendMulticast(multicastMessage)

                log.info("Push üzenet küldési eredmény: Sikeres: ${response.successCount}, Sikertelen: ${response.failureCount}")

                if (response.failureCount > 0) {
                    response.responses.forEachIndexed { index, result ->
                        if (!result.isSuccessful) {
                            // FIGYELEM: Itt a result.exception.errorCode használható a token érvénytelenítésére is, ha szükséges.
                            log.warn("Token: ${tokens[index]} sikertelen küldés: ${result.exception.message}")
                        }
                    }
                }

            } catch (e: Exception) {
                log.error("Hiba a Firebase push üzenet küldése során.", e)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Ktor Pluginok és Routing (VÁLTOZATLAN)
    // ---------------------------------------------------------------------

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
        // POST /register_fcm_token
        post("/register_fcm_token") {
            val registrationData = try {
                call.receive<FcmTokenRegistration>()
            } catch (e: Exception) {
                log.warn("Received invalid body for token registration: ${e.message}")
                call.respondText("Invalid request body (expected fcm_token)", status = HttpStatusCode.BadRequest)
                return@post
            }

            val token = registrationData.fcmToken

            var httpStatus: HttpStatusCode = HttpStatusCode.InternalServerError
            var responseBody: Map<String, String> = mapOf("status" to "Database initialization failure")

            try {
                transaction(db) {
                    val existing = FcmTokens.select { FcmTokens.token eq token }.singleOrNull()

                    if (existing == null) {
                        // Új token beszúrása
                        FcmTokens.insert {
                            it[FcmTokens.token] = token
                            it[registeredAt] = CurrentDateTime
                        }
                        log.info("New FCM Token registered: $token")
                        httpStatus = HttpStatusCode.Created
                        responseBody = mapOf("status" to "Token registered successfully")

                    } else {
                        // Meglévő token időbélyegének frissítése
                        FcmTokens.update({ FcmTokens.token eq token }) {
                            it[registeredAt] = CurrentDateTime
                        }
                        log.info("Existing FCM Token updated: $token")
                        httpStatus = HttpStatusCode.OK
                        responseBody = mapOf("status" to "Token updated successfully")
                    }
                }

                call.respond(httpStatus, responseBody)

            } catch (e: Exception) {
                log.error("Database error during token registration: ${e.message}", e)
                call.respondText("Internal Server Error: Database operation failed.", status = HttpStatusCode.InternalServerError)
            }
        }

        // GET /players
        get("/players") {
            log.info("Processing GET /players request.")
            val players = transaction(db) {
                Players.selectAll().map {
                    PlayerDTO(it[Players.id], it[Players.name], it[Players.age])
                }
            }
            log.info("Found ${players.size} players. Responding to client.")
            call.respond(players)
        }

        // POST /players
        post("/players") {
            val newPlayer = call.receive<NewPlayerDTO>()
            log.info("Processing POST /players request. Player: ${newPlayer.name}")

            val id = transaction(db) {
                Players.insert {
                    it[name] = newPlayer.name
                    it[age] = newPlayer.age
                } get Players.id
            }
            val player = PlayerDTO(id, newPlayer.name, newPlayer.age)

            // 1. Broadcast WS esemény
            val event = WsEvent.PlayerAdded(player)
            val message = json.encodeToString(WsEvent.serializer(), event)
            log.debug("Broadcasting PlayerAdded for ID $id to ${clients.size} clients.")
            clients.forEach { session ->
                session.send(message)
            }

            // 2. Push értesítés küldése
            sendPushNotification(
                title = "Új játékos!",
                body = "${player.name} (${player.age ?: "n/a"}) hozzá lett adva a listához."
            )

            call.respond(player)
        }

        // DELETE /players/{id}
        delete("/players/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            log.info("Processing DELETE /players/${id} request.")

            if (id != null) {
                var playerName: String? = null
                val deletedCount = transaction(db) {
                    playerName = Players.select(Players.id eq id).singleOrNull()?.get(Players.name)
                    Players.deleteWhere { Players.id eq id }
                }

                if (deletedCount > 0) {
                    // 1. Broadcast WS esemény
                    val event = WsEvent.PlayerDeleted(id)
                    val message = json.encodeToString(WsEvent.serializer(), event)
                    clients.forEach { session -> session.send(message) }
                    log.debug("Successfully deleted player ID $id. Broadcasting PlayerDeleted to ${clients.size} clients.")

                    // 2. Push értesítés küldése
                    sendPushNotification(
                        title = "Játékos törölve",
                        body = "${playerName ?: "Játékos ID $id"} el lett távolítva a listáról."
                    )

                    call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
                } else {
                    log.warn("Delete failed. Player ID $id not found.")
                    call.respondText("Player not found", status = HttpStatusCode.NotFound)
                }
            } else {
                log.warn("Invalid ID received for delete.")
                call.respondText("Invalid id", status = HttpStatusCode.BadRequest)
            }
        }

        // PUT /players/{id}
        put("/players/{id}") {
            val id = call.parameters["id"]?.toIntOrNull() ?: run {
                call.respondText("Invalid id", status = HttpStatusCode.BadRequest)
                log.warn("Invalid ID received for update.")
                return@put
            }

            val updatedPlayerDTO = try {
                call.receive<NewPlayerDTO>()
            } catch (e: Exception) {
                call.respondText("Invalid request body", status = HttpStatusCode.BadRequest)
                log.error("Invalid request body for update ID $id. Error: ${e.message}")
                return@put
            }

            log.info("Processing PUT /players/$id request. New data: Name=${updatedPlayerDTO.name}")

            val updatedCount = transaction(db) {
                Players.update({ Players.id eq id }) {
                    it[name] = updatedPlayerDTO.name
                    it[age] = updatedPlayerDTO.age
                }
            }

            if (updatedCount > 0) {
                val updatedPlayer = PlayerDTO(id, updatedPlayerDTO.name, updatedPlayerDTO.age)

                // 1. Broadcast WS esemény
                val event = WsEvent.PlayerUpdated(updatedPlayer)
                val message = json.encodeToString(WsEvent.serializer(), event)
                log.debug("Broadcasting PlayerUpdated for ID $id to ${clients.size} clients.")
                clients.forEach { session ->
                    session.send(message)
                }

                // 2. Push értesítés küldése
                sendPushNotification(
                    title = "Játékos adatok frissítve",
                    body = "${updatedPlayer.name} (ID $id) adatai frissítésre kerültek."
                )

                call.respond(HttpStatusCode.OK, updatedPlayer)
            } else {
                call.respondText("Player not found", status = HttpStatusCode.NotFound)
                log.warn("Update failed. Player ID $id not found in DB.")
            }
        }

        // WebSocket endpoint
        webSocket("/ws/players") {
            log.info("New WebSocket client connected. Current clients: ${clients.size + 1}")
            clients.add(this)
            try {
                incoming.consumeEach {
                    // A bejövő üzenetek kezelése itt történne
                }
            } finally {
                clients.remove(this)
                log.info("WebSocket client disconnected. Remaining clients: ${clients.size}")
            }
        }

        // Statikus fájlok kiszolgálása
        staticResources("/", "static") {
            default("admin_dashboard.html")
        }
    }
}
