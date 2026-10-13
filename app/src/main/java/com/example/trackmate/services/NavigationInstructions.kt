package com.example.trackmate.services

import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

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
        @Query("vehicle") vehicle: String? = null,
        @Query("lang") lang: String = "en"
    ): Response<TrackInstructions>
}

private val DEPART_TYPES = setOf(1, 2, 3)

const val THEN_MIN_METERS = 100.0
const val THEN_SECONDS = 10.0

const val SPEED_LIMIT_TOLERANCE_KMH = 5

fun nextManeuverIndex(maneuvers: List<Maneuver>, along: Double): Int {
    val ahead = maneuvers.indexOfFirst { !it.isDeparture && it.along > along }
    return if (ahead >= 0) ahead else maneuvers.indexOfLast { !it.isDeparture }
}

fun showsThen(maneuvers: List<Maneuver>, index: Int, speedKmh: Float): Boolean {
    val next = maneuvers.getOrNull(index) ?: return false
    val then = maneuvers.getOrNull(index + 1) ?: return false
    return then.along - next.along < maxOf(THEN_MIN_METERS, THEN_SECONDS * speedKmh / 3.6)
}

fun speedLimitAt(limits: List<SpeedLimit>, along: Double): Int? =
    limits.firstOrNull { along >= it.from && along < it.to }?.kmh

fun isOverLimit(speedKmh: Float, limitKmh: Int?): Boolean =
    limitKmh != null && speedKmh > limitKmh + SPEED_LIMIT_TOLERANCE_KMH

sealed class BannerState {
    object Loading : BannerState()
    object Unavailable : BannerState()
    data class BackToTrack(val meters: Double) : BannerState()
    data class Next(val index: Int, val meters: Double, val thenIndex: Int?) : BannerState()
}

fun bannerState(
    loading: Boolean,
    instructions: TrackInstructions?,
    offTrack: Boolean,
    distanceToTrack: Double,
    along: Double,
    speedKmh: Float
): BannerState {
    if (offTrack) return BannerState.BackToTrack(distanceToTrack)
    if (loading) return BannerState.Loading
    val maneuvers = instructions?.maneuvers ?: return BannerState.Unavailable
    val index = nextManeuverIndex(maneuvers, along)
    if (index < 0) return BannerState.Unavailable
    val then = if (showsThen(maneuvers, index, speedKmh)) index + 1 else null
    return BannerState.Next(index, (maneuvers[index].along - along).coerceAtLeast(0.0), then)
}
