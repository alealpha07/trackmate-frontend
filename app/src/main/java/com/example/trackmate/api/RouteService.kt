package com.example.trackmate.api

import com.squareup.moshi.JsonClass
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.*

@JsonClass(generateAdapter = true)
data class LatLng(
    val lat: Double,
    val lng: Double
)

@JsonClass(generateAdapter = true)
data class PlanRequest(
    val start: LatLng,
    val via: List<LatLng>,
    val end: LatLng,
    val vehicle: String,
    val policy: String,
    val filters: Map<String, Boolean>,
    // percent, null for no limit
    val maxDetour: Int? = null
)

// meters and seconds
@JsonClass(generateAdapter = true)
data class RouteLeg(
    val distance: Double,
    val duration: Double,
    val offBikeways: Double? = null,
    val highStress: Double? = null,
    val unpaved: Double? = null,
    val from: LatLng,
    val to: LatLng
)

@JsonClass(generateAdapter = true)
data class PlannedRoute(
    val track: List<TrackPoint>,
    val distance: Double,
    val duration: Double,
    val offBikeways: Double? = null,
    val highStress: Double? = null,
    val unpaved: Double? = null,
    val legs: List<RouteLeg>
)

@JsonClass(generateAdapter = true)
data class PlanProgress(
    val done: Int,
    val total: Int
)

@JsonClass(generateAdapter = true)
data class PlanMessage(
    val progress: PlanProgress? = null,
    val route: PlannedRoute? = null,
    val error: String? = null,
    val status: Int? = null
)

@JsonClass(generateAdapter = true)
data class PlannedTrackFile(
    val track: List<TrackPoint>,
    val distance: Double,
    val duration: Double
)

@JsonClass(generateAdapter = true)
data class GeocodeResult(
    val label: String,
    val lat: Double,
    val lng: Double
)

@JsonClass(generateAdapter = true)
data class VehicleInfo(
    val id: String,
    val plannable: Boolean
)

interface RouteService {
    // a Call so cancelling stops the stream and the server download
    @Streaming
    @POST("route/plan")
    fun plan(
        @Body data: PlanRequest,
        @Query("stream") stream: String = "1"
    ): Call<ResponseBody>

    @GET("route/geocode")
    suspend fun geocode(
        @Query("text") text: String,
        @Query("lat") lat: String,
        @Query("lng") lng: String,
        @Query("full") full: String? = null
    ): Response<List<GeocodeResult>>

    @GET("vehicle")
    suspend fun vehicles(): Response<List<VehicleInfo>>
}
