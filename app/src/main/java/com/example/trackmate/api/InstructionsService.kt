package com.example.trackmate.api

import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

private val DEPART_TYPES = setOf(1, 2, 3)

@JsonClass(generateAdapter = true)
data class Maneuver(
    val type: Int,
    val text: String,
    val streets: List<String>,
    val along: Double,
    val length: Double,
    val time: Double,
    val verbalPre: String? = null,
    val verbalPost: String? = null
) {
    val isDeparture get() = type in DEPART_TYPES
    val spokenText get() = verbalPre ?: text
}

@JsonClass(generateAdapter = true)
data class SpeedLimit(
    val from: Double,
    val to: Double,
    val kmh: Int
)

@JsonClass(generateAdapter = true)
data class TrackInstructions(
    val vehicle: String,
    val language: String,
    val maneuvers: List<Maneuver>,
    val speedLimits: List<SpeedLimit> = emptyList()
)

interface InstructionsService {
    @Headers("Cache-Control: no-store")
    @GET("track/instructions")
    suspend fun getInstructions(
        @Query("id") id: Int,
        @Query("vehicle") vehicle: String? = null
    ): Response<TrackInstructions>
}
