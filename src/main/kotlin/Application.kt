package hu.bme.aut.android.demo

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.server.http.content.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.time.Duration

// ---------------- DTO-k ----------------

@Serializable
data class FcmTokenRegistration(
    val email: String,
    val token: String
)

@Serializable
data class PlayerDTO(
    val id: Int,
    val name: String,
    val age: Int?,
    val email: String
)

@Serializable
data class NewPlayerDTO(
    val name: String,
    val age: Int?,
    val email: String
)

@Serializable
data class SendNotificationRequest(
    val targetEmail: String,
    val title: String,
    val body: String
)

// ---------------- Exposed táblák ----------------

object Players : IntIdTable("players") {
    val name = varchar("name", 100)
    val age = integer("age").nullable()
    val email = varchar("email", 150)
}

object FcmTokens : Table("fcm_tokens") {
    val email = varchar("email", 150).uniqueIndex()
    val token = text("token")
    override val primaryKey = PrimaryKey(email)
}

// ---------------- WebSocket események ----------------

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

// ---------------- Adatbázis init ----------------

fun initDataSource(): HikariDataSource {
    val dbUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/demo"
    val dbUser = System.getenv("DB_USER") ?: "demo"
    val dbPassword = System.getenv("DB_PASSWORD") ?: "demo"

    val jdbcUrlWithSsl = "$dbUrl?sslmode=require"

    val cfg = HikariConfig().apply {
        jdbcUrl = jdbcUrlWithSsl
        username = dbUser
        password = dbPassword
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 3
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
    }
    appLog.info("🌐 Adatbázis inicializálás elindult.")

    return HikariDataSource(cfg)
}

fun initDatabase(ds: HikariDataSource): Database {
    val db = Database.connect(ds)
    transaction(db) {
        // Csak akkor hozzuk létre, ha hiányzik
        SchemaUtils.createMissingTablesAndColumns(Players, FcmTokens)
    }
    appLog.info("✅ Adatbázis sikeresen inicializálva és táblák ellenőrizve.")

    return db
}

// ---------------- Globális változók ----------------

private val appLog = LoggerFactory.getLogger("KtorDemo")
private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// ---------------- Segédfüggvények ----------------

fun sendFcmNotification(token: String, title: String, body: String) {
    if (FirebaseApp.getApps().isEmpty()) {
        appLog.warn("Firebase nincs inicializálva, nem küldhető FCM.")
        return
    }

    applicationScope.launch {
        try {
            val message = Message.builder()
                .setToken(token)
                .setNotification(
                    Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build()
                )
                .build()

            appLog.info("🚀 FCM üzenet küldése indult. Cím: '$title'")

            val response = FirebaseMessaging.getInstance().send(message)
            appLog.info("✅ FCM üzenet elküldve: $response")
        } catch (e: Exception) {
            appLog.error("❌ FCM küldési hiba: ${e.message}")
        }
    }
}

fun savePlayer(db: Database, player: NewPlayerDTO): PlayerDTO {
    appLog.info("💾 Játékos mentése adatbázisba: ${player.name}")

    val id = transaction(db) {
        Players.insertAndGetId {
            it[name] = player.name
            it[age] = player.age
            it[email] = player.email
        }.value
    }
    val savedPlayer = PlayerDTO(id, player.name, player.age, player.email)
    appLog.info("✅ Játékos sikeresen mentve. ID: $id, Email: ${player.email}")
    return savedPlayer
}

// ---------------- Main ----------------

fun main() {
    val ds = initDataSource()
    val db = initDatabase(ds)
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module(db)
    }.start(wait = true)
    appLog.info("🚀 Ktor szerver elindult a 8080-as porton.") // Log szerver induláskor
}

// ---------------- Application modul ----------------

