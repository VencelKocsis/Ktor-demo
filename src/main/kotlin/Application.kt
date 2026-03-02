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
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.LocalDate
import io.ktor.server.auth.*
import com.google.firebase.auth.FirebaseAuth as FirebaseAdminAuth

// ---------------- DTO-k ----------------

// ---------------- EXPOSED TÁBLÁK DEFINÍCIÓI ----------------

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

object Seasons : IntIdTable("seasons") {
    val name = varchar("name", 100)
    val startDate = date("start_date")
    val endDate = date("end_date")
    val isActive = bool("is_active").default(false)
}

object Clubs : IntIdTable("clubs") {
    val name = varchar("name", 100)
    val address = varchar("address", 255)
}

object Users : IntIdTable("users") {
    val firebaseUid = varchar("firebase_uid", 128).uniqueIndex()
    val email = varchar("email", 100).uniqueIndex()
    val firstName = varchar("first_name", 100)
    val lastName = varchar("last_name", 100)
    val role = varchar("role", 20).default("user")
}

object Teams : IntIdTable("teams") {
    val clubId = reference("club_id", Clubs)
    val name = varchar("name", 100)
    val division = varchar("division", 50).nullable()
}

object TeamMembers : IntIdTable("team_members") {
    val teamId = reference("team_id", Teams)
    val userId = reference("user_id", Users)
    val isCaptain = bool("is_captain").default(false)
    val joinedAt = date("joined_at")
}

object Matches : IntIdTable("matches") {
    val seasonId = reference("season_id", Seasons)
    val roundNumber = integer("round_number")
    val homeTeamId = reference("home_team_id", Teams)
    val guestTeamId = reference("guest_team_id", Teams)
    val homeTeamScore = integer("home_team_score").default(0)
    val guestTeamScore = integer("guest_team_score").default(0)
    val matchDate = datetime("match_date").nullable()
    val status = varchar("status", 20).default("scheduled")
    val location = varchar("location", 255).nullable()
}

object MatchParticipants : IntIdTable("match_participants") {
    val matchId = reference("match_id", Matches).index()
    val playerName = varchar("player_name", 100)
    val teamSide = varchar("team_side", 10) // 'HOME', 'GUEST'
    val status = varchar("status", 20).default("APPLIED") // 'APPLIED', 'SELECTED'
}

object IndividualMatches : IntIdTable("individual_matches") {
    val matchId = reference("match_id", Matches).index()
    val homePlayerName = varchar("home_player_name", 100)
    val guestPlayerName = varchar("guest_player_name", 100)
    val homeScore = integer("home_score").default(0)
    val guestScore = integer("guest_score").default(0)
    val homeSetsWon = integer("home_sets_won").default(0) // Ez hiányzott a seedeléshez
    val guestSetsWon = integer("guest_sets_won").default(0) // Ez is
    val setScores = varchar("set_scores", 100).nullable() // Ez is
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

// ---------------- DB Init ----------------

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
        addDataSourceProperty("sslmode", "require") // Renderhez kell
    }
    return HikariDataSource(cfg)
}

fun initDatabase(ds: HikariDataSource): Database {
    val db = Database.connect(ds)
    transaction(db) {
        SchemaUtils.createMissingTablesAndColumns(
            Players, FcmTokens, Users, Clubs, Teams, TeamMembers, Seasons,
            Matches, IndividualMatches, MatchParticipants
        )
    }
    return db
}

// ---------------- Logika / Seed ----------------

fun generateSetScore(): String {
    val winPoints = if ((0..10).random() < 8) 11 else (12..15).random()
    val lossPoints = if (winPoints == 11) (0..9).random() else winPoints - 2
    return if ((0..1).random() == 0) "$winPoints-$lossPoints" else "$lossPoints-$winPoints"
}

