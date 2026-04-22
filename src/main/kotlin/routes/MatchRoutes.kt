package hu.bme.aut.android.demo.routes

import hu.bme.aut.android.demo.database.tables.FcmTokens
import hu.bme.aut.android.demo.database.tables.IndividualMatches
import hu.bme.aut.android.demo.database.tables.MatchParticipants
import hu.bme.aut.android.demo.database.tables.Matches
import hu.bme.aut.android.demo.database.tables.Seasons
import hu.bme.aut.android.demo.database.tables.TeamMembers
import hu.bme.aut.android.demo.database.tables.Teams
import hu.bme.aut.android.demo.database.tables.Users
import hu.bme.aut.android.demo.model.*
import hu.bme.aut.android.demo.service.FirebaseService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.collections.map

private val appLog = LoggerFactory.getLogger("MatchRoutes")

fun Route.matchRoutes(
    db: Database,
    matchWsClients: MutableList<DefaultWebSocketServerSession>,
    json: Json,
    applicationScope: CoroutineScope
) {

    // ==========================================
    // 1. PUBLIKUS VÉGPONTOK (Admin weboldalhoz)
    // ==========================================

    // --- MECCSEK LEKÉRDEZÉSE (Optimalizált N+1 Query javítással) ---
    get("/matches") {
        val round = call.request.queryParameters["round"]?.toIntOrNull()
        try {
            val matches = transaction(db) {
                // 1. Alap meccsek lekérése és ID-k kigyűjtése
                val matchRows = if (round != null) {
                    Matches.select { Matches.roundNumber eq round }.toList()
                } else {
                    Matches.selectAll().toList()
                }

                if (matchRows.isEmpty()) return@transaction emptyList<MatchDTO>()

                val matchIds = matchRows.map { it[Matches.id].value }

                // 2. Szótárak (Mapek) felépítése explicit típusokkal!
                val teamsMap: Map<Int, String> = Teams.selectAll().associate { it[Teams.id].value to it[Teams.name] }
                val seasonsMap: Map<Int, String> = Seasons.selectAll().associate { it[Seasons.id].value to it[Seasons.name] }
                val usersMap: Map<Int, ResultRow> = Users.selectAll().associateBy { it[Users.id].value }

                // 3. Az összes releváns Egyéni Meccs és Résztvevő lekérése EGYBEN, explicit típusokkal
                val allIndividualMatches = IndividualMatches.select { IndividualMatches.matchId inList matchIds }.toList()
                val individualMatchesByMatchId: Map<Int, List<ResultRow>> = allIndividualMatches.groupBy { it[IndividualMatches.matchId].value }

                val allParticipants = MatchParticipants.select { MatchParticipants.matchId inList matchIds }.toList()
                val participantsByMatchId: Map<Int, List<ResultRow>> = allParticipants.groupBy { it[MatchParticipants.matchId].value }

                // 4. Az adatok összefűzése a memóriában (0 extra adatbázis hívással!)
                matchRows.map { matchRow ->
                    val mId = matchRow[Matches.id].value
                    val homeTeamId = matchRow[Matches.homeTeamId].value
                    val guestTeamId = matchRow[Matches.guestTeamId].value
                    val sId = matchRow[Matches.seasonId].value

                    val homeName = teamsMap[homeTeamId] ?: "Ismeretlen"
                    val guestName = teamsMap[guestTeamId] ?: "Ismeretlen"
                    val seasonNameStr = seasonsMap[sId] ?: "Ismeretlen"

                    // Biztonságos olvasás az individualMatchesByMatchId-ből
                    val imList = individualMatchesByMatchId[mId] ?: emptyList()
                    val individualMatchesDto = imList.map { imRow ->
                        val hUserId = imRow[IndividualMatches.homePlayerId].value
                        val gUserId = imRow[IndividualMatches.guestPlayerId].value

                        val hUser = usersMap[hUserId]
                        val gUser = usersMap[gUserId]

                        IndividualMatchDTO(
                            id = imRow[IndividualMatches.id].value,
                            homePlayerId = hUserId,
                            homePlayerName = if (hUser != null) "${hUser[Users.lastName]} ${hUser[Users.firstName]}" else "Ismeretlen",
                            guestPlayerId = gUserId,
                            guestPlayerName = if (gUser != null) "${gUser[Users.lastName]} ${gUser[Users.firstName]}" else "Ismeretlen",
                            homeScore = imRow[IndividualMatches.homeScore],
                            guestScore = imRow[IndividualMatches.guestScore],
                            setScores = imRow[IndividualMatches.setScores],
                            status = imRow[IndividualMatches.status],
                            orderNumber = imRow[IndividualMatches.orderNumber]
                        )
                    }

                    // Biztonságos olvasás a participantsByMatchId-ből
                    val pList = participantsByMatchId[mId] ?: emptyList()
                    val participantsDto = pList.map { mpRow ->
                        val pUserId = mpRow[MatchParticipants.userId].value
                        val pUser = usersMap[pUserId]

                        MatchParticipantDTO(
                            id = mpRow[MatchParticipants.id].value,
                            userId = pUserId,
                            firebaseUid = pUser?.get(Users.firebaseUid) ?: "",
                            playerName = if (pUser != null) "${pUser[Users.lastName]} ${pUser[Users.firstName]}" else "Ismeretlen",
                            teamSide = mpRow[MatchParticipants.teamSide],
                            status = mpRow[MatchParticipants.status],
                            position = mpRow[MatchParticipants.position]
                        )
                    }

                    MatchDTO(
                        id = mId,
                        seasonId = sId,
                        seasonName = seasonNameStr,
                        roundNumber = matchRow[Matches.roundNumber] ?: 0,
                        homeTeamName = homeName,
                        guestTeamName = guestName,
                        homeScore = matchRow[Matches.homeTeamScore],
                        guestScore = matchRow[Matches.guestTeamScore],
                        date = matchRow[Matches.matchDate]?.toString() ?: "",
                        status = matchRow[Matches.status],
                        location = matchRow[Matches.location] ?: "",
                        homeTeamId = homeTeamId,
                        guestTeamId = guestTeamId,
                        individualMatches = individualMatchesDto,
                        participants = participantsDto,
                        homeTeamSigned = matchRow[Matches.homeTeamSigned],
                        guestTeamSigned = matchRow[Matches.guestTeamSigned]
                    )
                }
            }
            call.respond(matches)
        } catch (e: Exception) {
            appLog.error("Hiba a /matches lekérdezésekor: ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, "Adatbázis hiba történt")
        }
    }

    // --- EGYETLEN MECCS LEKÉRDEZÉSE (Optimalizált a részletek képernyőhöz) ---
    get("/matches/{id}") {
        val matchId = call.parameters["id"]?.toIntOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest, "Érvénytelen ID")
        try {
            val match = transaction(db) {
                val matchRow = Matches.select { Matches.id eq matchId }.singleOrNull() ?: return@transaction null

                val homeTeamEntityId = matchRow[Matches.homeTeamId]
                val guestTeamEntityId = matchRow[Matches.guestTeamId]
                val homeName = Teams.select { Teams.id eq homeTeamEntityId }.single()[Teams.name]
                val guestName = Teams.select { Teams.id eq guestTeamEntityId }.single()[Teams.name]

                val sId = matchRow[Matches.seasonId].value
                val seasonNameStr = Seasons.select { Seasons.id eq sId }.single()[Seasons.name]

                val individualMatches = IndividualMatches.select { IndividualMatches.matchId eq matchId }.map { imRow ->
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

                val participants = (MatchParticipants innerJoin Users).select { MatchParticipants.matchId eq matchId }.map { mpRow ->
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
                    seasonId = sId,
                    seasonName = seasonNameStr,
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
                    participants = participants,
                    homeTeamSigned = matchRow[Matches.homeTeamSigned],
                    guestTeamSigned = matchRow[Matches.guestTeamSigned]
                )
            }

            if (match == null) {
                call.respond(HttpStatusCode.NotFound, "Meccs nem található")
            } else {
                call.respond(match)
            }
        } catch (e: Exception) {
            appLog.error("Hiba a /matches/{id} lekérdezésekor: ${e.message}", e)
            call.respond(HttpStatusCode.InternalServerError, "Adatbázis hiba történt")
        }
    }

    // --- MÉRKŐZÉS TÖRLÉSE ---
    delete("/matches/{id}") {
        val matchId = call.parameters["id"]?.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)
        try {
            transaction(db) {
                IndividualMatches.deleteWhere { IndividualMatches.matchId eq matchId }
                MatchParticipants.deleteWhere { MatchParticipants.matchId eq matchId }
                Matches.deleteWhere { Matches.id eq matchId }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.Conflict, "Hiba a törlésnél: ${e.message}")
        }
    }

    // --- MÉRKŐZÉS LÉTREHOZÁSA ---
    post("/matches") {
        try {
            val request = call.receive<MatchCreateDTO>()

            if (request.homeTeamId == request.guestTeamId) {
                return@post call.respond(HttpStatusCode.BadRequest, "Egy csapat nem játszhat önmaga ellen!")
            }

            val newMatchId = transaction(db) {
                // 1. Meccs létrehozása
                val mId = Matches.insertAndGetId {
                    it[seasonId] = request.seasonId
                    it[roundNumber] = request.roundNumber
                    it[homeTeamId] = request.homeTeamId
                    it[guestTeamId] = request.guestTeamId
                    it[matchDate] = java.time.LocalDateTime.parse(request.matchDate) // Átalakítjuk a HTML dátumot
                    it[location] = request.location
                    it[status] = "scheduled"
                }

                // 2. Hazai csapat játékosainak hozzáadása (SELECTED státusszal)
                val homePlayers = TeamMembers.select { TeamMembers.teamId eq request.homeTeamId }.map { it[TeamMembers.userId] }
                homePlayers.forEach { uId ->
                    MatchParticipants.insert {
                        it[matchId] = mId
                        it[userId] = uId
                        it[teamSide] = "HOME"
                        it[status] = "SELECTED"
                    }
                }

                // 3. Vendég csapat játékosainak hozzáadása
                val guestPlayers = TeamMembers.select { TeamMembers.teamId eq request.guestTeamId }.map { it[TeamMembers.userId] }
                guestPlayers.forEach { uId ->
                    MatchParticipants.insert {
                        it[matchId] = mId
                        it[userId] = uId
                        it[teamSide] = "GUEST"
                        it[status] = "SELECTED"
                    }
                }

                mId.value
            }
            call.respond(HttpStatusCode.Created, mapOf("id" to newMatchId.toString(), "status" to "created"))
        } catch (e: Exception) {
            appLog.error("Hiba a meccs mentésekor: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, "Hiba a mentésnél: ${e.message}")
        }
    }

    // --- SZEZONOK LEKÉRDEZÉSE ---
    get("/seasons") {
        try {
            val seasons = transaction(db) {
                Seasons.selectAll().map {
                    SeasonDTO(
                        id = it[Seasons.id].value,
                        name = it[Seasons.name],
                        startDate = it[Seasons.startDate].toString(),
                        endDate = it[Seasons.endDate].toString(),
                        isActive = it[Seasons.isActive]
                    )
                }
            }
            call.respond(seasons)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, "Adatbázis hiba")
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

    // ==========================================
    // 2. VÉDETT VÉGPONTOK (Mobil applikációhoz)
    // ==========================================

    authenticate("firebase-auth") {

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

                                val userRow = Users.select { Users.id eq uId }.single()
                                val email = userRow[Users.email]

                                val token = FcmTokens.select { FcmTokens.userId eq uId }
                                    .singleOrNull()?.get(FcmTokens.token)

                                val matchRow = Matches.select { Matches.id eq mId }.single()
                                val homeTeamName = Teams.select { Teams.id eq matchRow[Matches.homeTeamId] }.single()[Teams.name]
                                val guestTeamName = Teams.select { Teams.id eq matchRow[Matches.guestTeamId] }.single()[Teams.name]

                                if (token != null) Triple(token, "$homeTeamName vs $guestTeamName", Pair(email, mId)) else null
                            }

                            notificationData?.let { (token, matchName, meta) ->
                                val (targetEmail, mId) = meta
                                // CSAK DATA PAYLOAD
                                FirebaseService.sendNotification(
                                    db = db,
                                    email = targetEmail,
                                    token = token,
                                    dataPayload = mapOf(
                                        "type" to "PLAYER_SELECTED",
                                        "matchId" to mId.toString(),
                                        "matchName" to matchName
                                    )
                                )
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

                    val isHomeCap = TeamMembers.select { (TeamMembers.teamId eq matchRow[Matches.homeTeamId]) and (TeamMembers.userId eq captainUserId) and (TeamMembers.isCaptain eq true) }.count() > 0
                    val isGuestCap = TeamMembers.select { (TeamMembers.teamId eq matchRow[Matches.guestTeamId]) and (TeamMembers.userId eq captainUserId) and (TeamMembers.isCaptain eq true) }.count() > 0

                    if (!isHomeCap && !isGuestCap) return@transaction null

                    // 1. Meccs elindítása (De a résztvevők státuszát NEM bántjuk, maradnak SELECTED, hogy le lehessen adni a sorrendet!)
                    Matches.update({ Matches.id eq matchId }) { it[status] = "in_progress" }

                    // 2. Kigyűjtjük az éppen JÁTSZÓ (keretben lévő) játékosok ID-jait, akiket NEM akarunk most értesíteni
                    val playingUserIds = MatchParticipants.slice(MatchParticipants.userId).select {
                        (MatchParticipants.matchId eq matchId) and (MatchParticipants.status eq "SELECTED")
                    }.map { it[MatchParticipants.userId] }

                    // 3. Értesítendők kigyűjtése: A KÉT CSAPAT MINDEN TAGJA, AKI NINCS A JÁTSZÓK KÖZÖTT (A nézők)
                    val tokensWithEmails = (TeamMembers innerJoin Users innerJoin FcmTokens)
                        .slice(Users.email, FcmTokens.token)
                        .select {
                            ((TeamMembers.teamId eq matchRow[Matches.homeTeamId]) or (TeamMembers.teamId eq matchRow[Matches.guestTeamId])) and
                                    (Users.id notInList playingUserIds)
                        }
                        .withDistinct()
                        .map { Pair(it[Users.email], it[FcmTokens.token]) }

                    val homeTeamName = Teams.select { Teams.id eq matchRow[Matches.homeTeamId] }.single()[Teams.name]
                    val guestTeamName = Teams.select { Teams.id eq matchRow[Matches.guestTeamId] }.single()[Teams.name]

                    Pair(tokensWithEmails, "$homeTeamName vs $guestTeamName")
                }

                if (notificationData != null) {
                    val (spectators, title) = notificationData

                    applicationScope.launch {
                        spectators.forEach { (email, token) ->
                            FirebaseService.sendNotification(
                                db = db,
                                email = email,
                                token = token,
                                dataPayload = mapOf(
                                    "type" to "MATCH_STARTED",
                                    "matchId" to matchId.toString(),
                                    "homeTeam" to title.split(" vs ")[0],
                                    "guestTeam" to title.split(" vs ")[1]
                                )
                            )
                        }
                    }
                }
                call.respond(HttpStatusCode.OK, mapOf("status" to "finalized"))

            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Szerver oldali hiba: ${e.message}")
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
                    val userRow = Users.select { Users.firebaseUid eq firebaseUid }.singleOrNull() ?: throw Exception("User not found")
                    val submittingUserId = userRow[Users.id]
                    val matchRow = Matches.select { Matches.id eq matchId }.singleOrNull() ?: throw Exception("Match not found")

                    val targetTeamId = if (lineupRequest.teamSide == "HOME") matchRow[Matches.homeTeamId] else matchRow[Matches.guestTeamId]
                    val isTeamMember = TeamMembers.select {
                        (TeamMembers.teamId eq targetTeamId) and (TeamMembers.userId eq submittingUserId)
                    }.count() > 0

                    if (!isTeamMember) throw Exception("Nem vagy a csapat tagja, így nem adhatsz le sorrendet!")
                    if (lineupRequest.positions.size != 4) throw Exception("Pontosan 4 játékost kell megadni a sorrendhez!")

                    // 1. Beírjuk a pozíciókat az adatbázisba (LOCKED)
                    val slottedUserIds = lineupRequest.positions.values.toList()
                    lineupRequest.positions.forEach { (pos, uId) ->
                        MatchParticipants.update({
                            (MatchParticipants.matchId eq matchId) and (MatchParticipants.userId eq uId) and (MatchParticipants.teamSide eq lineupRequest.teamSide)
                        }) {
                            it[position] = pos
                            it[status] = "LOCKED"
                        }
                    }

                    // 3. Megnézzük, hogy a TÖBBI csapat leadta-e már a sorrendet?
                    val otherSide = if (lineupRequest.teamSide == "HOME") "GUEST" else "HOME"
                    val otherSideLockedCount = MatchParticipants.select {
                        (MatchParticipants.matchId eq matchId) and (MatchParticipants.teamSide eq otherSide) and (MatchParticipants.status eq "LOCKED")
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

                        val pairings = listOf(
                            Pair(1, 1), Pair(2, 2), Pair(3, 3), Pair(4, 4),
                            Pair(1, 2), Pair(2, 3), Pair(3, 4), Pair(4, 1),
                            Pair(1, 3), Pair(2, 4), Pair(3, 1), Pair(4, 2),
                            Pair(1, 4), Pair(2, 1), Pair(3, 2), Pair(4, 3)
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

                                    it[this.homeScore] = 0
                                    it[this.guestScore] = 0
                                    it[this.homeSetsWon] = 0
                                    it[this.guestSetsWon] = 0
                                    it[this.setScores] = ""
                                }
                            } else {
                                appLog.error("Hiba a párosításnál! Hiányzó pozíció: H:$hPlayerId, G:$gPlayerId")
                            }
                        }
                    }
                    call.respond(HttpStatusCode.OK, mapOf("status" to "grid_generated"))
                } else {
                    call.respond(HttpStatusCode.OK, mapOf("status" to "waiting_for_opponent"))
                }

            } catch (e: Exception) {
                appLog.error("Hiba a sorrend mentésekor: ${e.message}", e)
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Hiba történt a sorrend leadásakor")
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
                    // --- WEBSOCKET BROADCAST ---
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

        // --- EGYÉNI MECCS BEFEJEZÉSE ALÁíRÁSSAL
        post("/matches/{matchId}/sign") {
            val principal = call.principal<UserIdPrincipal>()
            val firebaseUid = principal?.name ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val matchId = call.parameters["matchId"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, "Érvénytelen meccs ID")

            try {
                val result = transaction(db) {
                    val userRow = Users.select { Users.firebaseUid eq firebaseUid }.single()
                    val matchRow = Matches.select { Matches.id eq matchId }.single()

                    val isHome = TeamMembers.select { (TeamMembers.teamId eq matchRow[Matches.homeTeamId]) and (TeamMembers.userId eq userRow[Users.id]) }.count() > 0
                    val isGuest = TeamMembers.select { (TeamMembers.teamId eq matchRow[Matches.guestTeamId]) and (TeamMembers.userId eq userRow[Users.id]) }.count() > 0

                    if (!isHome && !isGuest) throw Exception("Nem vagy jogosult aláírni ezt a meccset!")

                    Matches.update({ Matches.id eq matchId }) {
                        if (isHome) it[homeTeamSigned] = true
                        if (isGuest) it[guestTeamSigned] = true
                    }

                    val updatedMatch = Matches.select { Matches.id eq matchId }.single()
                    val hSigned = updatedMatch[Matches.homeTeamSigned]
                    val gSigned = updatedMatch[Matches.guestTeamSigned]
                    var newStatus = updatedMatch[Matches.status]

                    var finalHomeScore = 0
                    var finalGuestScore = 0

                    if (hSigned && gSigned) {
                        newStatus = "finished"
                        val indMatches = IndividualMatches.select { IndividualMatches.matchId eq matchId }

                        indMatches.forEach { row ->
                            val hSets = row[IndividualMatches.homeScore]
                            val gSets = row[IndividualMatches.guestScore]
                            if (hSets > gSets) finalHomeScore++
                            else if (gSets > hSets) finalGuestScore++
                        }

                        Matches.update({ Matches.id eq matchId }) {
                            it[status] = newStatus
                            it[homeTeamScore] = finalHomeScore
                            it[guestTeamScore] = finalGuestScore
                        }
                    }

                    // Kibővített visszatérési érték a Push értesítésekhez
                    Triple(Triple(hSigned, gSigned, newStatus), finalHomeScore, finalGuestScore)
                }

                val (statusData, finalHomeScore, finalGuestScore) = result
                val (hSigned, gSigned, newStatus) = statusData

                // BROADCASTOLJUK a WebSocketen
                applicationScope.launch {
                    val event = WsEvent.MatchSignatureUpdated(matchId, hSigned, gSigned, newStatus)
                    val messageText = json.encodeToString(WsEvent.serializer(), event)
                    matchWsClients.toList().forEach { client ->
                        try { client.send(io.ktor.websocket.Frame.Text(messageText)) } catch (_: Exception) {}
                    }
                }

                call.respond(HttpStatusCode.OK, mapOf("status" to "signed", "matchStatus" to newStatus))

                // --- PUSH ÉRTESÍTÉSEK KIKÜLDÉSE HA LEZÁRULT A MECCS ---
                if (newStatus == "finished") {
                    applicationScope.launch {
                        val notificationsToSend = transaction(db) {
                            val matchRow = Matches.select { Matches.id eq matchId }.single()
                            val homeTeamId = matchRow[Matches.homeTeamId]
                            val guestTeamId = matchRow[Matches.guestTeamId]

                            val homeName = Teams.select { Teams.id eq homeTeamId }.single()[Teams.name]
                            val guestName = Teams.select { Teams.id eq guestTeamId }.single()[Teams.name]

                            // Összes játékos kigyűjtése
                            val players = (TeamMembers innerJoin Users innerJoin FcmTokens)
                                .slice(TeamMembers.teamId, Users.email, FcmTokens.token)
                                .select { (TeamMembers.teamId eq homeTeamId) or (TeamMembers.teamId eq guestTeamId) }
                                .map {
                                    // Kimentjük: CsapatID, Email, Token
                                    Triple(it[TeamMembers.teamId], it[Users.email], it[FcmTokens.token])
                                }
                                .distinctBy { it.third }

                            Triple(players, homeTeamId, Pair(homeName, guestName))
                        }

                        val (players, homeTeamId, teamNames) = notificationsToSend
                        val (homeName, guestName) = teamNames

                        players.forEach { (playerTeamId, email, token) ->
                            // 1. Kiszámoljuk, hogy az adott játékos nyert-e
                            val isHomePlayer = (playerTeamId == homeTeamId)
                            val isWin = if (isHomePlayer) (finalHomeScore > finalGuestScore) else (finalGuestScore > finalHomeScore)
                            val isDraw = (finalHomeScore == finalGuestScore)

                            val matchResult = if (isWin) "WIN" else if (isDraw) "DRAW" else "LOSS"

                            // 2. Csak nyers adatot (Data Payload) küldünk a telefonnak!
                            FirebaseService.sendNotification(
                                db = db,
                                email = email,
                                token = token,
                                // NINCS TITLE ÉS BODY, HISZEN A TELEFON FOGJA LEFORDÍTANI!
                                dataPayload = mapOf(
                                    "type" to "MATCH_FINISHED",
                                    "matchId" to matchId.toString(),
                                    "result" to matchResult,
                                    "homeTeam" to homeName,
                                    "guestTeam" to guestName,
                                    "homeScore" to finalHomeScore.toString(),
                                    "guestScore" to finalGuestScore.toString()
                                )
                            )
                        }
                    }
                }

            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Hiba")
            }
        }

        // --- KAPITÁNY: JÁTÉKOS MANUÁLIS HOZZÁADÁSA ---
        post("/matches/{id}/participants") {
            val matchId = call.parameters["id"]?.toIntOrNull()
            val request = call.receiveNullable<AddParticipantDTO>()

            if (matchId == null || request == null) {
                return@post call.respond(HttpStatusCode.BadRequest, "Érvénytelen meccs ID vagy hiányzó adatok")
            }

            val principal = call.principal<UserIdPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val firebaseUid = principal.name

            try {
                transaction(db) {
                    // 1. Lekérjük a hívó (kapitány) belső ID-ját
                    val callerId = Users.slice(Users.id)
                        .select { Users.firebaseUid eq firebaseUid }
                        .singleOrNull()?.get(Users.id)?.value
                        ?: throw IllegalArgumentException("Felhasználó nem található")

                    // 2. Lekérjük a meccset
                    val matchRow = Matches.select { Matches.id eq matchId }.singleOrNull()
                        ?: throw IllegalArgumentException("Meccs nem található")

                    if (matchRow[Matches.status] != "scheduled") {
                        throw IllegalArgumentException("A meccs már elindult vagy befejeződött!")
                    }

                    val homeTeamId = matchRow[Matches.homeTeamId]
                    val guestTeamId = matchRow[Matches.guestTeamId]

                    // 3. JAVÍTVA: Megnézzük a TeamMembers táblát, hogy a hívó kapitány-e!
                    val isHomeCaptain = TeamMembers.select {
                        (TeamMembers.teamId eq homeTeamId) and
                                (TeamMembers.userId eq callerId) and
                                (TeamMembers.isCaptain eq true)
                    }.count() > 0

                    val isGuestCaptain = TeamMembers.select {
                        (TeamMembers.teamId eq guestTeamId) and
                                (TeamMembers.userId eq callerId) and
                                (TeamMembers.isCaptain eq true)
                    }.count() > 0

                    val teamSide = when {
                        isHomeCaptain -> "HOME"
                        isGuestCaptain -> "GUEST"
                        else -> throw IllegalArgumentException("Csak a csapatkapitány adhat hozzá játékost!")
                    }

                    // 4. Ellenőrizzük, hogy a célpont játékos tagja-e a csapatnak
                    val targetTeamId = if (teamSide == "HOME") homeTeamId else guestTeamId
                    val isMember = TeamMembers.select {
                        (TeamMembers.teamId eq targetTeamId) and (TeamMembers.userId eq request.userId)
                    }.count() > 0

                    if (!isMember) {
                        throw IllegalArgumentException("A játékos nem tagja a csapatodnak!")
                    }

                    // 5. Megnézzük, nincs-e már benne véletlenül a meccsben
                    val alreadyExists = MatchParticipants.select {
                        (MatchParticipants.matchId eq matchId) and (MatchParticipants.userId eq request.userId)
                    }.count() > 0

                    if (alreadyExists) {
                        // Ha valamiért már benne volt, csak frissítjük a státuszát SELECTED-re
                        MatchParticipants.update({ (MatchParticipants.matchId eq matchId) and (MatchParticipants.userId eq request.userId) }) {
                            it[status] = "SELECTED"
                        }
                    } else {
                        // 6. Beszúrjuk az új jelentkezést SELECTED státusszal
                        MatchParticipants.insert {
                            it[this.matchId] = matchId
                            it[this.userId] = request.userId
                            it[this.teamSide] = teamSide
                            it[this.status] = "SELECTED"
                        }
                    }
                }
                call.respond(HttpStatusCode.OK, mapOf("message" to "Játékos sikeresen hozzáadva a kerethez!"))

            } catch (e: IllegalArgumentException) {
                // Saját, kontrollált hibáink (pl. nem ő a kapitány)
                call.respond(HttpStatusCode.Forbidden, e.message ?: "Művelet megtagadva")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Belső szerverhiba: ${e.message}")
            }
        }
    }
}