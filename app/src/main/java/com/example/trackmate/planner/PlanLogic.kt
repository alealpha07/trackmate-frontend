package com.example.trackmate.planner

import com.example.trackmate.api.PlanMessage
import com.example.trackmate.api.PlanProgress
import com.example.trackmate.api.PlannedRoute
import com.squareup.moshi.JsonAdapter
import okio.BufferedSource
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// same limit as the server
const val MAX_LEG_METERS = 50_000.0
private const val EARTH_RADIUS_METERS = 6_371_008.8

data class PlanPoint(val lat: Double, val lng: Double, val label: String? = null)

class PlanError(message: String) : Exception(message)

sealed interface RouteProblem {
    data object EmptyStop : RouteProblem
    data class LegTooFar(val leg: Int, val meters: Double) : RouteProblem
}

// null when the points can be planned, or start or destination are still empty
fun routeProblem(points: List<PlanPoint?>): RouteProblem? {
    if (points.first() == null || points.last() == null) return null
    if (points.any { it == null }) return RouteProblem.EmptyStop
    for (i in 0 until points.size - 1) {
        val meters = haversineMeters(points[i]!!, points[i + 1]!!)
        if (meters > MAX_LEG_METERS) return RouteProblem.LegTooFar(i, meters)
    }
    return null
}

fun pinLabel(index: Int, count: Int): String = when (index) {
    0 -> "A"
    count - 1 -> "B"
    else -> index.toString()
}

fun formatCoordinates(point: PlanPoint) = String.format(Locale.US, "%.5f, %.5f", point.lat, point.lng)

fun pointText(point: PlanPoint?): String = point?.let { it.label ?: formatCoordinates(it) } ?: ""

// accepts "44.8, 10.3", "44.8 10.3" and "44,8; 10,3"
fun parseCoordinates(text: String): PlanPoint? {
    val match = Regex("""^(-?\d+(?:\.\d+)?)\s*[,;\s]\s*(-?\d+(?:\.\d+)?)$""").find(text)
        ?: Regex("""^(-?\d+(?:,\d+)?)\s*[;\s]\s*(-?\d+(?:,\d+)?)$""").find(text)
        ?: return null
    val lat = match.groupValues[1].replace(",", ".").toDouble()
    val lng = match.groupValues[2].replace(",", ".").toDouble()
    if (abs(lat) > 90 || abs(lng) > 180) return null
    return PlanPoint(lat, lng)
}

fun haversineMeters(a: PlanPoint, b: PlanPoint): Double {
    val rad = { deg: Double -> Math.toRadians(deg) }
    val h = sin(rad(b.lat - a.lat) / 2).pow(2) +
        cos(rad(a.lat)) * cos(rad(b.lat)) * sin(rad(b.lng - a.lng) / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(h))
}

// like the web's Intl.NumberFormat
fun formatNumber(value: Double, minDecimals: Int): String =
    DecimalFormat(if (minDecimals == 1) "#,##0.0" else "#,##0.#", DecimalFormatSymbols.getInstance())
        .apply { roundingMode = RoundingMode.HALF_UP }
        .format(value)

fun formatDistance(meters: Double) = "${formatNumber(meters / 1000, 1)} km"

fun formatDuration(seconds: Double): String {
    val minutes = max(1, (seconds / 60).roundToInt())
    if (minutes < 60) return "$minutes min"
    return "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min"
}

// the NDJSON of POST /route/plan?stream=1: progress lines, then the route or an error
suspend fun readPlanMessages(
    source: BufferedSource,
    adapter: JsonAdapter<PlanMessage>,
    onProgress: suspend (PlanProgress) -> Unit
): PlannedRoute {
    var route: PlannedRoute? = null
    while (true) {
        val line = source.readUtf8Line() ?: break
        if (line.isBlank()) continue
        val message = adapter.fromJson(line) ?: continue
        message.error?.let { throw PlanError(it) }
        message.route?.let { route = it }
        message.progress?.let { if (it.total > 0) onProgress(it) }
    }
    return route ?: throw PlanError("")
}