fun playIndividualMatch(matchId: EntityID<Int>, hPlayerUserId: EntityID<Int>, gPlayerUserId: EntityID<Int>) {
    var homeSets = 0
    var guestSets = 0
    val scores = mutableListOf<String>()

    while (homeSets < 3 && guestSets < 3) {
        val score = generateSetScore()
        val parts = score.split("-")
        if (parts[0].toInt() > parts[1].toInt()) homeSets++ else guestSets++
        scores.add(score)
    }

    // Itt most neveket generálunk ID helyett, hogy passzoljon a tábládhoz
    // Valós appban itt User ID-t mentenénk, vagy lekérnénk a nevet.
    // Egyszerűsítés: "Player ID" szöveg
    IndividualMatches.insert {
        it[IndividualMatches.matchId] = matchId
        it[homePlayerName] = "User ${hPlayerUserId.value}"
        it[guestPlayerName] = "User ${gPlayerUserId.value}"
        it[homeScore] = homeSets // Itt a szett arányt mentjük pontnak
        it[guestScore] = guestSets
        it[homeSetsWon] = homeSets
        it[guestSetsWon] = guestSets
        it[setScores] = scores.joinToString(", ")
    }
}

fun seedDatabaseIfNeeded() {
    if (Clubs.selectAll().count() > 0) return

    appLog.info("🌱 Adatbázis feltöltése folyamatban...")

    Seasons.insert { it[name] = "2025 Ősz"; it[startDate] = LocalDate.of(2025, 9, 1); it[endDate] = LocalDate.of(2025, 12, 15); it[isActive] = false }
    val activeSeasonId = Seasons.insertAndGetId { it[name] = "2026 Tavasz"; it[startDate] = LocalDate.of(2026, 2, 1); it[endDate] = LocalDate.of(2026, 5, 31); it[isActive] = true }

    val beacId = Clubs.insertAndGetId { it[name] = "BEAC"; it[address] = "1117 Budapest, Bogdánfy u. 10." }
    val mafcId = Clubs.insertAndGetId { it[name] = "MAFC"; it[address] = "1111 Budapest, Műegyetem rkp. 3." }

    val beac1 = Teams.insertAndGetId { it[clubId] = beacId; it[name] = "BEAC I."; it[division] = "NB I." }
    val beac2 = Teams.insertAndGetId { it[clubId] = beacId; it[name] = "BEAC II."; it[division] = "Budapest I." }
    val mafc1 = Teams.insertAndGetId { it[clubId] = mafcId; it[name] = "MAFC I."; it[division] = "NB I." }
    val mafc2 = Teams.insertAndGetId { it[clubId] = mafcId; it[name] = "MAFC II."; it[division] = "Budapest I." }

    val teams = listOf(beac1, beac2, mafc1, mafc2)
    var userCounter = 1

    for (team in teams) {
        for (i in 1..4) {
            val uId = Users.insertAndGetId {
                it[firebaseUid] = "dummyUid$userCounter" ; it[email] = "player${userCounter}@test.com"; it[firstName] = "Player"; it[lastName] = "$userCounter"
            }
            TeamMembers.insert { it[teamId] = team; it[userId] = uId; it[isCaptain] = (i == 1); it[joinedAt] = LocalDate.now() }
            userCounter++
        }
    }

    val pairings = listOf(
        Triple(beac1, mafc1, 1), Triple(mafc1, beac1, 2),
        Triple(beac2, mafc2, 3), Triple(mafc2, beac2, 4)
    )

    pairings.forEach { (hId, gId, round) ->
        // 1. Meccs létrehozása (időponttal)
        val mId = Matches.insertAndGetId {
            it[seasonId] = activeSeasonId; it[roundNumber] = round; it[homeTeamId] = hId; it[guestTeamId] = gId
            it[status] = "finished"; it[matchDate] = LocalDate.now().atStartOfDay().plusDays(round.toLong() * 7)
            it[matchDate] = LocalDate.now().plusDays(round.toLong() * 7).atTime(18, 30)
            it[location] = "Budapest, Mérnök u. 35, 1119"
        }

        // 2. Hazai csapat játékosainak lekérése és hozzáadása résztvevőként
        val homePlayers = (TeamMembers innerJoin Users)
            .select { TeamMembers.teamId eq hId }
            .map { "${it[Users.lastName]} ${it[Users.firstName]}" }

        homePlayers.forEach { pName ->
            MatchParticipants.insert {
                it[matchId] = mId
                it[playerName] = pName
                it[teamSide] = "HOME"
                it[status] = "SELECTED" // Vagy "APPLIED", ha még csak jelentkezett
            }
        }

        // 3. Vendég csapat játékosainak lekérése és hozzáadása résztvevőként
        val guestPlayers = (TeamMembers innerJoin Users)
            .select { TeamMembers.teamId eq gId }
            .map { "${it[Users.lastName]} ${it[Users.firstName]}" }

        guestPlayers.forEach { pName ->
            MatchParticipants.insert {
                it[matchId] = mId
                it[playerName] = pName
                it[teamSide] = "GUEST"
                it[status] = "SELECTED"
            }
        }

        val hScore = (5..10).random()
        val gScore = (5..10).random()
        Matches.update({ Matches.id eq mId }) {
            it[homeTeamScore] = hScore
            it[guestTeamScore] = gScore
        }
    }
    appLog.info("✅ Adatbázis feltöltve!")
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
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    // HOST = 0.0.0.0 kötelező Renderhez!
    embeddedServer(Netty, port = port, host = "0.0.0.0") {

        // A háttérben töltünk, hogy a szerver azonnal indulhasson (No open ports hiba ellen)
        launch(Dispatchers.IO) {
            try {
                transaction(db) { seedDatabaseIfNeeded() }
            } catch (e: Exception) {
                appLog.error("Hiba a seedelésnél: ${e.message}")
            }
        }

        module(db)
    }.start(wait = true)
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
    install(Authentication) {
        bearer("firebase-auth") {
            authenticate { tokenCredential ->
                try {
                    // A Firebase SDK leellenőrzi a tokent (lejárt-e, hamisították-e)
                    val decodedToken = FirebaseAdminAuth.getInstance().verifyIdToken(tokenCredential.token)

                    // Ha sikeres, visszaadjuk a felhasználó azonosítóját a Ktornak
                    UserIdPrincipal(decodedToken.uid)
                } catch (e: Exception) {
                    // Ha a token érvénytelen, 401 Unauthorized hibát kap a kliens
                    appLog.warn("Érvénytelen token: ${e.message}")
                    null
                }
            }
        }
    }
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(10)
        timeout = Duration.ofSeconds(60)
    }

    val clients = mutableListOf<DefaultWebSocketServerSession>()
    val json = Json { classDiscriminator = "type"; encodeDefaults = true }

    routing {
        // --- REGISZTRÁCIÓ SZINKRONIZÁLÁSA (Ide jön a Firebase token alapján a user) ---
        authenticate("firebase-auth") {
            post("/auth/sync") {
                // Ezt az azonosítót a tokenből olvassuk ki, így NEM LEHET MEGHAMISÍTANI
                val principal = call.principal<UserIdPrincipal>()
                val firebaseUid = principal?.name ?: return@post call.respond(HttpStatusCode.Unauthorized)

                // Várjuk az Androidtól a regisztrált adatokat (pl. név)
                val newUserData = call.receive<UserDTO>()

                val savedUser = transaction(db) {
                    // Megnézzük, létezik-e már (ha csak bejelentkezett, nem kell újra beszúrni)
                    val existingUser = Users.select { Users.firebaseUid eq firebaseUid }.singleOrNull()

                    if (existingUser == null) {
                        // Ha új user, beszúrjuk az adatbázisba
                        val id = Users.insertAndGetId {
                            it[Users.firebaseUid] = firebaseUid
                            it[email] = newUserData.email
                            it[firstName] = newUserData.firstName
                            it[lastName] = newUserData.lastName
                        }.value

                        UserDTO(id, newUserData.email, newUserData.firstName, newUserData.lastName)
                    } else {
                        // Ha már létezik, visszaadjuk a meglévőt
                        UserDTO(
                            existingUser[Users.id].value,
                            existingUser[Users.email],
                            existingUser[Users.firstName],
                            existingUser[Users.lastName]
                        )
                    }
                }

                call.respond(HttpStatusCode.OK, savedUser)
            }

            put("/auth/me") {
                // Biztonság: Csak a saját magát szerkesztheti a token alapján!
                val principal = call.principal<UserIdPrincipal>()
                val firebaseUid = principal?.name ?: return@put call.respond(HttpStatusCode.Unauthorized)

                val updatedData = call.receive<UserDTO>()

                val updatedUser = transaction(db) {
                    // Frissítjük a kereszt- és vezetéknevet
                    val rowsAffected = Users.update({ Users.firebaseUid eq firebaseUid }) {
                        it[firstName] = updatedData.firstName
                        it[lastName] = updatedData.lastName
                        // Az emailt itt szándékosan nem frissítjük, azt a Firebase kezeli!
                    }

                    // Ha sikeres volt a frissítés, lekérjük a friss adatokat
                    if (rowsAffected > 0) {
                        val row = Users.select { Users.firebaseUid eq firebaseUid }.single()
                        UserDTO(
                            id = row[Users.id].value,
                            email = row[Users.email],
                            firstName = row[Users.firstName],
                            lastName = row[Users.lastName]
                        )
                    } else {
                        null
                    }
                }

                if (updatedUser != null) {
                    call.respond(HttpStatusCode.OK, updatedUser)
                } else {
                    call.respond(HttpStatusCode.NotFound, "Felhasználó nem található")
                }
            }
        }

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

        // Ktor Backend - Application.kt vagy Routing.kt
        get("/teams") {
            appLog.info("📥 GET /teams lekérdezés...")

            try {
                val teamsResponse = transaction(db) {
                    Teams.selectAll().map { teamRow ->
                        val tId = teamRow[Teams.id].value
                        val currentTeamEntityId = teamRow[Teams.id]
                        val clubRow = Clubs.select { Clubs.id eq teamRow[Teams.clubId] }.single()

                        val teamMatches = Matches.select {
                        ((Matches.homeTeamId eq currentTeamEntityId) or (Matches.guestTeamId eq currentTeamEntityId)) and
                                (Matches.status eq "finished")
                        }.toList()

                        var wins = 0
                        var losses = 0
                        var draws = 0

                        teamMatches.forEach { row ->
                            // Itt is EntityID-t hasonlítunk EntityID-hoz
                            val isHome = row[Matches.homeTeamId] == currentTeamEntityId
                            val homeScore = row[Matches.homeTeamScore]
                            val guestScore = row[Matches.guestTeamScore]

                            when {
                                homeScore == guestScore -> draws++
                                isHome && homeScore > guestScore -> wins++
                                !isHome && guestScore > homeScore -> wins++
                                else -> losses++
                            }
                        }

                        val points = (wins * 2) + (draws * 1) // 3 pont a győzelemért, 1 a döntetlenért
                        // -----------------------------------------------------------

                        val membersList = (TeamMembers innerJoin Users)
                            .select { TeamMembers.teamId eq tId }
                            .map { memberRow ->
                                MemberDTO(
                                    userId = memberRow[Users.id].value,
                                    firebaseUid = memberRow[Users.firebaseUid],
                                    name = "${memberRow[Users.lastName]} ${memberRow[Users.firstName]}",
                                    isCaptain = memberRow[TeamMembers.isCaptain]
                                )
                            }

                        // Visszaadjuk az ÚJ, bővített DTO-t (Itt is frissíteni kell a DTOs.kt-ben!)
                        TeamWithMembersDTO(
                            teamId = tId,
                            teamName = teamRow[Teams.name],
                            clubName = clubRow[Clubs.name],
                            division = teamRow[Teams.division],
                            members = membersList,
                            matchesPlayed = teamMatches.size,
                            wins = wins,
                            losses = losses,
                            draws = draws,
                            points = points
                        )
                    }
                }
                call.respond(teamsResponse)

            } catch (e: Exception) {
                appLog.error("Hiba a /teams lekérdezésekor: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError, "Adatbázis hiba történt")
            }
        }

        get("/matches") {
            val round = call.request.queryParameters["round"]?.toIntOrNull()

            val matches = transaction(db) {
                val query = if (round != null) {
                    Matches.select { Matches.roundNumber eq round }
                } else {
                    Matches.selectAll()
                }

                query.map { matchRow ->
                    val mId = matchRow[Matches.id].value

                    // Csapatnevek lekérése az ID-k alapján
                    val homeName = Teams.select { Teams.id eq matchRow[Matches.homeTeamId] }.single()[Teams.name]
                    val guestName = Teams.select { Teams.id eq matchRow[Matches.guestTeamId] }.single()[Teams.name]

                    // --- 2. EGYÉNI MECCSEK LEKÉRÉSE (individualMatches) ---
                    // Csak akkor van értelme, ha finished
                    val individualMatches = IndividualMatches.select { IndividualMatches.matchId eq mId }.map { imRow ->
                        IndividualMatchDTO(
                            id = imRow[IndividualMatches.id].value,
                            homePlayerName = imRow[IndividualMatches.homePlayerName],
                            guestPlayerName = imRow[IndividualMatches.guestPlayerName],
                            homeScore = imRow[IndividualMatches.homeScore],
                            guestScore = imRow[IndividualMatches.guestScore]
                        )
                    }

                    // --- 3. RÉSZTVEVŐK LEKÉRÉSE (participants) ---
                    val participants = MatchParticipants.select { MatchParticipants.matchId eq mId }.map { mpRow ->
                        MatchParticipantDTO(
                            id = mpRow[MatchParticipants.id].value,
                            playerName = mpRow[MatchParticipants.playerName],
                            teamSide = mpRow[MatchParticipants.teamSide],
                            status = mpRow[MatchParticipants.status]
                        )
                    }

                    MatchDTO(
                        id = matchRow[Matches.id].value,
                        roundNumber = matchRow[Matches.roundNumber] ?: 0,
                        homeTeamName = homeName,
                        guestTeamName = guestName,
                        homeScore = matchRow[Matches.homeTeamScore],
                        guestScore = matchRow[Matches.guestTeamScore],
                        date = matchRow[Matches.matchDate]?.toString() ?: "",
                        status = matchRow[Matches.status],
                        location = matchRow[Matches.location] ?: "",
                        individualMatches = individualMatches,
                        participants = participants
                    )
                }
            }
            call.respond(matches)
        }

        // --- 1. CSAPAT NEVÉNEK MÓDOSÍTÁSA (PUT) ---
        put("/teams/{id}") {
            val teamId = call.parameters["id"]?.toIntOrNull()
                ?: return@put call.respond(HttpStatusCode.BadRequest, "Érvénytelen csapat ID")

            val updateData = call.receive<TeamUpdateDTO>()

            val rowsAffected = transaction(db) {
                Teams.update({ Teams.id eq teamId }) {
                    it[name] = updateData.name
                }
            }

            if (rowsAffected > 0) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "updated", "newName" to updateData.name))
            } else {
                call.respond(HttpStatusCode.NotFound, "Csapat nem található")
            }
        }

        // --- 2. SZABAD JÁTÉKOSOK LEKÉRÉSE (GET) ---
        // Azok a userek kellenek, akiknek az ID-ja NINCS benne a team_members táblában
        get("/users/available") {
            val availableUsers = transaction(db) {
                // Készítünk egy lekérdezést, ami visszaadja az összes olyan user ID-t, aki már csapatban van
                val usersInTeams = TeamMembers.slice(TeamMembers.userId).selectAll()

                // Csak azokat a usereket kérjük le, akik nincsenek benne a fenti listában
                Users.select { Users.id notInSubQuery usersInTeams }
                    .map { row ->
                        MemberDTO(
                            userId = row[Users.id].value,
                            firebaseUid = row[Users.firebaseUid],
                            name = "${row[Users.lastName]} ${row[Users.firstName]}",
                            isCaptain = false
                        )
                    }
            }
            call.respond(HttpStatusCode.OK, availableUsers)
        }

        // --- 3. ÚJ TAG HOZZÁADÁSA A CSAPATHOZ (POST) ---
        post("/teams/{id}/members") {
            val teamId = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Érvénytelen csapat ID")

            val request = call.receive<TeamMemberOperationDTO>()

            transaction(db) {
                TeamMembers.insert {
                    it[this.teamId] = teamId
                    it[userId] = request.userId
                    it[isCaptain] = false // Az új tag alapból sima játékos
                    it[joinedAt] = LocalDate.now()
                }
            }
            call.respond(HttpStatusCode.Created, mapOf("status" to "added"))
        }

        // --- 4. TAG ELTÁVOLÍTÁSA A CSAPATBÓL (DELETE) ---
        delete("/teams/{teamId}/members/{userId}") {
            val teamId = call.parameters["teamId"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Érvénytelen csapat ID")
            val userId = call.parameters["userId"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Érvénytelen user ID")

            val rowsDeleted = transaction(db) {
                TeamMembers.deleteWhere {
                    (TeamMembers.teamId eq teamId) and (TeamMembers.userId eq userId)
                }
            }

            if (rowsDeleted > 0) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
            } else {
                call.respond(HttpStatusCode.NotFound, "A játékos nem található ebben a csapatban")
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