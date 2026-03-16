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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.*
import kotlinx.serialization.json.*
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

// ---------------- EXPOSED TÁBLÁK DEFINÍCIÓI ----------------
object FcmTokens : IntIdTable("fcm_tokens") {
    val userId = reference("user_id", Users).uniqueIndex()
    val token = text("token")
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
    val userId = reference("user_id", Users)
    val teamSide = varchar("team_side", 10) // 'HOME', 'GUEST'
    val status = varchar("status", 20).default("APPLIED") // 'APPLIED', 'SELECTED', 'LOCKED'

    // A játékos pozíciója a csapaton belül (1, 2, 3 vagy 4)
    // Null, amíg a kapitány be nem állítja.
    val position = integer("position").nullable()
}

object IndividualMatches : IntIdTable("individual_matches") {
    val matchId = reference("match_id", Matches).index()
    val homePlayerId = reference("home_player_id", Users)
    val guestPlayerId = reference("guest_player_id", Users)

    // Hanyadik meccs a 16-ból? (Sorrend a megjelenítéshez)
    val orderNumber = integer("order_number").default(0)

    val homeScore = integer("home_score").default(0) // Szettek (pl 3)
    val guestScore = integer("guest_score").default(0) // Szettek (pl 1)

    // Ezt benne hagyjuk kompatibilitás miatt, bár ugyanazt tudja, mint a fenti kettő
    val homeSetsWon = integer("home_sets_won").default(0)
    val guestSetsWon = integer("guest_sets_won").default(0)

    // A szettek részletes pontszámai (Pl: "11-8, 9-11, 11-5, 11-6")
    val setScores = varchar("set_scores", 255).nullable()

    // A meccs állapota
    val status = varchar("status", 20).default("pending") // 'pending', 'in_progress', 'finished'
}

// ---------------- WebSocket események ----------------

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class WsEvent {
    @Serializable
    @SerialName("IndividualScoreUpdated")
    data class IndividualScoreUpdated(
        val individualMatchId: Int,
        val homeScore: Int,
        val guestScore: Int,
        val setScores: String,
        val status: String
    ) : WsEvent()
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
            FcmTokens, Users, Clubs, Teams, TeamMembers, Seasons,
            Matches, IndividualMatches, MatchParticipants
        )
    }
    return db
}

// ---------------- Logika / Seed ----------------
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
        val mId = Matches.insertAndGetId {
            it[seasonId] = activeSeasonId; it[roundNumber] = round; it[homeTeamId] = hId; it[guestTeamId] = gId
            it[status] = "finished"; it[matchDate] = LocalDate.now().plusDays(round.toLong() * 7).atTime(18, 30)
            it[location] = "Budapest, Mérnök u. 35, 1119"
        }

        val homePlayers = TeamMembers.select { TeamMembers.teamId eq hId }.map { it[TeamMembers.userId] }
        homePlayers.forEach { uId ->
            MatchParticipants.insert {
                it[matchId] = mId; it[userId] = uId; it[teamSide] = "HOME"; it[status] = "SELECTED"
            }
        }

        val guestPlayers = TeamMembers.select { TeamMembers.teamId eq gId }.map { it[TeamMembers.userId] }
        guestPlayers.forEach { uId ->
            MatchParticipants.insert {
                it[matchId] = mId; it[userId] = uId; it[teamSide] = "GUEST"; it[status] = "SELECTED"
            }
        }

        Matches.update({ Matches.id eq mId }) {
            it[homeTeamScore] = (5..10).random()
            it[guestTeamScore] = (5..10).random()
        }
    }
    appLog.info("✅ Adatbázis feltöltve!")
}

// ---------------- Globális változók ----------------

private val appLog = LoggerFactory.getLogger("KtorDemo")
private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

// ---------------- Segédfüggvények ----------------

