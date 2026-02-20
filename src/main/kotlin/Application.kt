package hu.bme.aut.android.demo

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import hu.bme.aut.android.demo.model.*
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
import java.time.LocalDate

// ---------------- DTO-k ----------------

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
    val dbUrl = System.getenv("DB_URL")
        ?: "jdbc:postgresql://localhost:5432/demo"

    val dbUser = System.getenv("DB_USER")
        ?: "demo"

    val dbPassword = System.getenv("DB_PASSWORD")
        ?: "demo"

    appLog.info("DB_URL: $dbUrl")

    val cfg = HikariConfig().apply {
        jdbcUrl = dbUrl
        username = dbUser
        password = dbPassword
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 3
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"

        // Render SSL
        addDataSourceProperty("sslmode", "require")
    }

    appLog.info("🌐 Adatbázis inicializálás elindult.")
    return HikariDataSource(cfg)
}

fun initDatabase(ds: HikariDataSource): Database {
    val db = Database.connect(ds)
    transaction(db) {
        // LÉTREHOZZUK AZ ÖSSZES TÁBLÁT
        SchemaUtils.createMissingTablesAndColumns(Players, FcmTokens, // régiek
            Users, Clubs, Teams, TeamMembers, Seasons, Matches, IndividualGames // Az újak
        )

        // 2. BETÖLTJÜK A TESZTADATOKAT (Ha a tábla még üres)
        seedDatabaseIfNeeded()
    }
    appLog.info("✅ Adatbázis sikeresen inicializálva és táblák ellenőrizve.")

    return db
}

// TESZTADATOK BETÖLTÉSE
fun seedDatabaseIfNeeded() {
    // Csak akkor töltjük fel, ha még nincsenek klubok
    if (Clubs.selectAll().count() > 0) {
        return // Már fel van töltve
    }

    appLog.info("🌱 Tesztadatok betöltése folyamatban...")

    // 1. Szezonok
    Seasons.insert {
        it[name] = "2025 Ősz"
        it[startDate] = LocalDate.of(2025, 9, 1)
        it[endDate] = LocalDate.of(2025, 12, 15)
        it[isActive] = false
    }
    Seasons.insert {
        it[name] = "2026 Tavasz"
        it[startDate] = LocalDate.of(2026, 2, 1)
        it[endDate] = LocalDate.of(2026, 5, 31)
        it[isActive] = true
    }

    // 2. Klubok
    val beacId = Clubs.insertAndGetId {
        it[name] = "BEAC"
        it[address] = "1117 Budapest, Bogdánfy u. 10."
    }
    val mafcId = Clubs.insertAndGetId {
        it[name] = "MAFC"
        it[address] = "1111 Budapest, Műegyetem rkp. 3."
    }

    // 3. Csapatok
    val beac1 = Teams.insertAndGetId { it[clubId] = beacId; it[name] = "BEAC I."; it[division] = "NB I." }
    val beac2 = Teams.insertAndGetId { it[clubId] = beacId; it[name] = "BEAC II."; it[division] = "Budapest I." }
    val mafc1 = Teams.insertAndGetId { it[clubId] = mafcId; it[name] = "MAFC I."; it[division] = "NB I." }
    val mafc2 = Teams.insertAndGetId { it[clubId] = mafcId; it[name] = "MAFC II."; it[division] = "Budapest I." }

    // 4. Játékosok és Csapattagok generálása
    val teams = listOf(beac1, beac2, mafc1, mafc2)
    var userCounter = 1

    for (team in teams) {
        for (i in 1..4) {
            val isCap = (i == 1) // Az első ember a csapatkapitány

            val userId = Users.insertAndGetId {
                it[email] = "player${userCounter}@test.com"
                it[passwordHash] = "hashed_pw" // Később ezt igazira kell cserélni
                it[firstName] = "Player"
                it[lastName] = userCounter.toString()
                it[role] = "user"
            }

            TeamMembers.insert {
                it[teamId] = team
                it[this.userId] = userId
                it[isCaptain] = isCap
                it[joinedAt] = LocalDate.now()
            }
            userCounter++
        }
    }
    appLog.info("✅ Tesztadatok (Klubok, Csapatok, Játékosok) sikeresen betöltve!")
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

    // --- Port dinamikus beolvasása ---
    // A Render.com a "PORT" környezeti változóban mondja meg, hol kell futnia az appnak.
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(db)
    }.start(wait = true)
    appLog.info("🚀 Ktor szerver elindult a $port-as porton.") // Log szerver induláskor
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

        get("/teams") {
            appLog.info("📥 GET /teams lekérdezés...")

            try {
                val teamsResponse = transaction(db) {
                    // Végigmegyünk a csapatokon
                    Teams.selectAll().map { teamRow ->
                        val tId = teamRow[Teams.id].value

                        // Megkeressük a csapat klubját
                        val clubRow = Clubs.select { Clubs.id eq teamRow[Teams.clubId] }.single()

                        // Megkeressük a csapat tagjait (INNER JOIN)
                        val membersList = (TeamMembers innerJoin Users)
                            .select { TeamMembers.teamId eq tId }
                            .map { memberRow ->
                                MemberDTO(
                                    userId = memberRow[Users.id].value,
                                    name = "${memberRow[Users.lastName]} ${memberRow[Users.firstName]}",
                                    isCaptain = memberRow[TeamMembers.isCaptain]
                                )
                            }

                        TeamWithMembersDTO(
                            teamId = tId,
                            teamName = teamRow[Teams.name],
                            clubName = clubRow[Clubs.name],
                            division = teamRow[Teams.division],
                            members = membersList
                        )
                    }
                }

                call.respond(teamsResponse)

            } catch (e: Exception) {
                appLog.error("Hiba a /teams lekérdezésekor: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError, "Adatbázis hiba történt")
            }
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