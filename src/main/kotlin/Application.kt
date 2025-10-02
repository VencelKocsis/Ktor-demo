package hu.bme.aut.android.demo

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import io.ktor.serialization.kotlinx.json.*
import java.time.Duration
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import io.ktor.server.http.content.* // ----- DTO-k -----
@Serializable
data class PlayerDTO(val id: Int, val name: String, val age: Int?)

@Serializable
data class NewPlayerDTO(val name: String, val age: Int?)

// ----- WebSocket események -----
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class WsEvent {
    @Serializable
    @SerialName("PlayerAdded")
    data class PlayerAdded(val player: PlayerDTO) : WsEvent()

    @Serializable
    @SerialName("PlayerDeleted")
    data class PlayerDeleted(val id: Int) : WsEvent()
}

// ----- Exposed táblák -----
object Players : Table("players") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", length = 100)
    val age = integer("age").nullable()
    override val primaryKey = PrimaryKey(id)
}

// ----- DB init -----
fun initDataSource(): HikariDataSource {
    val cfg = HikariConfig().apply {
        jdbcUrl = System.getenv("JDBC_DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/demo"
        username = System.getenv("DB_USER") ?: "demo"
        password = System.getenv("DB_PASSWORD") ?: "demo"
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 3
    }
    return HikariDataSource(cfg)
}

fun initDatabase(ds: HikariDataSource): Database {
    val db = Database.connect(ds)
    transaction(db) {
        SchemaUtils.create(Players)
    }
    return db
}

// ----- Main -----
fun main() {
    val ds = initDataSource()
    val db = initDatabase(ds)
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module(db)
    }.start(wait = true)
}

fun Application.module(db: Database) {
    install(ContentNegotiation) { json() }
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(30)
    }

    val clients = mutableListOf<DefaultWebSocketServerSession>()
    val serializersModule = SerializersModule {
        polymorphic(WsEvent::class) {
            subclass(WsEvent.PlayerAdded::class, WsEvent.PlayerAdded.serializer())
            subclass(WsEvent.PlayerDeleted::class, WsEvent.PlayerDeleted.serializer())
        }
    }

    val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
    }

    routing {
        get("/players") {
            val players = transaction(db) {
                Players.selectAll().map {
                    PlayerDTO(it[Players.id], it[Players.name], it[Players.age])
                }
            }
            call.respond(players)
        }

        post("/players") {
            val newPlayer = call.receive<NewPlayerDTO>()
            val id = transaction(db) {
                Players.insert {
                    it[name] = newPlayer.name
                    it[age] = newPlayer.age
                } get Players.id
            }
            val player = PlayerDTO(id, newPlayer.name, newPlayer.age)

            // broadcast
            val event = WsEvent.PlayerAdded(player)
            val message = json.encodeToString(WsEvent.serializer(), event)

            println("DEBUG: Broadcasting PlayerAdded for ID $id to ${clients.size} clients.")

            clients.forEach { session ->
                session.send(message)
            }

            call.respond(player)
        }

        delete("/players/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) {
                transaction(db) {
                    Players.deleteWhere { Players.id eq id }
                }

                // broadcast
                val event = WsEvent.PlayerDeleted(id)
                val message = json.encodeToString(WsEvent.serializer(), event)
                clients.forEach { session -> session.send(message) }

                call.respond(mapOf("status" to "deleted"))
            } else {
                call.respondText("Invalid id")
            }
        }

        // WebSocket endpoint
        webSocket("/ws/players") {
            clients.add(this)
            try {
                incoming.consumeEach { /* szerver csak küld */ }
            } finally {
                clients.remove(this)
            }
        }

        // Statikus fájlok kiszolgálása
        staticResources("/", "static") {
            default("admin_dashboard.html")
        }
    }
}
