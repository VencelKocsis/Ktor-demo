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
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.time.Duration

// ---------------- DTO-k ----------------

@Serializable
data class FcmTokenRegistration(val email: String, val token: String)

@Serializable
data class PlayerDTO(val id: Int, val name: String, val age: Int?)

@Serializable
data class NewPlayerDTO(val name: String, val age: Int?)

// ---------------- Exposed táblák ----------------

object Players : IntIdTable("players") {
    val name = varchar("name", length = 100)
    val age = integer("age").nullable()
    val userId = varchar("user_id", length = 100).uniqueIndex().nullable()
}

object FcmTokens : Table("fcm_tokens") {
    val email = varchar("email", length = 150).uniqueIndex()
    val token = varchar("token", length = 255)
    val registeredAt = datetime("registered_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(email)
}

@Serializable
data class SendNotificationRequest(
    val targetEmail: String,
    val title: String,
    val body: String
)

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

    return HikariDataSource(cfg)
}

fun initDatabase(ds: HikariDataSource): Database {
    val db = Database.connect(ds)
    transaction(db) {
        // !!! IDEIGLENES JAVÍTÁS A SÉMA HIBA ELKERÜLÉSÉRE !!!
        // Eldobjuk a régi (esetleg hibás) táblákat, hogy az új séma garantáltan létrejöjjön.
        SchemaUtils.drop(Players, FcmTokens)
        SchemaUtils.create(Players, FcmTokens)
    }
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

            val response = FirebaseMessaging.getInstance().send(message)
            appLog.info("✅ FCM üzenet sikeresen elküldve. Token: $token, Válasz ID: $response")
        } catch (e: Exception) {
            appLog.error("❌ FCM küldési hiba: ${e.message}")
        }
    }
}

fun savePlayer(db: Database, player: NewPlayerDTO): PlayerDTO {
    val id = transaction(db) {
        Players.insertAndGetId {
            it[name] = player.name
            it[age] = player.age
        }.value
    }
    return PlayerDTO(id, player.name, player.age)
}

// ---------------- Main ----------------

fun main() {
    val ds = initDataSource()
    val db = initDatabase(ds)
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module(db)
    }.start(wait = true)
}

// ---------------- Application modul ----------------

fun Application.module(db: Database) {
    // Firebase init
    val firebaseServiceAccountKey = System.getenv("FIREBASE_SERVICE_ACCOUNT_KEY")
    if (firebaseServiceAccountKey != null && firebaseServiceAccountKey.isNotBlank()) {
        try {
            val credentials =
                GoogleCredentials.fromStream(ByteArrayInputStream(firebaseServiceAccountKey.toByteArray()))
            val options = FirebaseOptions.builder().setCredentials(credentials).build()
            FirebaseApp.initializeApp(options)
            appLog.info("✅ Firebase inicializálva.")
        } catch (e: Exception) {
            appLog.error("❌ Firebase inicializálás sikertelen: ${e.message}")
        }
    } else {
        appLog.warn("⚠️ Nincs FIREBASE_SERVICE_ACCOUNT_KEY beállítva.")
    }

    install(ContentNegotiation) { json() }
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(10)
        timeout = Duration.ofSeconds(60)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    val clients = mutableListOf<DefaultWebSocketServerSession>()
    val json = Json { classDiscriminator = "type"; encodeDefaults = true }

    routing {
        post("/send_fcm_notification") {
            val request = call.receive<SendNotificationRequest>()
            val targetEmail = request.targetEmail

            val targetToken = transaction(db) {
                FcmTokens.select { FcmTokens.email eq targetEmail }
                    .singleOrNull()?.get(FcmTokens.token)
            }

            if (targetToken == null) {
                call.respond(HttpStatusCode.NotFound, "Nincs FCM token ehhez az e-mailhez: $targetEmail")
                return@post
            }

            // 📞 A közös segédfüggvény meghívása
            sendFcmNotification(
                token = targetToken,
                title = request.title,
                body = request.body
            )

            call.respond(HttpStatusCode.OK, mapOf("status" to "sent_via_admin_sdk"))
        }

        // Játékos frissítése (PUT)
        put("/players/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid ID")

            val updatedPlayer = call.receive<NewPlayerDTO>()

            val rowAffected = transaction(db) {
                Players.update({ Players.id eq id }) {
                    it[name] = updatedPlayer.name
                    it[age] = updatedPlayer.age
                    // A userId-t itt nem frissítjük, az a token regisztrációhoz kell
                }
            }

            if (rowAffected == 0) {
                call.respond(HttpStatusCode.NotFound, "Player not found")
                return@put
            }

            val saved = PlayerDTO(id, updatedPlayer.name, updatedPlayer.age)

            // WebSocket Broadcast
            val event = WsEvent.PlayerUpdated(saved)
            val message = json.encodeToString(WsEvent.serializer(), event)
            clients.forEach { session -> session.send(message) }

            call.respond(HttpStatusCode.OK, saved)
        }

        // FCM token regisztrálás
        post("/register_fcm_token") {
            val registration = call.receive<FcmTokenRegistration>()
            val token = registration.token
            val email = registration.email

            transaction(db) {
                FcmTokens.replace {
                    it[FcmTokens.email] = email
                    it[FcmTokens.token] = token
                    it[registeredAt] = CurrentDateTime
                }
            }
            appLog.info("✅ FCM token regisztrálva/frissítve: Email=$email, Token=$token")

            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        // Játékos lekérés
        get("/players") {
            val players = transaction(db) {
                Players.selectAll().map {
                    PlayerDTO(it[Players.id].value, it[Players.name], it[Players.age])
                }
            }
            call.respond(players)
        }

        // Új játékos mentése (ezt használjuk a meghívás szimulálására)
        post("/players") {
            val player = call.receive<NewPlayerDTO>()
            val saved = savePlayer(db, player)

            // A cél User ID, aminek a tokent regisztrálni kell.
            val targetUserId = "test-user-fcm-target"

            val targetToken = transaction(db) {
                FcmTokens.select { FcmTokens.email eq targetUserId }.singleOrNull()?.get(FcmTokens.token)
            }

            if (targetToken != null) {
                sendFcmNotification(
                    token = targetToken,
                    title = "Nevezés a versenyre!",
                    body = "${player.name} nevezett téged a 'Példa Kupa' versenyre."
                )
            } else {
                appLog.warn("⚠️ Nincs FCM token a(z) $targetUserId felhasználóhoz. Kérjük, győződjön meg róla, hogy az Android app elküldte a tokent erre a User ID-ra.")
            }

            // WebSocket Broadcast
            val event = WsEvent.PlayerAdded(saved)
            val message = json.encodeToString(WsEvent.serializer(), event)
            clients.forEach { session -> session.send(message) }

            call.respond(HttpStatusCode.Created, saved)
        }

        // Törlés
        delete("/players/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid ID")

            val name = transaction(db) {
                val row = Players.select { Players.id eq id }.singleOrNull()
                row?.get(Players.name)
            }

            if (name == null) {
                call.respond(HttpStatusCode.NotFound, "Player not found")
                return@delete
            }

            transaction(db) { Players.deleteWhere { Players.id eq id } }

            val event = WsEvent.PlayerDeleted(id)
            val message = json.encodeToString(WsEvent.serializer(), event)
            clients.forEach { session -> session.send(message) }

            call.respond(HttpStatusCode.OK)
        }

        // WebSocket
        webSocket("/ws/players") {
            clients.add(this)
            try {
                incoming.consumeEach { }
            } finally {
                clients.remove(this)
            }
        }

        staticResources("/", "static") {
            default("admin_dashboard.html")
        }
    }
}
