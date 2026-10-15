package com.example.trackmate.navigate

import com.example.trackmate.api.NewTravelRequest
import com.example.trackmate.track.MAX_ACCURACY_METERS
import com.example.trackmate.track.MAX_PLAUSIBLE_SPEED_MPS
import com.example.trackmate.track.MIN_RELIABLE_DT_SECONDS
import java.util.Locale

// a GPS fix without android.location, so the maths runs in unit tests
data class Fix(
    val lat: Double,
    val lng: Double,
    val time: Long,
    val speedMps: Float? = null,
    val accuracy: Float? = null
)

// meters, the service passes Location.distanceBetween
typealias FixDistance = (Fix, Fix) -> Float

private fun accurateEnough(fix: Fix) = fix.accuracy == null || fix.accuracy <= MAX_ACCURACY_METERS

// the fix's own speed, else from the previous fix when both are accurate and far enough apart in time
private fun speedMps(prev: Fix?, curr: Fix, distance: FixDistance): Float? {
    if (curr.speedMps != null) return curr.speedMps
    if (prev == null) return null
    val dt = (curr.time - prev.time) / 1000f
    return if (accurateEnough(prev) && accurateEnough(curr) && dt >= MIN_RELIABLE_DT_SECONDS) distance(prev, curr) / dt else null
}

fun speedAtFixKmh(prev: Fix?, curr: Fix, distance: FixDistance): Float {
    val speed = speedMps(prev, curr, distance)
    return if (speed != null && speed <= MAX_PLAUSIBLE_SPEED_MPS) speed * 3.6f else 0f
}

fun travelledMeters(fixes: List<Fix>, distance: FixDistance): Float = fixes.zipWithNext(distance).sum()

private fun twoDecimals(value: Float) = "%.2f".format(Locale.US, value).toFloat()

// the travel sent after a recording, id is set by the caller
fun travelStats(fixes: List<Fix>, distance: FixDistance): NewTravelRequest? {
    if (fixes.size < 2) return null
    val meters = travelledMeters(fixes, distance)
    val maxSpeedMps = fixes.zipWithNext { prev, curr -> speedMps(prev, curr, distance) }
        .filterNotNull()
        .filter { it <= MAX_PLAUSIBLE_SPEED_MPS }
        .maxOrNull()?.coerceAtLeast(0f) ?: 0f
    val seconds = (fixes.last().time - fixes.first().time) / 1000f
    val averageKmh = if (seconds > 0) meters / seconds * 3.6f else 0f
    return NewTravelRequest(
        id = -1,
        time = twoDecimals(seconds),
        averageSpeed = twoDecimals(averageKmh),
        maxSpeed = twoDecimals(maxSpeedMps * 3.6f),
        distance = twoDecimals(meters / 1000f)
    )
}
