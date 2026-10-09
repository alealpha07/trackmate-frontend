package com.example.trackmate.services

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6371008.8
private const val METERS_PER_DEGREE = EARTH_RADIUS_METERS * Math.PI / 180

// A fix is first matched only near where the rider was; the window ahead covers a few seconds without GPS at
// motorway speed
const val SEARCH_BEHIND_METERS = 50.0
const val SEARCH_AHEAD_METERS = 500.0

// Each meter jumped along the track counts like this much distance from it, so on loops and out-and-back tracks the
// rider stays matched to the stretch they are on, not to the same road travelled the other way. Going back costs
// more: at the turn of an out-and-back the two directions are equally close, and the rider is going on.
const val FORWARD_JUMP_COST = 0.1
const val BACKWARD_JUMP_COST = 0.5

// After this many fixes off the track the whole track is searched, so rejoining it elsewhere is found
const val REJOIN_AFTER_FIXES = 5

/** Follows a rider along a track: how far along it they are and how far from it. */
class TrackProgress(
    points: List<Point>,
    private val offTrackMeters: Double = OFF_TRACK_THRESHOLD.toDouble()
) {
    data class Point(val latitude: Double, val longitude: Double)

    private class Match(val segment: Int, val along: Double, val distance: Double, val point: Point)

    // A single point becomes a zero-length segment, so there is always one
    private val points = if (points.size == 1) points + points else points
    private val cumulative = DoubleArray(this.points.size)

    val length: Double

    var along = 0.0
        private set
    var distanceToTrack = 0.0
        private set
    var segment = 0
        private set
    var projected: Point = this.points.first()
        private set
    private var offTrackFixes = 0

    val remaining get() = length - along
    val offTrack get() = distanceToTrack > offTrackMeters

    init {
        require(points.isNotEmpty()) { "A track needs at least one point" }
        for (i in 1 until this.points.size) {
            cumulative[i] = cumulative[i - 1] + haversine(this.points[i - 1], this.points[i])
        }
        length = cumulative.last()
    }

    fun update(latitude: Double, longitude: Double) {
        val position = Point(latitude, longitude)
        var best = bestMatch(position, windowStart(), windowEnd())
        if (best.distance > offTrackMeters) {
            offTrackFixes++
            if (offTrackFixes >= REJOIN_AFTER_FIXES) {
                val global = bestMatch(position, 0, points.size - 2)
                if (global.distance <= offTrackMeters) best = global
            }
        }

        distanceToTrack = best.distance
        if (best.distance <= offTrackMeters) {
            offTrackFixes = 0
            segment = best.segment
            along = best.along
            projected = best.point
        }
    }

    private fun windowStart(): Int {
        var i = segment
        while (i > 0 && cumulative[i] > along - SEARCH_BEHIND_METERS) i--
        return i
    }

    private fun windowEnd(): Int {
        var i = segment
        while (i < points.size - 2 && cumulative[i + 1] < along + SEARCH_AHEAD_METERS) i++
        return i
    }

    // Segments within the off-track distance always win over farther ones, whatever the jump
    private fun bestMatch(position: Point, from: Int, to: Int): Match {
        var best: Match? = null
        var bestScore = Double.MAX_VALUE
        for (i in from..to) {
            val match = project(position, i)
            val jump = match.along - along
            val jumpCost = if (jump >= 0) jump * FORWARD_JUMP_COST else -jump * BACKWARD_JUMP_COST
            val offTrackCost = if (match.distance > offTrackMeters) EARTH_RADIUS_METERS else 0.0
            val score = match.distance + jumpCost + offTrackCost
            if (score < bestScore) {
                best = match
                bestScore = score
            }
        }
        return best!!
    }

    // Flat coordinates in meters around the position are accurate enough over one segment
    private fun project(position: Point, i: Int): Match {
        val a = points[i]
        val b = points[i + 1]
        val metersPerLng = METERS_PER_DEGREE * cos(Math.toRadians(position.latitude))
        val ax = (a.longitude - position.longitude) * metersPerLng
        val ay = (a.latitude - position.latitude) * METERS_PER_DEGREE
        val dx = (b.longitude - a.longitude) * metersPerLng
        val dy = (b.latitude - a.latitude) * METERS_PER_DEGREE
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0.0) 0.0 else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return Match(
            segment = i,
            along = cumulative[i] + t * (cumulative[i + 1] - cumulative[i]),
            distance = hypot(ax + t * dx, ay + t * dy),
            point = Point(
                a.latitude + t * (b.latitude - a.latitude),
                a.longitude + t * (b.longitude - a.longitude)
            )
        )
    }

    private fun haversine(a: Point, b: Point): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h))
    }
}
