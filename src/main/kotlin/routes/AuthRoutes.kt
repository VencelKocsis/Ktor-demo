package hu.bme.aut.android.demo.routes

import hu.bme.aut.android.demo.database.tables.TeamMembers
import hu.bme.aut.android.demo.database.tables.Users
import hu.bme.aut.android.demo.model.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val appLog = LoggerFactory.getLogger("AuthRoutes")

fun Route.authRoutes(db: Database) {

    // ==========================================
    // 1. PUBLIKUS VÉGPONTOK (Admin weboldalhoz)
    // ==========================================

    // --- SZABAD JÁTÉKOSOK LEKÉRDEZÉSE ---
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

    // ==========================================
    // VÉDETT VÉGPONTOK (Mobil applikációhoz)
    // ==========================================

    authenticate("firebase-auth") {

        // --- MINDEN FELHASZNÁLÓ LEKÉRDEZÉSE (A legördülő menühöz) ---
        get("/users") {
            try {
                val allUsers = transaction(db) {
                    Users.selectAll().map { row ->
                        MemberDTO(
                            userId = row[Users.id].value,
                            firebaseUid = row[Users.firebaseUid],
                            name = "${row[Users.lastName]} ${row[Users.firstName]}",
                            isCaptain = false
                        )
                    }
                }
                call.respond(HttpStatusCode.OK, allUsers)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Adatbázis hiba")
            }
        }

        // --- AUTH SYNC ---
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

        // --- SAJÁT PROFIL MÓDOSÍTÁSA ---
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
    }
}