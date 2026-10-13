package com.example.trackmate.services

import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query
import kotlin.math.roundToInt

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

private val ARRIVE_TYPES = setOf(4, 5, 6)
private const val ROUNDABOUT_ENTER = 26
private const val ROUNDABOUT_EXIT = 27

const val VOICE_EARLY_SECONDS = 20.0
const val VOICE_NOW_SECONDS = 6.0
const val VOICE_NOW_MIN_METERS = 25.0
const val VOICE_AFTER_DELAY_METERS = 20.0
const val VOICE_AFTER_WINDOW_METERS = 200.0
const val VOICE_AFTER_MIN_METERS = 1000.0

fun voiceEarlyMinMeters(vehicle: String) = when (vehicle) {
    "car", "motorcycle" -> 300.0
    "foot" -> 60.0
    else -> 100.0
}

sealed class VoicePrompt {
    data class Depart(val index: Int) : VoicePrompt()
    data class Early(val index: Int, val meters: Int) : VoicePrompt()
    data class Now(val index: Int) : VoicePrompt()
    data class After(val index: Int) : VoicePrompt()
    object OffTrack : VoicePrompt()
}

class VoiceGuide(private val maneuvers: List<Maneuver>, vehicle: String) {
    private val earlyMinMeters = voiceEarlyMinMeters(vehicle)
    private val spoken = mutableSetOf<Pair<Int, Int>>()
    private val said = mutableSetOf<Int>()
    private var wasOffTrack = false

    fun update(along: Double, speedKmh: Float, offTrack: Boolean): VoicePrompt? {
        if (offTrack) {
            if (wasOffTrack) return null
            wasOffTrack = true
            return VoicePrompt.OffTrack
        }
        wasOffTrack = false
        val index = nextManeuverIndex(maneuvers, along)
        if (index < 0) return null
        val next = maneuvers[index]
        val meters = next.along - along
        val speed = speedKmh / 3.6
        val nowMeters = maxOf(VOICE_NOW_MIN_METERS, speed * VOICE_NOW_SECONDS)
        val earlyMeters = maxOf(earlyMinMeters, speed * VOICE_EARLY_SECONDS)
        val previous = maneuvers.getOrNull(index - 1)

        if (meters <= nowMeters) {
            if (!markSpoken(index, NOW)) return null
            markSpoken(index, EARLY)
            if (previous != null && index - 1 in said && coveredBy(previous, next, nowMeters)) return null
            said += index
            return VoicePrompt.Now(index)
        }
        if (previous != null && previous.isDeparture && markSpoken(index - 1, NOW)) {
            said += index - 1
            return VoicePrompt.Depart(index - 1)
        }
        if (meters <= earlyMeters && next.type !in ARRIVE_TYPES &&
            (previous == null || next.along - previous.along > 2 * nowMeters) && markSpoken(index, EARLY)
        ) {
            return VoicePrompt.Early(index, roundSpokenMeters(meters))
        }
        if (previous?.verbalPost != null && meters > maxOf(VOICE_AFTER_MIN_METERS, 2 * earlyMeters) &&
            along - previous.along in VOICE_AFTER_DELAY_METERS..VOICE_AFTER_WINDOW_METERS && markSpoken(index - 1, AFTER)
        ) {
            return VoicePrompt.After(index - 1)
        }
        return null
    }

    fun arrival(): VoicePrompt? {
        val index = maneuvers.indexOfLast { it.type in ARRIVE_TYPES }
        return if (index >= 0 && markSpoken(index, NOW)) VoicePrompt.Now(index) else null
    }

    // valhalla appends a close maneuver ("Then ..."), and a roundabout entry already names the exit
    private fun coveredBy(previous: Maneuver, next: Maneuver, nowMeters: Double): Boolean =
        previous.verbalPre?.endsWith(next.spokenText) == true ||
            (previous.type == ROUNDABOUT_ENTER && next.type == ROUNDABOUT_EXIT && next.along - previous.along <= nowMeters)

    private fun markSpoken(index: Int, stage: Int) = spoken.add(index to stage)

    private companion object {
        const val EARLY = 0
        const val NOW = 1
        const val AFTER = 2
    }
}

fun roundSpokenMeters(meters: Double): Int =
    if (meters < 100) ((meters / 10).roundToInt() * 10).coerceAtLeast(10) else (meters / 50).roundToInt() * 50