fun Application.module(db: Database) {
    // Firebase init
    val firebaseServiceAccountKey = System.getenv("FIREBASE_SERVICE_ACCOUNT_KEY")
    if (!firebaseServiceAccountKey.isNullOrBlank()) {
        try {
            val credentials = GoogleCredentials.fromStream(ByteArrayInputStream(firebaseServiceAccountKey.toByteArray()))
            val options = FirebaseOptions.builder().setCredentials(credentials).build()
            FirebaseApp.initializeApp(options)
            appLog.info("✅ Firebase inicializálva.")
        } catch (e: Exception) {
            appLog.error("❌ Firebase inicializálás sikertelen: ${e.message}")
        }
    } else {
        appLog.warn("⚠️ FIREBASE_SERVICE_ACCOUNT_KEY nincs beállítva.")
    }

    install(ContentNegotiation) { json() }
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(10)
        timeout = Duration.ofSeconds(60)
    }

    val clients = mutableListOf<DefaultWebSocketServerSession>()
    val json = Json { classDiscriminator = "type"; encodeDefaults = true }

    routing {
        // ---------------- FCM üzenet küldés ----------------
        post("/send_fcm_notification") {
            val request = call.receive<SendNotificationRequest>()

            appLog.info("📩 FCM küldési kérés érkezett. Cél: ${request.targetEmail}")

            val targetToken = transaction(db) {
                FcmTokens.select { FcmTokens.email eq request.targetEmail }
                    .singleOrNull()?.get(FcmTokens.token)
            }

            if (targetToken == null) {
                appLog.warn("🛑 Hiba: FCM token nem található ehhez az e-mailhez: ${request.targetEmail}")
                call.respond(HttpStatusCode.NotFound, "Nincs token ehhez az e-mailhez: ${request.targetEmail}")
                return@post
            }

            sendFcmNotification(targetToken, request.title, request.body)
            call.respond(HttpStatusCode.OK, mapOf("status" to "sent"))
        }

        // ---------------- FCM token regisztrálás ----------------
        post("/register_fcm_token") {
            val registration = call.receive<FcmTokenRegistration>()
            transaction(db) {
                FcmTokens.replace {
                    it[email] = registration.email
                    it[token] = registration.token
                }
            }
            appLog.info("✅ FCM token regisztrálva/frissítve: Email=${registration.email}") // Logolás hozzáadva
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        // ---------------- Játékos CRUD ----------------
        get("/players") {
            val players = transaction(db) {
                Players.selectAll().map {
                    PlayerDTO(
                        it[Players.id].value,
                        it[Players.name],
                        it[Players.age],
                        it[Players.email]
                    )
                }
            }
            appLog.info("📥 GET /players lekérdezés: ${players.size} játékos visszaadva.")
            call.respond(players)
        }

        post("/players") {
            val player = call.receive<NewPlayerDTO>()
            val saved = savePlayer(db, player)

            val event = WsEvent.PlayerAdded(saved)
            val message = json.encodeToString(WsEvent.serializer(), event)
            clients.forEach { it.send(message) }

            appLog.info("📣 WS: PlayerAdded broadcastolva ${clients.size} kliensnek. Player ID: ${saved.id}")
            clients.forEach {
                it.send(message)
            }

            call.respond(HttpStatusCode.Created, saved)
        }

        put("/players/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid ID")

            appLog.info("📝 PUT /players/$id szerkesztési kérés.")

            val updated = call.receive<NewPlayerDTO>()
            val rowAffected = transaction(db) {
                Players.update({ Players.id eq id }) {
                    it[name] = updated.name
                    it[age] = updated.age
                    it[email] = updated.email
                }
            }

            if (rowAffected == 0) {
                appLog.warn("🛑 Hiba: Játékos nem található az ID: $id alatt.")
                call.respond(HttpStatusCode.NotFound, "Player not found")
                return@put
            }

            val saved = PlayerDTO(id, updated.name, updated.age, updated.email)
            val event = WsEvent.PlayerUpdated(saved)
            val message = json.encodeToString(WsEvent.serializer(), event)

            appLog.info("📣 WS: PlayerUpdated broadcastolva ${clients.size} kliensnek. Player ID: $id")
            clients.forEach { it.send(message) }

            call.respond(saved)
        }

        delete("/players/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid ID")

            appLog.info("🗑️ DELETE /players/$id törlési kérés.")

            transaction(db) { Players.deleteWhere { Players.id eq id } }

            val event = WsEvent.PlayerDeleted(id)
            val message = json.encodeToString(WsEvent.serializer(), event)

            appLog.info("📣 WS: PlayerDeleted broadcastolva ${clients.size} kliensnek. Player ID: $id")
            clients.forEach { it.send(message) }

            call.respond(HttpStatusCode.OK)
        }

        // ---------------- WebSocket ----------------
        webSocket("/ws/players") {
            appLog.info("🔗 Új WebSocket kliens csatlakozott. Jelenlegi kliensek száma: ${clients.size + 1}")
            clients.add(this)
            try {
                incoming.consumeEach { }
            } finally {
                clients.remove(this)
                appLog.info("💔 WebSocket kliens lekapcsolódott. Jelenlegi kliensek száma: ${clients.size}")
            }
        }

        staticResources("/", "static") { default("admin_dashboard.html") }
    }
}