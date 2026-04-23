package hu.bme.aut.android.demo.routes

import hu.bme.aut.android.demo.database.tables.Rackets
import hu.bme.aut.android.demo.database.tables.Users
import hu.bme.aut.android.demo.model.RacketDTO
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

private val appLog = LoggerFactory.getLogger("RacketRoutes")

fun Route.racketRoutes(db: Database) {

    authenticate("firebase-auth") {

        // --- ÜTŐ LÉTREHOZÁSA VAGY MÓDOSÍTÁSA (POST) ---
        post("/api/users/equipment") {
            val principal = call.principal<UserIdPrincipal>()
            val firebaseUid = principal?.name ?: return@post call.respond(HttpStatusCode.Unauthorized)

            try {
                val dto = call.receive<RacketDTO>()

                transaction(db) {
                    val internalUser = Users.select { Users.firebaseUid eq firebaseUid }.singleOrNull()
                        ?: throw IllegalArgumentException("Felhasználó nem található!")

                    val internalUserId = internalUser[Users.id]

                    if (dto.id == null) {
                        // HA NINCS ID -> ÚJ LÉTREHOZÁSA
                        Rackets.insert {
                            it[userId] = internalUserId
                            it[bladeManufacturer] = dto.bladeManufacturer
                            it[bladeModel] = dto.bladeModel
                            it[fhRubberManufacturer] = dto.fhRubberManufacturer
                            it[fhRubberModel] = dto.fhRubberModel
                            it[fhRubberColor] = dto.fhRubberColor
                            it[bhRubberManufacturer] = dto.bhRubberManufacturer
                            it[bhRubberModel] = dto.bhRubberModel
                            it[bhRubberColor] = dto.bhRubberColor
                        }
                    } else {
                        // HA VAN ID -> MEGLÉVŐ MÓDOSÍTÁSA
                        Rackets.update({ (Rackets.id eq dto.id) and (Rackets.userId eq internalUserId) }) {
                            it[bladeManufacturer] = dto.bladeManufacturer
                            it[bladeModel] = dto.bladeModel
                            it[fhRubberManufacturer] = dto.fhRubberManufacturer
                            it[fhRubberModel] = dto.fhRubberModel
                            it[fhRubberColor] = dto.fhRubberColor
                            it[bhRubberManufacturer] = dto.bhRubberManufacturer
                            it[bhRubberModel] = dto.bhRubberModel
                            it[bhRubberColor] = dto.bhRubberColor
                        }
                    }
                }
                call.respond(HttpStatusCode.OK, mapOf("message" to "Felszerelés elmentve!"))

            } catch (e: Exception) {
                appLog.error("Hiba az ütő mentésekor: ${e.message}")
                call.respond(HttpStatusCode.InternalServerError, "Hiba a mentés során: ${e.message}")
            }
        }

        // --- ÜTŐ TÖRLÉSE (DELETE) ---
        delete("/api/users/equipment/{racketId}") {
            val principal = call.principal<UserIdPrincipal>()
            val firebaseUid = principal?.name ?: return@delete call.respond(HttpStatusCode.Unauthorized)
            val racketId = call.parameters["racketId"]?.toIntOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest, "Érvénytelen ID")

            try {
                val deletedCount = transaction(db) {
                    val internalUser = Users.select { Users.firebaseUid eq firebaseUid }.singleOrNull()
                        ?: throw IllegalArgumentException("User nem található")

                    // Csak akkor törlődhet, ha a Racket az övé!
                    Rackets.deleteWhere {
                        (Rackets.id eq racketId) and (Rackets.userId eq internalUser[Users.id])
                    }
                }

                if (deletedCount > 0) {
                    call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
                } else {
                    call.respond(HttpStatusCode.NotFound, "Ütő nem található vagy nincs jogosultságod törölni.")
                }

            } catch (e: Exception) {
                appLog.error("Hiba az ütő törlésekor: ${e.message}")
                call.respond(HttpStatusCode.InternalServerError, "Szerver hiba")
            }
        }
    }
}