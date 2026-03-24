package hu.bme.aut.android.demo.database.tables

import org.jetbrains.exposed.dao.id.IntIdTable

object Users : IntIdTable("users") {
    val firebaseUid = varchar("firebase_uid", 128).uniqueIndex()
    val email = varchar("email", 100).uniqueIndex()
    val firstName = varchar("first_name", 100)
    val lastName = varchar("last_name", 100)
    val role = varchar("role", 20).default("user")
}

object FcmTokens : IntIdTable("fcm_tokens") {
    val userId = reference("user_id", Users).uniqueIndex()
    val token = text("token")
}