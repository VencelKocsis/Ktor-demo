package hu.bme.aut.android.demo.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import hu.bme.aut.android.demo.database.tables.Clubs
import hu.bme.aut.android.demo.database.tables.FcmTokens
import hu.bme.aut.android.demo.database.tables.IndividualMatches
import hu.bme.aut.android.demo.database.tables.MatchParticipants
import hu.bme.aut.android.demo.database.tables.Matches
import hu.bme.aut.android.demo.database.tables.Seasons
import hu.bme.aut.android.demo.database.tables.TeamMembers
import hu.bme.aut.android.demo.database.tables.Teams
import hu.bme.aut.android.demo.database.tables.Users
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDate

object DatabaseFactory {
    private val appLog = LoggerFactory.getLogger("DatabaseFactory")

    // Saját coroutine scope a háttérfolyamatokhoz (pl. a seedeléshez)
    private val dbScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(): Database {
        appLog.info("🗄️ Adatbázis kapcsolat inicializálása...")
        val ds = initDataSource()
        val db = Database.connect(ds)

        // 1. Táblák létrehozása
        transaction(db) {
            SchemaUtils.createMissingTablesAndColumns(
                FcmTokens, Users, Clubs, Teams, TeamMembers, Seasons,
                Matches, IndividualMatches, MatchParticipants, Rackets
            )
        }

        // 2. Tesztadatok betöltése a háttérben (hogy ne akassza meg a szerver indulását)
        dbScope.launch {
            try {
                transaction(db) { seedDatabaseIfNeeded() }
            } catch (e: Exception) {
                appLog.error("❌ Hiba a seedelésnél: ${e.message}", e)
            }
        }

        return db
    }

    private fun initDataSource(): HikariDataSource {
        val dbUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/demo"
        val dbUser = System.getenv("DB_USER") ?: "demo"
        val dbPassword = System.getenv("DB_PASSWORD") ?: "demo"

        val cfg = HikariConfig().apply {
            jdbcUrl = dbUrl
            username = dbUser
            password = dbPassword
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 3
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            addDataSourceProperty("sslmode", "require") // Renderhez kell
        }
        return HikariDataSource(cfg)
    }

    private fun seedDatabaseIfNeeded() {
        if (Clubs.selectAll().count() > 0) return

        appLog.info("🌱 Adatbázis feltöltése folyamatban...")

        Seasons.insert { it[name] = "2025 Ősz"; it[startDate] = LocalDate.of(2025, 9, 1); it[endDate] = LocalDate.of(2025, 12, 15); it[isActive] = false }
        val activeSeasonId = Seasons.insertAndGetId { it[name] = "2026 Tavasz"; it[startDate] = LocalDate.of(2026, 2, 1); it[endDate] = LocalDate.of(2026, 5, 31); it[isActive] = true }

        val beacId = Clubs.insertAndGetId { it[name] = "BEAC"; it[address] = "1117 Budapest, Bogdánfy u. 10." }
        val mafcId = Clubs.insertAndGetId { it[name] = "MAFC"; it[address] = "1111 Budapest, Műegyetem rkp. 3." }

        val beac1 = Teams.insertAndGetId { it[clubId] = beacId; it[name] = "BEAC I."; it[division] = "NB I." }
        val beac2 = Teams.insertAndGetId { it[clubId] = beacId; it[name] = "BEAC II."; it[division] = "Budapest I." }
        val mafc1 = Teams.insertAndGetId { it[clubId] = mafcId; it[name] = "MAFC I."; it[division] = "NB I." }
        val mafc2 = Teams.insertAndGetId { it[clubId] = mafcId; it[name] = "MAFC II."; it[division] = "Budapest I." }

        val teams = listOf(beac1, beac2, mafc1, mafc2)
        var userCounter = 1

        for (team in teams) {
            for (i in 1..4) {
                val uId = Users.insertAndGetId {
                    it[firebaseUid] = "dummyUid$userCounter" ; it[email] = "player${userCounter}@test.com"; it[firstName] = "Player"; it[lastName] = "$userCounter"
                }
                TeamMembers.insert { it[teamId] = team; it[userId] = uId; it[isCaptain] = (i == 1); it[joinedAt] = LocalDate.now() }
                userCounter++
            }
        }

        val pairings = listOf(
            Triple(beac1, mafc1, 1), Triple(mafc1, beac1, 2),
            Triple(beac2, mafc2, 3), Triple(mafc2, beac2, 4)
        )

        pairings.forEach { (hId, gId, round) ->
            val mId = Matches.insertAndGetId {
                it[seasonId] = activeSeasonId; it[roundNumber] = round; it[homeTeamId] = hId; it[guestTeamId] = gId
                it[status] = "finished"; it[matchDate] = LocalDate.now().plusDays(round.toLong() * 7).atTime(18, 30)
                it[location] = "Budapest, Mérnök u. 35, 1119"
            }

            val homePlayers = TeamMembers.select { TeamMembers.teamId eq hId }.map { it[TeamMembers.userId] }
            homePlayers.forEach { uId ->
                MatchParticipants.insert {
                    it[matchId] = mId; it[userId] = uId; it[teamSide] = "HOME"; it[status] = "SELECTED"
                }
            }

            val guestPlayers = TeamMembers.select { TeamMembers.teamId eq gId }.map { it[TeamMembers.userId] }
            guestPlayers.forEach { uId ->
                MatchParticipants.insert {
                    it[matchId] = mId; it[userId] = uId; it[teamSide] = "GUEST"; it[status] = "SELECTED"
                }
            }

            Matches.update({ Matches.id eq mId }) {
                it[homeTeamScore] = (5..10).random()
                it[guestTeamScore] = (5..10).random()
            }
        }
        appLog.info("✅ Adatbázis feltöltve tesztadatokkal!")
    }
}