fun sendFcmNotification(db: Database, email: String, token: String, title: String, body: String) {
    if (FirebaseApp.getApps().isEmpty()) {
        appLog.warn("Firebase nincs inicializálva, nem küldhető FCM.")
        return
    }

    applicationScope.launch {
        try {
            val message = Message.builder()
                .setToken(token)
                .setNotification(
                    Notification.builder().setTitle(title).setBody(body).build()
                ).build()

            appLog.info("🚀 FCM üzenet küldése indult. Cím: '$title' (Cél: $email)")
            val response = FirebaseMessaging.getInstance().send(message)
            appLog.info("✅ FCM üzenet elküldve: $response")

        } catch (e: Exception) {
            appLog.error("❌ FCM küldési hiba: ${e.message}")

            if (e.message?.contains("Requested entity was not found") == true || e.message?.contains("UNREGISTERED") == true) {
                appLog.info("🧹 Halott token észlelve! Törlés az adatbázisból...")
                transaction(db) {
                    // JAVÍTVA: A token alapján törlünk, mert a userId-t most nem tudjuk kapásból
                    FcmTokens.deleteWhere { FcmTokens.token eq token }
                }
            }
        }
    }
}

// ---------------- Main ----------------

fun main() {
    val ds = initDataSource()
    val db = initDatabase(ds)
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        launch(Dispatchers.IO) {
            try { transaction(db) { seedDatabaseIfNeeded() } }
            catch (e: Exception) { appLog.error("Hiba a seedelésnél: ${e.message}") }
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
                    val decodedToken = FirebaseAdminAuth.getInstance().verifyIdToken(tokenCredential.token)
                    UserIdPrincipal(decodedToken.uid)
                } catch (e: Exception) {
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

    val matchWsClients = mutableListOf<DefaultWebSocketServerSession>()
    val json = Json { classDiscriminator = "type"; encodeDefaults = true }

    routing {

        // ====================================================================
        // NYILVÁNOS VÉGPONTOK
        // ====================================================================

        get("/teams") {
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

                        var wins = 0; var losses = 0; var draws = 0

                        teamMatches.forEach { row ->
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

                        val points = (wins * 2) + (draws * 1)

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

                        TeamWithMembersDTO(
                            teamId = tId, teamName = teamRow[Teams.name], clubName = clubRow[Clubs.name],
                            division = teamRow[Teams.division], members = membersList,
                            matchesPlayed = teamMatches.size, wins = wins, losses = losses, draws = draws, points = points
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
            try {
                val matches = transaction(db) {
                    val query = if (round != null) Matches.select { Matches.roundNumber eq round } else Matches.selectAll()

                    query.map { matchRow ->
                        val mId = matchRow[Matches.id].value
                        val homeTeamEntityId = matchRow[Matches.homeTeamId]
                        val guestTeamEntityId = matchRow[Matches.guestTeamId]
                        val homeName = Teams.select { Teams.id eq homeTeamEntityId }.single()[Teams.name]
                        val guestName = Teams.select { Teams.id eq guestTeamEntityId }.single()[Teams.name]

                        val individualMatches = IndividualMatches.select { IndividualMatches.matchId eq mId }.map { imRow ->
                            val homeUserRow = Users.select { Users.id eq imRow[IndividualMatches.homePlayerId] }.single()
                            val guestUserRow = Users.select { Users.id eq imRow[IndividualMatches.guestPlayerId] }.single()
                            IndividualMatchDTO(
                                id = imRow[IndividualMatches.id].value,
                                homePlayerId = imRow[IndividualMatches.homePlayerId].value,
                                homePlayerName = "${homeUserRow[Users.lastName]} ${homeUserRow[Users.firstName]}",
                                guestPlayerId = imRow[IndividualMatches.guestPlayerId].value,
                                guestPlayerName = "${guestUserRow[Users.lastName]} ${guestUserRow[Users.firstName]}",
                                homeScore = imRow[IndividualMatches.homeScore],
                                guestScore = imRow[IndividualMatches.guestScore],
                                setScores = imRow[IndividualMatches.setScores],
                                status = imRow[IndividualMatches.status],
                                orderNumber = imRow[IndividualMatches.orderNumber]
                            )
                        }

                        val participants = (MatchParticipants innerJoin Users).select { MatchParticipants.matchId eq mId }.map { mpRow ->
                            MatchParticipantDTO(
                                id = mpRow[MatchParticipants.id].value,
                                userId = mpRow[Users.id].value,
                                firebaseUid = mpRow[Users.firebaseUid],
                                playerName = "${mpRow[Users.lastName]} ${mpRow[Users.firstName]}",
                                teamSide = mpRow[MatchParticipants.teamSide],
                                status = mpRow[MatchParticipants.status],
                                position = mpRow[MatchParticipants.position]
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
                            homeTeamId = homeTeamEntityId.value,
                            guestTeamId = guestTeamEntityId.value,
                            individualMatches = individualMatches,
                            participants = participants
                        )
                    }
                }
                call.respond(matches)
            } catch (e: Exception) {
                appLog.error("Hiba a /matches lekérdezésekor: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError, "Adatbázis hiba történt")
            }
        }

        post("/send_fcm_notification") {
            val request = call.receive<SendNotificationRequest>()
            val notificationData = transaction(db) {
                (Users innerJoin FcmTokens)
                    .slice(FcmTokens.token)
                    .select { Users.email eq request.targetEmail }
                    .singleOrNull()?.get(FcmTokens.token)
            }
            if (notificationData == null) {
                call.respond(HttpStatusCode.NotFound, "Nincs token ehhez az e-mailhez.")
                return@post
            }
            sendFcmNotification(db, request.targetEmail, notificationData, request.title, request.body)
            call.respond(HttpStatusCode.OK, mapOf("status" to "sent"))
        }

        post("/register_fcm_token") {
            val registration = call.receive<FcmTokenRegistration>()
            val result = transaction(db) {
                val userRow = Users.select { Users.email eq registration.email }.singleOrNull()
                if (userRow != null) {
                    val uId = userRow[Users.id]
                    FcmTokens.deleteWhere { FcmTokens.userId eq uId }
                    FcmTokens.insert {
                        it[userId] = uId
                        it[token] = registration.token
                    }
                    true
                } else false
            }
            if (result) call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
            else call.respond(HttpStatusCode.NotFound, "User nem található")
        }


        // ====================================================================
        // VÉDETT VÉGPONTOK (Csak Firebase tokennel)
        // ====================================================================
        authenticate("firebase-auth") {

            post("/auth/sync") {
                val principal = call.principal<UserIdPrincipal>()
                val firebaseUid = principal?.name ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val newUserData = call.receive<UserDTO>()

                val savedUser = transaction(db) {
                    val existingUser = Users.select { Users.firebaseUid eq firebaseUid }.singleOrNull()
                    if (existingUser == null) {
                        val id = Users.insertAndGetId {
                            it[Users.firebaseUid] = firebaseUid
                            it[email] = newUserData.email
                            it[firstName] = newUserData.firstName
                            it[lastName] = newUserData.lastName
                        }.value
                        UserDTO(id, newUserData.email, newUserData.firstName, newUserData.lastName)
                    } else {
                        UserDTO(
                            existingUser[Users.id].value, existingUser[Users.email],
                            existingUser[Users.firstName], existingUser[Users.lastName]
                        )
                    }
                }
                call.respond(HttpStatusCode.OK, savedUser)
            }

            put("/auth/me") {
                val principal = call.principal<UserIdPrincipal>()
                val firebaseUid = principal?.name ?: return@put call.respond(HttpStatusCode.Unauthorized)
                val updatedData = call.receive<UserDTO>()

                val updatedUser = transaction(db) {
                    val rowsAffected = Users.update({ Users.firebaseUid eq firebaseUid }) {
                        it[firstName] = updatedData.firstName
                        it[lastName] = updatedData.lastName
                    }
                    if (rowsAffected > 0) {
                        val row = Users.select { Users.firebaseUid eq firebaseUid }.single()
                        UserDTO(
                            id = row[Users.id].value, email = row[Users.email],
                            firstName = row[Users.firstName], lastName = row[Users.lastName]
                        )
                    } else null
                }
                if (updatedUser != null) call.respond(HttpStatusCode.OK, updatedUser) else call.respond(HttpStatusCode.NotFound, "Nincs ilyen")
            }

            put("/teams/{id}") {
                val teamId = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, "Érvénytelen csapat ID")
                val updateData = call.receive<TeamUpdateDTO>()
                val rowsAffected = transaction(db) {
                    Teams.update({ Teams.id eq teamId }) { it[name] = updateData.name }
                }
                if (rowsAffected > 0) call.respond(HttpStatusCode.OK, mapOf("status" to "updated")) else call.respond(HttpStatusCode.NotFound, "Csapat nem található")
            }

            get("/users/available") {
                val availableUsers = transaction(db) {
                    val usersInTeams = TeamMembers.slice(TeamMembers.userId).selectAll()
                    Users.select { Users.id notInSubQuery usersInTeams }.map { row ->
                        MemberDTO(
                            userId = row[Users.id].value, firebaseUid = row[Users.firebaseUid],
                            name = "${row[Users.lastName]} ${row[Users.firstName]}", isCaptain = false
                        )
                    }
                }
                call.respond(HttpStatusCode.OK, availableUsers)
            }

            post("/teams/{id}/members") {
                val teamId = call.parameters["id"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, "Érvénytelen csapat ID")
                val request = call.receive<TeamMemberOperationDTO>()
                transaction(db) {
                    TeamMembers.insert {
                        it[this.teamId] = teamId; it[userId] = request.userId
                        it[isCaptain] = false; it[joinedAt] = LocalDate.now()
                    }
                }
                call.respond(HttpStatusCode.Created, mapOf("status" to "added"))
            }

            delete("/teams/{teamId}/members/{userId}") {
                val teamId = call.parameters["teamId"]?.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest, "Érvénytelen ID")
                val userId = call.parameters["userId"]?.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest, "Érvénytelen ID")
                val rowsDeleted = transaction(db) {
                    TeamMembers.deleteWhere { (TeamMembers.teamId eq teamId) and (TeamMembers.userId eq userId) }
                }
                if (rowsDeleted > 0) call.respond(HttpStatusCode.OK, mapOf("status" to "deleted")) else call.respond(HttpStatusCode.NotFound, "Nincs ilyen")
            }

            // --- JELENTKEZÉS A MECCSRE ---
            post("/matches/{matchId}/apply") {
                val principal = call.principal<UserIdPrincipal>()
                val firebaseUid = principal?.name ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val matchId = call.parameters["matchId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, "Érvénytelen meccs ID")

                val result: Pair<HttpStatusCode, Any> = transaction(db) {
                    val userRow = Users.select { Users.firebaseUid eq firebaseUid }.singleOrNull()
                    if (userRow == null) return@transaction Pair(HttpStatusCode.NotFound, "User nem található")

                    val userId = userRow[Users.id]

                    val matchRow = Matches.select { Matches.id eq matchId }.singleOrNull()
                    if (matchRow == null) return@transaction Pair(HttpStatusCode.NotFound, "Meccs nem található")

                    val isHomeTeamMember = TeamMembers.select { (TeamMembers.teamId eq matchRow[Matches.homeTeamId]) and (TeamMembers.userId eq userId) }.count() > 0
                    val isGuestTeamMember = TeamMembers.select { (TeamMembers.teamId eq matchRow[Matches.guestTeamId]) and (TeamMembers.userId eq userId) }.count() > 0

                    val teamSide = when {
                        isHomeTeamMember -> "HOME"
                        isGuestTeamMember -> "GUEST"
                        else -> return@transaction Pair(HttpStatusCode.Forbidden, "Nem vagy tagja egyik csapatnak sem!")
                    }

                    val alreadyApplied = MatchParticipants.select { (MatchParticipants.matchId eq matchId) and (MatchParticipants.userId eq userId) }.count() > 0
                    if (alreadyApplied) return@transaction Pair(HttpStatusCode.Conflict, "Már jelentkeztél erre a meccsre!")

                    MatchParticipants.insert {
                        it[MatchParticipants.matchId] = matchId
                        it[MatchParticipants.userId] = userId
                        it[MatchParticipants.teamSide] = teamSide
                        it[MatchParticipants.status] = "APPLIED"
                    }
                    Pair(HttpStatusCode.OK, mapOf("status" to "applied"))
                }
                call.respond(result.first, result.second)
            }

            // --- JELENTKEZÉS VISSZAVONÁSA ---
            delete("/matches/{matchId}/apply") {
                val principal = call.principal<UserIdPrincipal>()
                val firebaseUid = principal?.name ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                val matchId = call.parameters["matchId"]?.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest, "Érvénytelen meccs ID")

                val result: Pair<HttpStatusCode, Any> = transaction(db) {
                    val userRow = Users.select { Users.firebaseUid eq firebaseUid }.singleOrNull()
                    if (userRow == null) return@transaction Pair(HttpStatusCode.NotFound, "User nem található")

                    val userId = userRow[Users.id]

                    val matchRow = Matches.select { Matches.id eq matchId }.singleOrNull()
                    if (matchRow == null) return@transaction Pair(HttpStatusCode.NotFound, "Meccs nem található")

                    if (matchRow[Matches.status] != "scheduled") {
                        return@transaction Pair(HttpStatusCode.Forbidden, "A meccs már elindult vagy lezárult, nem vonhatod vissza a jelentkezést.")
                    }

                    val deletedRows = MatchParticipants.deleteWhere {
                        (MatchParticipants.matchId eq matchId) and (MatchParticipants.userId eq userId)
                    }

                    if (deletedRows > 0) Pair(HttpStatusCode.OK, mapOf("status" to "withdrawn"))
                    else Pair(HttpStatusCode.NotFound, "Nem találtunk aktív jelentkezést.")
                }
                call.respond(result.first, result.second)
            }

            // --- KAPITÁNYI VÉGLEGESÍTÉS / INDÍTÁS ---
            post("/matches/{matchId}/finalize") {
                val principal = call.principal<UserIdPrincipal>()
                val firebaseUid = principal?.name ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val matchId = call.parameters["matchId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid Match ID")

                try {
                    val notificationData = transaction(db) {
                        val userRow = Users.select { Users.firebaseUid eq firebaseUid }.singleOrNull() ?: return@transaction null
                        val captainUserId = userRow[Users.id]
                        val matchRow = Matches.select { Matches.id eq matchId }.singleOrNull() ?: return@transaction null

                        // 1. Jogosultság ellenőrzése
                        val isHomeCap = TeamMembers.select { (TeamMembers.teamId eq matchRow[Matches.homeTeamId]) and (TeamMembers.userId eq captainUserId) and (TeamMembers.isCaptain eq true) }.count() > 0
                        val isGuestCap = TeamMembers.select { (TeamMembers.teamId eq matchRow[Matches.guestTeamId]) and (TeamMembers.userId eq captainUserId) and (TeamMembers.isCaptain eq true) }.count() > 0

                        if (!isHomeCap && !isGuestCap) return@transaction null

                        // 2. ÚJ LOGIKA: Mindkét csapat létszámának ellenőrzése
                        val homeSelectedCount = MatchParticipants.select {
                            (MatchParticipants.matchId eq matchId) and
                                    (MatchParticipants.teamSide eq "HOME") and
                                    (MatchParticipants.status eq "SELECTED")
                        }.count()

                        val guestSelectedCount = MatchParticipants.select {
                            (MatchParticipants.matchId eq matchId) and
                                    (MatchParticipants.teamSide eq "GUEST") and
                                    (MatchParticipants.status eq "SELECTED")
                        }.count()

                        if (homeSelectedCount < 4 || guestSelectedCount < 4) {
                            throw IllegalArgumentException("Mindkét csapatból legalább 4 játékos kiválasztása szükséges a meccs indításához!")
                        }

                        // 3. Meccs elindítása
                        Matches.update({ Matches.id eq matchId }) { it[status] = "in_progress" }

                        // 4. Értesítendők kigyűjtése (Mindenki, aki be lett válogatva, kivéve az indító kapitány)
                        val tokensWithEmails = (MatchParticipants innerJoin Users innerJoin FcmTokens)
                            .slice(Users.email, FcmTokens.token)
                            .select {
                                (MatchParticipants.matchId eq matchId) and
                                        (MatchParticipants.status eq "SELECTED") and
                                        (MatchParticipants.userId neq captainUserId)
                            }
                            .map { Pair(it[Users.email], it[FcmTokens.token]) }

                        val homeTeamName = Teams.select { Teams.id eq matchRow[Matches.homeTeamId] }.single()[Teams.name]
                        val guestTeamName = Teams.select { Teams.id eq matchRow[Matches.guestTeamId] }.single()[Teams.name]

                        Pair(tokensWithEmails, "$homeTeamName vs $guestTeamName")
                    }

                    if (notificationData == null) {
                        call.respond(HttpStatusCode.Forbidden, "Hiba az indításkor: nincs jogosultság vagy nem található a meccs.")
                    } else {
                        call.respond(HttpStatusCode.OK, mapOf("status" to "finalized"))
                        applicationScope.launch {
                            val (players, title) = notificationData
                            players.forEach { (email, token) ->
                                sendFcmNotification(db, email, token, "A mérkőzés elindult! 🏁", "A $title meccs hivatalosan megkezdődött.")
                            }
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    // Ha a mi létszám-ellenőrzésünk bukik el, szép 400-as hibát küldünk
                    appLog.warn("Sikertelen indítási kísérlet: ${e.message}")
                    call.respond(HttpStatusCode.BadRequest, e.message ?: "Hiányzó játékosok.")
                } catch (e: Exception) {
                    appLog.error("Hiba a véglegesítésnél: ${e.message}", e)
                    call.respond(HttpStatusCode.InternalServerError, "Szerver oldali hiba: ${e.message}")
                }
            }

            // --- STÁTUSZ FRISSÍTÉSE (Betesz / Kivesz) ---
            put("/matches/participants/{participantId}/status") {
                val principal = call.principal<UserIdPrincipal>()
                val firebaseUid = principal?.name ?: return@put call.respond(HttpStatusCode.Unauthorized)
                val participantId = call.parameters["participantId"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, "Érvénytelen jelentkezési ID")

                val request = call.receive<ParticipantStatusUpdateDTO>()

                val rowsUpdated = transaction(db) {
                    MatchParticipants.update({ MatchParticipants.id eq participantId }) {
                        it[status] = request.status
                    }
                }

                if (rowsUpdated > 0) {
                    if (request.status == "SELECTED") {
                        applicationScope.launch {
                            try {
                                val notificationData = transaction(db) {
                                    val pRow = MatchParticipants.select { MatchParticipants.id eq participantId }.single()
                                    val mId = pRow[MatchParticipants.matchId]
                                    val uId = pRow[MatchParticipants.userId]

                                    // Lekérjük a felhasználó adatait (kell az email a logoláshoz/értesítéshez)
                                    val userRow = Users.select { Users.id eq uId }.single()
                                    val email = userRow[Users.email]

                                    // JAVÍTVA: A tokent most már a userId alapján keressük, mert a táblában nincs email oszlop!
                                    val token = FcmTokens.select { FcmTokens.userId eq uId }
                                        .singleOrNull()?.get(FcmTokens.token)

                                    val matchRow = Matches.select { Matches.id eq mId }.single()
                                    val homeTeamName = Teams.select { Teams.id eq matchRow[Matches.homeTeamId] }.single()[Teams.name]
                                    val guestTeamName = Teams.select { Teams.id eq matchRow[Matches.guestTeamId] }.single()[Teams.name]

                                    if (token != null) Triple(token, "$homeTeamName vs $guestTeamName", email) else null
                                }

                                notificationData?.let { (token, matchName, targetEmail) ->
                                    sendFcmNotification(db, targetEmail, token, "Bekerültél a keretbe! 🏀", "A kapitány kiválasztott a $matchName mérkőzésre!")
                                }
                            } catch (e: Exception) {
                                appLog.error("❌ Hiba az értesítés előkészítésekor: ${e.message}")
                            }
                        }
                    }
                    call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
                } else {
                    call.respond(HttpStatusCode.NotFound, "Jelentkezési adat nem található")
                }
            }

            // --- SORREND LEADÁSA (LINEUP) ---
            post("/matches/{matchId}/lineup") {
                val principal = call.principal<UserIdPrincipal>()
                val firebaseUid = principal?.name ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val matchId = call.parameters["matchId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid Match ID")
                val lineupRequest = call.receive<LineupSubmitDTO>()

                try {
                    val isMatchReadyForGrid = transaction(db) {
                        // 1. Jogosultság (Csapattag-e?)
                        val userRow = Users.select { Users.firebaseUid eq firebaseUid }.singleOrNull() ?: throw Exception("User not found")
                        val submittingUserId = userRow[Users.id]
                        val matchRow = Matches.select { Matches.id eq matchId }.singleOrNull() ?: throw Exception("Match not found")

                        // JAVÍTVA: Csak azt nézzük, hogy tagja-e a csapatnak (nem kell kapitánynak lennie)
                        val targetTeamId = if (lineupRequest.teamSide == "HOME") matchRow[Matches.homeTeamId] else matchRow[Matches.guestTeamId]
                        val isTeamMember = TeamMembers.select {
                            (TeamMembers.teamId eq targetTeamId) and (TeamMembers.userId eq submittingUserId)
                        }.count() > 0

                        if (!isTeamMember) throw Exception("Nem vagy a csapat tagja, így nem adhatsz le sorrendet!")

                        if (lineupRequest.positions.size != 4) throw Exception("Pontosan 4 játékost kell megadni a sorrendhez!")

                        // 2. Beírjuk a pozíciókat az adatbázisba (és a státuszt LOCKED-re tesszük)
                        lineupRequest.positions.forEach { (pos, uId) ->
                            MatchParticipants.update({
                                (MatchParticipants.matchId eq matchId) and
                                        (MatchParticipants.userId eq uId) and
                                        (MatchParticipants.teamSide eq lineupRequest.teamSide)
                            }) {
                                it[position] = pos
                                it[status] = "LOCKED"
                            }
                        }

                        // 3. Megnézzük, hogy a TÖBBI csapat leadta-e már a sorrendet?
                        val otherSide = if (lineupRequest.teamSide == "HOME") "GUEST" else "HOME"
                        val otherSideLockedCount = MatchParticipants.select {
                            (MatchParticipants.matchId eq matchId) and
                                    (MatchParticipants.teamSide eq otherSide) and
                                    (MatchParticipants.status eq "LOCKED")
                        }.count()

                        otherSideLockedCount == 4L
                    }

                    // 4. HA MINDKÉT OLDAL MEGVOLT -> GENERÁLJUK A 16 MECCSET!
                    if (isMatchReadyForGrid) {
                        transaction(db) {
                            val homePlayersByPos = MatchParticipants.select { (MatchParticipants.matchId eq matchId) and (MatchParticipants.teamSide eq "HOME") and (MatchParticipants.status eq "LOCKED") }
                                .associate { it[MatchParticipants.position]!! to it[MatchParticipants.userId] }

                            val guestPlayersByPos = MatchParticipants.select { (MatchParticipants.matchId eq matchId) and (MatchParticipants.teamSide eq "GUEST") and (MatchParticipants.status eq "LOCKED") }
                                .associate { it[MatchParticipants.position]!! to it[MatchParticipants.userId] }

                            // A hivatalos 16 meccses asztalitenisz párosítás mátrixa (Home Pos vs Guest Pos)
                            val pairings = listOf(
                                Pair(1, 1), Pair(2, 2), Pair(3, 3), Pair(4, 4), // 1. kör
                                Pair(1, 2), Pair(2, 3), Pair(3, 4), Pair(4, 1), // 2. kör
                                Pair(1, 3), Pair(2, 4), Pair(3, 1), Pair(4, 2), // 3. kör
                                Pair(1, 4), Pair(2, 1), Pair(3, 2), Pair(4, 3)  // 4. kör
                            )

                            IndividualMatches.deleteWhere { IndividualMatches.matchId eq matchId }

                            pairings.forEachIndexed { index, pair ->
                                val hPlayerId = homePlayersByPos[pair.first]
                                val gPlayerId = guestPlayersByPos[pair.second]

                                if (hPlayerId != null && gPlayerId != null) {
                                    IndividualMatches.insert {
                                        it[this.matchId] = matchId
                                        it[this.homePlayerId] = hPlayerId
                                        it[this.guestPlayerId] = gPlayerId
                                        it[this.orderNumber] = index + 1
                                        it[this.status] = if (index < 2) "in_progress" else "pending"
                                    }
                                }
                            }
                        }

                        call.respond(HttpStatusCode.OK, mapOf("status" to "grid_generated"))
                    } else {
                        call.respond(HttpStatusCode.OK, mapOf("status" to "waiting_for_opponent"))
                    }

                } catch (e: Exception) {
                    appLog.error("Hiba a sorrend mentésekor: ${e.message}", e)
                    call.respond(HttpStatusCode.BadRequest, e.message ?: "Hiba történt")
                }
            }

            // --- EGYÉNI MECCS PONTOZÁSA ÉS AUTOMATIKUS LÉPTETÉS ---
            put("/matches/individual/{id}/score") {
                val principal = call.principal<UserIdPrincipal>()
                if (principal == null) return@put call.respond(HttpStatusCode.Unauthorized)

                val individualMatchId = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, "Érvénytelen ID")
                val request = call.receive<ScoreSubmitDTO>()

                try {
                    val result = transaction(db) {
                        // 1. Frissítjük az aktuális meccs állását
                        val rowsAffected = IndividualMatches.update({ IndividualMatches.id eq individualMatchId }) {
                            it[homeScore] = request.homeScore
                            it[guestScore] = request.guestScore
                            it[homeSetsWon] = request.homeScore
                            it[guestSetsWon] = request.guestScore
                            it[setScores] = request.setScores
                            it[status] = request.status
                        }

                        // 2. Automatikus léptetés (Ahogy korábban megírtuk)
                        if (rowsAffected > 0 && request.status == "finished") {
                            val parentMatchId = IndividualMatches.slice(IndividualMatches.matchId).select { IndividualMatches.id eq individualMatchId }.singleOrNull()?.get(IndividualMatches.matchId)
                            if (parentMatchId != null) {
                                val activeCount = IndividualMatches.select { (IndividualMatches.matchId eq parentMatchId) and (IndividualMatches.status eq "in_progress") }.count()
                                val neededCount = 2 - activeCount.toInt()
                                if (neededCount > 0) {
                                    val nextMatchesToStart = IndividualMatches
                                        .select { (IndividualMatches.matchId eq parentMatchId) and (IndividualMatches.status eq "pending") }
                                        .orderBy(IndividualMatches.orderNumber to SortOrder.ASC).limit(neededCount).map { it[IndividualMatches.id] }
                                    IndividualMatches.update({ IndividualMatches.id inList nextMatchesToStart }) { it[status] = "in_progress" }
                                }
                            }
                        }
                        rowsAffected
                    }

                    if (result > 0) {
                        // --- ÚJ: WEBSOCKET BROADCAST ---
                        // Szétküldjük az új állást minden csatlakozott kliensnek!
                        applicationScope.launch {
                            val event = WsEvent.IndividualScoreUpdated(
                                individualMatchId = individualMatchId,
                                homeScore = request.homeScore,
                                guestScore = request.guestScore,
                                setScores = request.setScores,
                                status = request.status
                            )
                            val messageText = json.encodeToString(WsEvent.serializer(), event)

                            // Minden élő kapcsolatnak elküldjük a string-et
                            val activeClients = matchWsClients.toList() // Másolat, hogy elkerüljük a ConcurrentModificationException-t
                            for (client in activeClients) {
                                try {
                                    client.send(io.ktor.websocket.Frame.Text(messageText))
                                } catch (e: Exception) {
                                    appLog.warn("Nem sikerült elküldeni a WS üzenetet egy kliensnek: ${e.message}")
                                }
                            }
                        }
                        // --- WEBSOCKET BROADCAST VÉGE ---

                        call.respond(HttpStatusCode.OK, mapOf("status" to "score_updated"))
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Meccs nem található")
                    }
                } catch (e: Exception) {
                    appLog.error("Hiba a pontozásnál: ${e.message}", e)
                    call.respond(HttpStatusCode.InternalServerError, "Szerver hiba: ${e.message}")
                }
            }
        }

        // ---------------- WebSocket ----------------
        // --- Meccsek WebSocket végpontja ---
        webSocket("/ws/matches") {
            appLog.info("🏓 Új Meccs WS kliens csatlakozott. Összesen: ${matchWsClients.size + 1}")
            matchWsClients.add(this)
            try { incoming.consumeEach { } }
            finally {
                matchWsClients.remove(this)
                appLog.info("💔 Meccs WS kliens lekapcsolódott. Összesen: ${matchWsClients.size}")
            }
        }

        staticResources("/", "static") { default("admin_dashboard.html") }
    }
}