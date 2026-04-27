package hu.bme.aut.android.demo.routes

import hu.bme.aut.android.demo.database.tables.FcmTokens
import hu.bme.aut.android.demo.database.tables.Rackets
import hu.bme.aut.android.demo.database.tables.Users
import hu.bme.aut.android.demo.model.MarketItemDTO
import hu.bme.aut.android.demo.model.RacketDTO
import hu.bme.aut.android.demo.service.FirebaseService
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
                            it[isForSale] = dto.isForSale
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
                            it[isForSale] = dto.isForSale
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

        // --- 2. PIAC LEKÉRDEZÉSE ---
        get("/api/market/equipment") {
            try {
                val marketItems = transaction(db) {
                    (Rackets innerJoin Users).select { Rackets.isForSale eq true }.map { row ->
                        MarketItemDTO(
                            racket = RacketDTO(
                                id = row[Rackets.id].value,
                                bladeManufacturer = row[Rackets.bladeManufacturer],
                                bladeModel = row[Rackets.bladeModel],
                                fhRubberManufacturer = row[Rackets.fhRubberManufacturer],
                                fhRubberModel = row[Rackets.fhRubberModel],
                                fhRubberColor = row[Rackets.fhRubberColor],
                                bhRubberManufacturer = row[Rackets.bhRubberManufacturer],
                                bhRubberModel = row[Rackets.bhRubberModel],
                                bhRubberColor = row[Rackets.bhRubberColor],
                                isForSale = true
                            ),
                            ownerName = "${row[Users.lastName]} ${row[Users.firstName]}",
                            ownerId = row[Users.id].value
                        )
                    }
                }
                call.respond(HttpStatusCode.OK, marketItems)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Szerver hiba")
            }
        }

        // --- 3. ÉRDEKLŐDÉS KÜLDÉSE ---
        post("/api/market/equipment/{id}/inquire") {
            val principal = call.principal<UserIdPrincipal>()
            val inquirerFirebaseUid = principal?.name ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val racketId = call.parameters["id"]?.toIntOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)

            try {
                val notificationData = transaction(db) {
                    val inquirerRow = Users.select { Users.firebaseUid eq inquirerFirebaseUid }.singleOrNull() ?: return@transaction null
                    val inquirerName = "${inquirerRow[Users.lastName]} ${inquirerRow[Users.firstName]}"
                    val inquirerEmail = inquirerRow[Users.email]

                    val racketRow = Rackets.select { Rackets.id eq racketId }.singleOrNull() ?: return@transaction null
                    val ownerId = racketRow[Rackets.userId]
                    val racketName = "${racketRow[Rackets.bladeManufacturer]} ${racketRow[Rackets.bladeModel]}"

                    val ownerRow = Users.select { Users.id eq ownerId }.singleOrNull() ?: return@transaction null
                    val ownerEmail = ownerRow[Users.email]

                    val ownerToken = FcmTokens.select { FcmTokens.userId eq ownerId }.singleOrNull()?.get(FcmTokens.token)

                    if (ownerToken != null) {
                        Triple(ownerEmail, ownerToken, Pair(inquirerName, racketName))
                    } else null
                }

                if (notificationData != null) {
                    val (ownerEmail, token, info) = notificationData
                    val (inquirerName, racketName) = info

                    // Push értesítés küldése a FirebaseService segítségével
                    FirebaseService.sendNotification(
                        db = db,
                        email = ownerEmail,
                        token = token,
                        dataPayload = mapOf(
                            "type" to "MARKET_INQUIRY",
                            "title" to "Érdeklődés felszerelésre!",
                            "body" to "$inquirerName érdeklődik a '$racketName' ütőd iránt!",
                            "inquirerName" to inquirerName
                        )
                    )
                    call.respond(HttpStatusCode.OK, mapOf("status" to "Notification sent"))
                } else {
                    // Ha a tulajnak nincs FCM tokenje, jelezzük
                    call.respond(HttpStatusCode.NotFound, "A tulajdonos nem kaphat értesítést (nincs token).")
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Szerver hiba")
            }
        }
    }
}