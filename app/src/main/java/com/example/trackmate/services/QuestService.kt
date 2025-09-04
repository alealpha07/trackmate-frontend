package com.example.trackmate.services

import com.squareup.moshi.JsonClass
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

@JsonClass(generateAdapter = true)
data class QuestResponse(
    val id: Int,
    val userId: Int,
    val description: String,
    val experience: Int,
    val maxProgress: Int,
    val progress: Int,
    val type: String,
)

enum class QuestType {
    TRAVEL_DISTANCE, RECORD_TRACK, NAVIGATE_TRACK
}

@JsonClass(generateAdapter = true)
data class IncreaseQuestRequest(
    val type: String,
    val progress: Int
)

@JsonClass(generateAdapter = true)
data class CollectQuestRequest(
    val id: Int
)

interface QuestService {

    @PUT("quest")
    suspend fun collectQuest(
        @Body data: CollectQuestRequest,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @POST("quest")
    suspend fun increaseQuest(
        @Body data: IncreaseQuestRequest,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @GET("quest")
    suspend fun getQuests(
        @Query("lang") lang: String = "en"
    ): Response<List<QuestResponse>>
}
