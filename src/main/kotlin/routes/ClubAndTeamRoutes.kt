package hu.bme.aut.android.demo.routes

import hu.bme.aut.android.demo.database.tables.Clubs
import hu.bme.aut.android.demo.database.tables.Matches
import hu.bme.aut.android.demo.database.tables.TeamMembers
import hu.bme.aut.android.demo.database.tables.Teams
import hu.bme.aut.android.demo.database.tables.Users
import hu.bme.aut.android.demo.model.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDate
import kotlin.collections.map

private val appLog = LoggerFactory.getLogger("ClubAndTeamRoutes")

fun Route.clubAndTeamRoutes(db: Database) {

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

    // --- CSAPAT MÓDOSÍTÁSA ---
    put("/teams/{id}") {
        val teamId = call.parameters["id"]?.toIntOrNull() ?: return@put call.respond(HttpStatusCode.BadRequest, "Érvénytelen csapat ID")
        val updateData = call.receive<TeamUpdateDTO>()
        val rowsAffected = transaction(db) {
            Teams.update({ Teams.id eq teamId }) {
                it[name] = updateData.name
                it[division] = if (updateData.division.isNullOrBlank()) null else updateData.division
            }
        }
        if (rowsAffected > 0) call.respond(HttpStatusCode.OK, mapOf("status" to "updated")) else call.respond(HttpStatusCode.NotFound, "Csapat nem található")
    }

    // ==========================================
    // 2. VÉDETT VÉGPONTOK (Mobil applikációhoz)
    // ==========================================

    authenticate("firebase-auth") {

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
    }
}