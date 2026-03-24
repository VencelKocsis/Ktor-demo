package hu.bme.aut.android.demo.database.tables

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.date

object Clubs : IntIdTable("clubs") {
    val name = varchar("name", 100)
    val address = varchar("address", 255)
}

object Teams : IntIdTable("teams") {
    val clubId = reference("club_id", Clubs)
    val name = varchar("name", 100)
    val division = varchar("division", 50).nullable()
}

object TeamMembers : IntIdTable("team_members") {
    val teamId = reference("team_id", Teams)
    val userId = reference("user_id", Users)
    val isCaptain = bool("is_captain").default(false)
    val joinedAt = date("joined_at")
}