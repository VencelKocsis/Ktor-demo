package hu.bme.aut.android.demo.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class WsEvent {
    @Serializable
    @SerialName("IndividualScoreUpdated")
    data class IndividualScoreUpdated(
        val individualMatchId: Int,
        val homeScore: Int,
        val guestScore: Int,
        val setScores: String,
        val status: String
    ) : WsEvent()

    @Serializable
    @SerialName("MatchSignatureUpdated")
    data class MatchSignatureUpdated(
        val matchId: Int,
        val homeSigned: Boolean,
        val guestSigned: Boolean,
        val status: String
    ) : WsEvent()
}