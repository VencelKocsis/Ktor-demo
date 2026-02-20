package hu.bme.aut.android.demo

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.javatime.CurrentDateTime

// --- FELHASZNÁLÓK ÉS JOGOSULTSÁGOK ---
object Users : IntIdTable("users") {
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val firstName = varchar("first_name", 100)
    val lastName = varchar("last_name", 100)
    val birthDate = date("birth_date").nullable()
    val profileImageUrl = varchar("profile_image_url", 255).nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
    val role = varchar("role", 50).default("user")
}

// --- EGYESÜLETEK ÉS CSAPATOK ---
object Clubs : IntIdTable("clubs") {
    val name = varchar("name", 255)
    val address = varchar("address", 255).nullable()
    val contactEmail = varchar("contact_email", 255).nullable()
    val website = varchar("website", 255).nullable()
}

object Teams : IntIdTable("teams") {
    val clubId = reference("club_id", Clubs)
    val name = varchar("name", 255)
    val division = varchar("division", 100).nullable()
}

object TeamMembers : IntIdTable("team_members") {
    val teamId = reference("team_id", Teams)
    val userId = reference("user_id", Users)
    val isCaptain = bool("is_captain").default(false)
    val joinedAt = date("joined_at").nullable() // Ideális esetben CurrentDate
    val leftAt = date("left_at").nullable()
}

// --- BAJNOKSÁG SZERKEZET ---
object Seasons : IntIdTable("seasons") {
    val name = varchar("name", 255)
    val startDate = date("start_date").nullable()
    val endDate = date("end_date").nullable()
    val isActive = bool("is_active").default(true)
}

object Matches : IntIdTable("matches") {
    val seasonId = reference("season_id", Seasons)
    val roundNumber = integer("round_number").nullable()
    val homeTeamId = reference("home_team_id", Teams)
    val guestTeamId = reference("guest_team_id", Teams)
    val location = varchar("location", 255).nullable()
    val matchDate = datetime("match_date").nullable()
    val homeTeamScore = integer("home_team_score").default(0)
    val guestTeamScore = integer("guest_team_score").default(0)
    val status = varchar("status", 50).default("scheduled")
}

object IndividualGames : IntIdTable("individual_games") {
    val matchId = reference("match_id", Matches)
    val homePlayerId = reference("home_player_id", Users)
    val guestPlayerId = reference("guest_player_id", Users)
    val homeSetsWon = integer("home_sets_won").default(0)
    val guestSetsWon = integer("guest_sets_won").default(0)
    val setScores = text("set_scores").nullable()
}
