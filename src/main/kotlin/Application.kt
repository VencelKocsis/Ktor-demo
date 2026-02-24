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
import org.jetbrains.exposed.sql.javatime.datetime
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

/*object Matches : IntIdTable("matches") {
    val seasonId = reference("season_id", Seasons)
    val roundNumber = integer("round_number")
    val homeTeamId = reference("home_team_id", Teams)
    val guestTeamId = reference("guest_team_id", Teams)
    val homeTeamScore = integer("home_team_score").default(0)
    val guestTeamScore = integer("guest_team_score").default(0)
    val matchDate = datetime("match_date").nullable()
    val status = varchar("status", 20).default("scheduled")
    val location = varchar("location", 255).nullable()
}*/

object MatchParticipants : IntIdTable("match_participants") {
    val matchId = reference("match_id", Matches).index()
    val playerName = varchar("player_name", 100)
    val teamSide = varchar("team_side", 10) // 'HOME', 'GUEST'
    val status = varchar("status", 20).default("APPLIED") // 'APPLIED', 'SELECTED'
}

object IndividualMatches : IntIdTable("individual_matches") { // Vagy "match_games", attól függ mi lett a neve DBeaverben TODO
    val matchId = reference("match_id", Matches).index()
    val homePlayerName = varchar("home_player_name", 100) // Vagy ID, ha user_id-t tárolsz
    val guestPlayerName = varchar("guest_player_name", 100)
    val homeScore = integer("home_score").default(0)
    val guestScore = integer("guest_score").default(0)
    // Ha User ID-t tároltál a seed-elésnél, akkor itt joinolni kell majd!
    // A mostani példában string nevet feltételezünk az egyszerűség kedvéért,
    // ahogy a DBeaver SQL scriptben csináltuk.
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

    // 1. Csak a táblákat hozzuk létre gyorsan
    transaction(db) {
        SchemaUtils.createMissingTablesAndColumns(
            Players, FcmTokens, Users, Clubs, Teams, TeamMembers, Seasons,
            Matches, IndividualGames, MatchParticipants
        )
    }
    appLog.info("✅ Táblaszerkezet ellenőrizve.")

    // A seedelést innen kivettük, majd a main-ben hívjuk meg!

    return db
}

// Segédfüggvény a szett pontszámok generálásához
fun generateSetScore(): String {
    val winPoints = if ((0..10).random() < 8) 11 else (12..15).random()
    val lossPoints = if (winPoints == 11) (0..9).random() else winPoints - 2
    return if ((0..1).random() == 0) "$winPoints-$lossPoints" else "$lossPoints-$winPoints"
}

// Segédfüggvény egy egyéni meccs (16/forduló) lejátszásához
fun playIndividualMatch(matchId: EntityID<Int>, homePlayer: EntityID<Int>, guestPlayer: EntityID<Int>) {
    var homeSets = 0
    var guestSets = 0
    val scores = mutableListOf<String>()

    while (homeSets < 3 && guestSets < 3) {
        val score = generateSetScore()
        val parts = score.split("-")
        if (parts[0].toInt() > parts[1].toInt()) homeSets++ else guestSets++
        scores.add(score)
    }

    IndividualGames.insert {
        it[IndividualGames.matchId] = matchId
        it[homePlayerId] = homePlayer
        it[guestPlayerId] = guestPlayer
        it[homeSetsWon] = homeSets
        it[guestSetsWon] = guestSets
        it[setScores] = scores.joinToString(", ")
    }
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

    val activeSeasonId = Seasons.selectAll().first()[Seasons.id]

    // Definiáljuk a 4 nagy fordulót (Match a táblában)
    val pairings = listOf(
        Triple(beac1, mafc1, 1), // 1. Forduló: BEAC I - MAFC I
        Triple(mafc1, beac1, 2), // 2. Forduló: MAFC I - BEAC I
        Triple(beac2, mafc2, 3), // 3. Forduló: BEAC II - MAFC II
        Triple(mafc2, beac2, 4)  // 4. Forduló: MAFC II - BEAC II
    )

    pairings.forEach { (homeId, guestId, roundNum) ->
        // 1. Csapatmérkőzés létrehozása
        val mId = Matches.insertAndGetId {
            it[seasonId] = activeSeasonId
            it[roundNumber] = roundNum
            it[homeTeamId] = homeId
            it[guestTeamId] = guestId
            it[status] = "finished"
            it[matchDate] = LocalDate.now().atStartOfDay()
        }

        // 2. Játékosok lekérése a két csapatból
        val homePlayers = TeamMembers.select { TeamMembers.teamId eq homeId }.map { it[TeamMembers.userId] }
        val guestPlayers = TeamMembers.select { TeamMembers.teamId eq guestId }.map { it[TeamMembers.userId] }

        // 3. Mindenki játszik mindenkivel (4x4 = 16 meccs)
        var homeTeamTotal = 0
        var guestTeamTotal = 0

        for (hPlayer in homePlayers) {
            for (gPlayer in guestPlayers) {
                // Lejátszunk egy egyéni meccset
                playIndividualMatch(mId, hPlayer, gPlayer)

                // Itt egy egyszerűsített statisztikát számolunk a csapatnak
                // (Később a backend a get/teams-nél ezt úgyis újraszámolja a sémád alapján)
            }
        }

        // Frissítsük a csapat pontszámát (pl. hány egyéni győzelem született)
        val homeWins = IndividualGames.select {
            (IndividualGames.matchId eq mId) and (IndividualGames.homeSetsWon greater IndividualGames.guestSetsWon)
        }.count().toInt()

        val guestWins = 16 - homeWins

        Matches.update({ Matches.id eq mId }) {
            it[homeTeamScore] = homeWins
            it[guestTeamScore] = guestWins
        }
    }

    appLog.info("✅ Fordulók és egyéni meccsek betöltve!")

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

    // A Render a PORT környezeti változóban adja meg a portot.
    // Ha nincs (pl. lokálban), akkor 8080.
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    embeddedServer(Netty, port = port, host = "0.0.0.0") { // FONTOS: host = "0.0.0.0"

        // --- ADATBÁZIS FELTÖLTÉS HÁTTÉRBEN ---
        // Így a szerver azonnal elindul, nem várja meg a 60+ insertet
        launch(Dispatchers.IO) {
            try {
                // Mivel a seedDatabaseIfNeeded-ben insert-ek vannak,
                // azoknak tranzakcióban kell futniuk.
                transaction(db) {
                    seedDatabaseIfNeeded()
                }
            } catch (e: Exception) {
                appLog.error("❌ Hiba az adatbázis feltöltésekor: ${e.message}")
                e.printStackTrace()
            }
        }

        module(db)
    }.start(wait = true)

    // Ez a sor technikailag sosem fut le a wait=true miatt,
    // de a logokban látni fogod a Netty indulását.
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