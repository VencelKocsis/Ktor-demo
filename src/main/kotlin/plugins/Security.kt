package hu.bme.aut.android.demo.plugins

import com.google.firebase.auth.FirebaseAuth as FirebaseAdminAuth
import io.ktor.server.application.*
import io.ktor.server.auth.*
import org.slf4j.LoggerFactory

private val appLog = LoggerFactory.getLogger("Security")

fun Application.configureSecurity() {
    install(Authentication) {
        bearer("firebase-auth") {
            authenticate { tokenCredential ->
                try {
                    val decodedToken =
                        FirebaseAdminAuth.getInstance().verifyIdToken(tokenCredential.token)
                    UserIdPrincipal(decodedToken.uid)
                } catch (e: Exception) {
                    appLog.warn("Firebase token verification failed: ${e.message}")
                    null
                }
            }
        }
    }
}