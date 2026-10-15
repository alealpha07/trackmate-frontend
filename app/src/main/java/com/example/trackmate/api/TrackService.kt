package com.example.trackmate.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*
import java.util.Date

@JsonClass(generateAdapter = true)
data class NewTrackRequest(
    val name: String,
    val vehicle: String
)

@JsonClass(generateAdapter = true)
data class TrackId(
    val id: Int
)

@JsonClass(generateAdapter = true)
data class EditTrackRequest(
    val id: Int,
    val name: String,
    val vehicle: String
)

@JsonClass(generateAdapter = true)
data class NewTravelRequest(
    val id: Int,
    val time: Float,
    val averageSpeed: Float,
    val maxSpeed: Float,
    val distance: Float,
    // null uses the track's vehicle
    val vehicle: String? = null
)

@JsonClass(generateAdapter = true)
data class TravelId(
    val id: Int
)

@JsonClass(generateAdapter = true)
data class TrackItem(
    val id: Int,
    val name: String,
    val vehicle: String,
    val bestTime: Float?,
    val maxSpeed: Float?,
    val bestAverageSpeed: Float?,
    val travelCount: Int
)

@JsonClass(generateAdapter = true)
data class TravelItem(
    val id: Int,
    val userId: Int,
    val trackId: Int?,
    val name: String,
    val time: Float,
    val dateTimeString: String,
    val maxSpeed: Float,
    val averageSpeed: Float,
    val distance: Float,
    val vehicle: String,
    // Only sent by track/travel/details
    val username: String? = null
)

@JsonClass(generateAdapter = true)
data class LeaderboardItem(
    val userId: Int,
    val name: String,
    val time: Float
)

@JsonClass(generateAdapter = true)
data class TravelStatistics(
    val time: Float,
    val averageSpeed: Float,
    val maxSpeed: Float,
    val username: String,
    val distance: Float,
    val vehicle: String
)

@JsonClass(generateAdapter = true)
data class TrackDetails(
    val id: Int,
    val name: String,
    val vehicle: String,
    val ownerId: Int? = null,
    val travelCount: Int,
    val userBest: TravelStatistics?,
    val overallBest: TravelStatistics?
)

interface TrackService {
    @Multipart
    @POST("track/file")
    suspend fun uploadTrack(
        @Part file: MultipartBody.Part,
        @Query("id") id: Int,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @GET("track/file")
    @Streaming
    suspend fun getTrackFile(
        @Query("id") id: Int,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @POST("track")
    suspend fun createTrack(
        @Body data: NewTrackRequest,
        @Query("lang") lang: String = "en"
    ): Response<TrackId>

    @PUT("track")
    suspend fun editTrack(
        @Body data: EditTrackRequest,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @DELETE("track")
    suspend fun deleteTrack(
        @Query("id") id: Int,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @GET("track")
    suspend fun getTracks(
        @Query("vehicle") vehicle: String? = null,
        @Query("lang") lang: String = "en"
    ): Response<List<TrackItem>>

    @GET("track/details")
    suspend fun getTrack(
        @Query("id") id: Int,
        @Query("vehicle") vehicle: String? = null,
        @Query("lang") lang: String = "en"
    ): Response<TrackDetails>

    @POST("track/travel")
    suspend fun createTravel(
        @Body data: NewTravelRequest,
        @Query("lang") lang: String = "en"
    ): Response<TravelId>

    @Multipart
    @POST("track/travel/file")
    suspend fun uploadTravelFile(
        @Part file: MultipartBody.Part,
        @Query("id") id: Int,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @GET("track/travel/file")
    @Streaming
    suspend fun getTravelFile(
        @Query("id") id: Int,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @DELETE("track/travel")
    suspend fun deleteTravel(
        @Query("id") id: Int,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @GET("track/travel")
    suspend fun getTravels(
        @Query("lang") lang: String = "en"
    ): Response<List<TravelItem>>

    @GET("track/travel/details")
    suspend fun getTravelsByTrack(
        @Query("id") id: Int,
        @Query("vehicle") vehicle: String? = null,
        @Query("lang") lang: String = "en"
    ): Response<List<TravelItem>>

    @GET("track/leaderboard")
    suspend fun getLeaderboard(
        @Query("id") id: Int,
        @Query("vehicle") vehicle: String? = null,
        @Query("lang") lang: String = "en"
    ): Response<List<LeaderboardItem>>
}

@JsonClass(generateAdapter = true)
data class TrackPoint(
    @Json(name = "lat") val latitude: Double,
    @Json(name = "lng") val longitude: Double,
    val timestamp: Long,
    val speed: Float = 0f // km/h
)

@JsonClass(generateAdapter = true)
data class Track(
    val track: List<TrackPoint>
)
