package hu.bme.aut.android.demo.service

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import hu.bme.aut.android.demo.database.tables.FcmTokens
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

object FirebaseService {
    private val appLog = LoggerFactory.getLogger("FirebaseService")

    // Külön CoroutineScope az értesítéseknek, hogy a háttérben menjenek el,
    // és ne várakoztassák meg a HTTP válaszokat (pl. a meccs indításakor)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Ezt majd az Application.kt-ból hívjuk meg induláskor
    fun init() {
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
    }

    // Ez az a függvény, amit a NotificationRoutes keres!
    fun sendNotification(db: Database, email: String, token: String, title: String, body: String) {
        if (FirebaseApp.getApps().isEmpty()) {
            appLog.warn("Firebase nincs inicializálva, nem küldhető FCM.")
            return
        }

        // Elindítjuk a küldést a háttérben
        serviceScope.launch {
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

                // Ha a token már nem él (letörölte az appot, stb.), kitöröljük az adatbázisból
                if (e.message?.contains("Requested entity was not found") == true || e.message?.contains("UNREGISTERED") == true) {
                    appLog.info("🧹 Halott token észlelve! Törlés az adatbázisból...")
                    transaction(db) {
                        FcmTokens.deleteWhere { FcmTokens.token eq token }
                    }
                }
            }
        }
    }
}