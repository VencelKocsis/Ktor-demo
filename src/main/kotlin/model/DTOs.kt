package hu.bme.aut.android.demo.model

import kotlinx.serialization.Serializable

// --- SZEZONOK ---
@Serializable
data class SeasonDTO(
    val id: Int,
    val name: String,
    val startDate: String,
    val endDate: String,
    val isActive: Boolean
)

// --- KLUBOK ---
@Serializable
data class ClubDTO(
    val id: Int,
    val name: String,
    val address: String
)

// --- FELHASZNÁLÓK ---
@Serializable
data class UserDTO(
    val id: Int,
    val email: String,
    val firstName: String,
    val lastName: String
    // Jelszót soha nem küldünk ki DTO-ban!
)

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
    val status: String,
    val location: String,
    val individualMatches: List<IndividualMatchDTO>? = null,
    val participants: List<MatchParticipantDTO>? = null
)

@Serializable
data class IndividualMatchDTO(
    val id: Int,
    val homePlayerName: String, // TODO user id alapján nevet lekérése
    val guestPlayerName: String, // TODO user id alapján nevet lekérése
    val homeScore: Int,
    val guestScore: Int
)

@Serializable
data class MatchParticipantDTO(
    val id: Int,
    val playerName: String, // TODO user id alapján nevet lekérése
    val teamSide: String, // "HOME" vagy "GUEST"
    val status: String    // "APPLIED" vagy "SELECTED"
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