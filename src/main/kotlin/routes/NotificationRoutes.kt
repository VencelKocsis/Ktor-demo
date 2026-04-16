package hu.bme.aut.android.demo.routes

import hu.bme.aut.android.demo.database.tables.FcmTokens
import hu.bme.aut.android.demo.database.tables.Users
import hu.bme.aut.android.demo.model.*
import hu.bme.aut.android.demo.service.FirebaseService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.notificationRoutes(db: Database) {

    // --- MANUÁLIS TESZT VÉGPONT ---
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

        FirebaseService.sendNotification(
            db = db,
            email = request.targetEmail,
            token = notificationData,
            dataPayload = mapOf(
                "type" to "MANUAL_TEST",
                "title" to request.title,
                "body" to request.body
            )
        )

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
}