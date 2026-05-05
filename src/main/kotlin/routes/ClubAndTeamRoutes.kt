package hu.bme.aut.android.demo.routes

import hu.bme.aut.android.demo.database.tables.Clubs
import hu.bme.aut.android.demo.database.tables.FcmTokens
import hu.bme.aut.android.demo.database.tables.Matches
import hu.bme.aut.android.demo.database.tables.TeamMembers
import hu.bme.aut.android.demo.database.tables.Teams
import hu.bme.aut.android.demo.database.tables.Users
import hu.bme.aut.android.demo.model.*
import hu.bme.aut.android.demo.service.FirebaseService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDate
import kotlin.collections.map

private val appLog = LoggerFactory.getLogger("ClubAndTeamRoutes")

fun Route.clubAndTeamRoutes(
    db: Database,
    applicationScope: CoroutineScope
) {

    // ==========================================
    // 1. PUBLIKUS VÉGPONTOK (Admin weboldalhoz)
    // ==========================================

    // --- KLUBOK LEKÉRDEZÉSE ---
    get("/clubs") {
        try {
            val clubs = transaction(db) {
                Clubs.selectAll().map {
                    ClubDTO(
                        id = it[Clubs.id].value,
                        name = it[Clubs.name],
                        address = it[Clubs.address]
                    )
                }
            }
            call.respond(clubs)
        } catch (e: Exception) {
            appLog.error("Hiba a /clubs lekérdezésekor", e)
            call.respond(HttpStatusCode.InternalServerError, "Adatbázis hiba")
        }
    }

    // --- KLUB LÉTREHOZÁSA ---
    post("/clubs") {
        try {
            val request = call.receive<ClubCreateDTO>()
            val newId = transaction(db) {
                Clubs.insertAndGetId {
                    it[name] = request.name
                    it[address] = request.address
                }.value
            }
            call.respond(HttpStatusCode.Created, mapOf("id" to newId.toString(), "status" to "created"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Hiba a mentésnél: ${e.message}")
        }
    }

    // --- KLUB MÓDOSÍTÁSA ---
    put("/clubs/{id}") {
        try {
            val clubId = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest)
            val request = call.receive<ClubCreateDTO>()
            transaction(db) {
                Clubs.update({ Clubs.id eq clubId }) {
                    it[name] = request.name
                    it[address] = request.address
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Hiba: ${e.message}")
        }
    }

    // --- KLUB TÖRLÉSE ---
    delete("/clubs/{id}") {
        val clubId = call.parameters["id"]?.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)
        try {
            transaction(db) { Clubs.deleteWhere { Clubs.id eq clubId } }
            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.Conflict, "Nem törölhető! Töröld előbb a klubhoz tartozó csapatokat.")
        }
    }

    // --- CSAPATOK LEKÉRDEZÉSE ---
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

    // --- CSAPAT LÉTREHOZÁSA KAPITÁNNYAL ---
    post("/teams") {
        try {
            val request = call.receive<TeamCreateDTO>()
            val newId = transaction(db) {
                // 1. Létrehozzuk magát a csapatot
                val tId = Teams.insertAndGetId {
                    it[clubId] = request.clubId
                    it[name] = request.name
                    it[division] = if (request.division.isNullOrBlank()) null else request.division
                }

                // 2. Azonnal felvesszük az első tagot kapitányként!
                TeamMembers.insert {
                    it[teamId] = tId
                    it[userId] = request.captainUserId
                    it[isCaptain] = true
                    it[joinedAt] = LocalDate.now()
                }

                tId.value
            }
            call.respond(HttpStatusCode.Created, mapOf("id" to newId.toString(), "status" to "created"))
        } catch (e: Exception) {
            appLog.error("Hiba a csapat létrehozásakor: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, "Hiba a mentésnél: ${e.message}")
        }
    }

    // --- CSAPAT MÓDOSÍTÁSA (Név, Divízió, Tagok, Kapitány) ---
    put("/teams/{id}") {
        val teamId = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, "Érvénytelen csapat ID")
        try {
            val updateData = call.receive<TeamUpdateDTO>()

            transaction(db) {
                // 1. Alapadatok frissítése
                Teams.update({ Teams.id eq teamId }) {
                    it[name] = updateData.name
                    it[division] = if (updateData.division.isNullOrBlank()) null else updateData.division
                }
                // 2. Tagok frissítése (Törlünk mindenkit, majd felvesszük a kiválasztottakat)
                TeamMembers.deleteWhere { TeamMembers.teamId eq teamId }

                updateData.memberIds.forEach { uId ->
                    TeamMembers.insert {
                        it[this.teamId] = teamId
                        it[userId] = uId
                        it[isCaptain] = (uId == updateData.captainUserId) // A kapitányt is itt állítjuk be!
                        it[joinedAt] = LocalDate.now()
                    }
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Hiba a frissítésnél: ${e.message}")
        }
    }

    // --- CSAPAT TÖRLÉSE ---
    delete("/teams/{id}") {
        val teamId = call.parameters["id"]?.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)
        try {
            transaction(db) {
                // Először a tagokat kell törölni (Foreign Key megszorítás miatt)
                TeamMembers.deleteWhere { TeamMembers.teamId eq teamId }
                // Majd a csapatot
                Teams.deleteWhere { Teams.id eq teamId }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.Conflict, "Nem törölhető! Töröld előbb a csapathoz tartozó meccseket.")
        }
    }

    // ==========================================
    // 2. VÉDETT VÉGPONTOK (Mobil applikációhoz)
    // ==========================================

    authenticate("firebase-auth") {

        // ==============================================================================
// CSAPATTAGOK KEZELÉSE (MOBIL VÉGPONTOK - ÉRTESÍTÉSEKKEL)
// ==============================================================================

        /**
         * Új tag hozzáadása egy csapathoz.
         * * Létrehozza a kapcsolatot az adatbázisban, majd a háttérben (applicationScope)
         * kiküld egy "TEAM_ADDED" típusú Push Értesítést az érintett felhasználónak.
         */
        post("/teams/{id}/members") {
            val teamId = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Érvénytelen csapat ID")
            val request = call.receive<TeamMemberOperationDTO>()

            // 1. Adatbázis művelet (Beszúrás)
            transaction(db) {
                TeamMembers.insert {
                    it[this.teamId] = teamId
                    it[userId] = request.userId
                    it[isCaptain] = false
                    it[joinedAt] = LocalDate.now()
                }
            }

            // 2. Értesítés aszinkron kiküldése (Nem akasztja meg a HTTP választ)
            applicationScope.launch {
                try {
                    val notificationData = transaction(db) {
                        val userRow = Users.select { Users.id eq request.userId }.singleOrNull()
                        val teamRow = Teams.select { Teams.id eq teamId }.singleOrNull()
                        val token = FcmTokens.select { FcmTokens.userId eq request.userId }.singleOrNull()?.get(FcmTokens.token)

                        if (userRow != null && teamRow != null && token != null) {
                            Triple(token, userRow[Users.email], teamRow[Teams.name])
                        } else null
                    }

                    notificationData?.let { (token, email, teamName) ->
                        FirebaseService.sendNotification(
                            db = db,
                            email = email,
                            token = token,
                            dataPayload = mapOf(
                                "type" to "TEAM_ADDED",
                                "teamId" to teamId.toString(),
                                "teamName" to teamName
                            )
                        )
                    }
                } catch (e: Exception) {
                    appLog.error("❌ Hiba a csapathoz adás értesítésénél: ${e.message}")
                }
            }

            call.respond(HttpStatusCode.Created, mapOf("status" to "added"))
        }

        /**
         * Tag eltávolítása (kirúgása) egy csapatból.
         * * Mivel a törlés után már nem tudnánk lekérdezni a felhasználó adatait az értesítéshez,
         * először lefoglaljuk a tokent és a neveket, majd törlünk, és végül kiküldjük a "TEAM_REMOVED" értesítést.
         */
        delete("/teams/{teamId}/members/{userId}") {
            val teamId = call.parameters["teamId"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Érvénytelen ID")
            val userId = call.parameters["userId"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Érvénytelen ID")

            // Átmeneti tárolók az értesítéshez
            var tokenToSend: String? = null
            var emailToSend: String? = null
            var teamNameToSend: String? = null

            // 1. Adatok kinyerése ÉS Törlés (Egy tranzakcióban)
            val rowsDeleted = transaction(db) {
                val userRow = Users.select { Users.id eq userId }.singleOrNull()
                val teamRow = Teams.select { Teams.id eq teamId }.singleOrNull()
                tokenToSend = FcmTokens.select { FcmTokens.userId eq userId }.singleOrNull()?.get(FcmTokens.token)

                if (userRow != null) emailToSend = userRow[Users.email]
                if (teamRow != null) teamNameToSend = teamRow[Teams.name]

                TeamMembers.deleteWhere { (TeamMembers.teamId eq teamId) and (TeamMembers.userId eq userId) }
            }

            // 2. Értesítés aszinkron kiküldése (Ha sikeres volt a törlés)
            if (rowsDeleted > 0) {
                if (tokenToSend != null && emailToSend != null && teamNameToSend != null) {
                    applicationScope.launch {
                        try {
                            FirebaseService.sendNotification(
                                db = db,
                                email = emailToSend!!,
                                token = tokenToSend!!,
                                dataPayload = mapOf(
                                    "type" to "TEAM_REMOVED",
                                    "teamId" to teamId.toString(),
                                    "teamName" to teamNameToSend!!
                                )
                            )
                        } catch (e: Exception) {
                            appLog.error("❌ Hiba a csapatból kirúgás értesítésénél: ${e.message}")
                        }
                    }
                }
                call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
            } else {
                call.respond(HttpStatusCode.NotFound, "Nincs ilyen csapattag")
            }
        }
    }
}