package hu.bme.aut.android.demo.model

import kotlinx.serialization.Serializable

@Serializable
data class TeamWithMembersDTO(
    val teamId: Int,
    val teamName: String,
    val clubName: String,
    val division: String?,
    val members: List<MemberDTO>,
    val matchesPlayed: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val points: Int
)

@Serializable
data class MemberDTO(
    val userId: Int,
    val name: String,
    val isCaptain: Boolean
)

@Serializable
data class MatchDTO(
    val id: Int,
    val roundNumber: Int,
    val homeTeamName: String,
    val guestTeamName: String,
    val homeScore: Int,
    val guestScore: Int,
    val date: String,
    val status: String
)

@Serializable
data class FcmTokenRegistration(
    val email: String,
    val token: String
)

@Serializable
data class PlayerDTO(
    val id: Int,
    val name: String,
    val age: Int?,
    val email: String
)

@Serializable
data class NewPlayerDTO(
    val name: String,
    val age: Int?,
    val email: String
)

@Serializable
data class SendNotificationRequest(
    val targetEmail: String,
    val title: String,
    val body: String
)