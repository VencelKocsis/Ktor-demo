package hu.bme.aut.android.demo.model

import kotlinx.serialization.Serializable

@Serializable
data class MatchCreateDTO(
    val seasonId: Int,
    val roundNumber: Int,
    val homeTeamId: Int,
    val guestTeamId: Int,
    val matchDate: String, // "YYYY-MM-DDTHH:mm" formátumban jön a HTML-ből
    val location: String
)

@Serializable
data class TeamCreateDTO(
    val clubId: Int,
    val name: String,
    val division: String? = null,
    val captainUserId: Int
)

@Serializable
data class TeamUpdateDTO(
    val name: String,
    val division: String? = null
)

@Serializable
data class ClubCreateDTO(
    val name: String,
    val address: String
)

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
data class TeamMemberOperationDTO(
    val userId: Int
)

@Serializable
data class MemberDTO(
    val userId: Int,
    val firebaseUid: String,
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
    val homeTeamId: Int,
    val guestTeamId: Int,
    val individualMatches: List<IndividualMatchDTO>? = null,
    val participants: List<MatchParticipantDTO>? = null,
    val homeTeamSigned: Boolean = false,
    val guestTeamSigned: Boolean = false
)

@Serializable
data class ParticipantStatusUpdateDTO(
    val status: String
)

@Serializable
data class LineupSubmitDTO(
    val teamSide: String, // "HOME" vagy "GUEST"
    val positions: Map<Int, Int> // Map, ahol a Kulcs: pozíció(1-4), Érték: userId
)

@Serializable
data class MatchParticipantDTO(
    val id: Int,
    val userId: Int,
    val firebaseUid: String? = null,
    val playerName: String,
    val teamSide: String, // "HOME" vagy "GUEST"
    val status: String, // "APPLIED", "SELECTED" vagy "LOCKED"
    val position: Int? = null
)

@Serializable
data class IndividualMatchDTO(
    val id: Int,
    val homePlayerId: Int,
    val homePlayerName: String,
    val guestPlayerId: Int,
    val guestPlayerName: String,
    val homeScore: Int,
    val guestScore: Int,
    val setScores: String? = null,
    val status: String? = "pending",
    val orderNumber: Int = 0
)

@Serializable
data class ScoreSubmitDTO(
    val homeScore: Int,       // Szettek száma (pl. 3)
    val guestScore: Int,      // Szettek száma (pl. 1)
    val setScores: String,    // "11-8, 9-11, 12-10, 11-5"
    val status: String        // "in_progress" vagy "finished"
)

@Serializable
data class FcmTokenRegistration(
    val email: String,
    val token: String
)

@Serializable
data class SendNotificationRequest(
    val targetEmail: String,
    val title: String,
    val body: String
)