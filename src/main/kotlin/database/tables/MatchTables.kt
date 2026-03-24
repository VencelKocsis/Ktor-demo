package hu.bme.aut.android.demo.database.tables

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

object Seasons : IntIdTable("seasons") {
    val name = varchar("name", 100)
    val startDate = date("start_date")
    val endDate = date("end_date")
    val isActive = bool("is_active").default(false)
}

object Matches : IntIdTable("matches") {
    val seasonId = reference("season_id", Seasons)
    val roundNumber = integer("round_number")
    val homeTeamId = reference("home_team_id", Teams)
    val guestTeamId = reference("guest_team_id", Teams)
    val homeTeamScore = integer("home_team_score").default(0)
    val guestTeamScore = integer("guest_team_score").default(0)
    val matchDate = datetime("match_date").nullable()
    val status = varchar("status", 20).default("scheduled")
    val location = varchar("location", 255).nullable()
    val homeTeamSigned = bool("home_team_signed").default(false)
    val guestTeamSigned = bool("guest_team_signed").default(false)
}

object MatchParticipants : IntIdTable("match_participants") {
    val matchId = reference("match_id", Matches).index()
    val userId = reference("user_id", Users)
    val teamSide = varchar("team_side", 10) // 'HOME', 'GUEST'
    val status = varchar("status", 20).default("APPLIED") // 'APPLIED', 'SELECTED', 'LOCKED'

    // A játékos pozíciója a csapaton belül (1, 2, 3 vagy 4)
    val position = integer("position").nullable()
}

object IndividualMatches : IntIdTable("individual_matches") {
    val matchId = reference("match_id", Matches).index()
    val homePlayerId = reference("home_player_id", Users)
    val guestPlayerId = reference("guest_player_id", Users)

    // Hanyadik meccs a 16-ból? (Sorrend a megjelenítéshez)
    val orderNumber = integer("order_number").default(0)

    val homeScore = integer("home_score").default(0) // Szettek (pl 3)
    val guestScore = integer("guest_score").default(0) // Szettek (pl 1)

    val homeSetsWon = integer("home_sets_won").default(0)
    val guestSetsWon = integer("guest_sets_won").default(0)

    // A szettek részletes pontszámai (Pl: "11-8, 9-11, 11-5, 11-6")
    val setScores = varchar("set_scores", 255).nullable()

    // A meccs állapota
    val status = varchar("status", 20).default("pending") // 'pending', 'in_progress', 'finished'
}