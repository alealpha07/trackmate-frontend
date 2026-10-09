package com.example.trackmate.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class TrackProgressTest {
    private val baseLat = 45.0
    private val baseLng = 9.0
    private val metersPerDegree = 6371008.8 * Math.PI / 180

    // Positions in meters east (x) and north (y) of a fixed base point
    private fun at(x: Double, y: Double) = TrackProgress.Point(
        baseLat + y / metersPerDegree,
        baseLng + x / (metersPerDegree * cos(Math.toRadians(baseLat)))
    )

    // A track through the corners, with a point every `step` meters like a planned track
    private fun track(vararg corners: Pair<Double, Double>, step: Double = 50.0): TrackProgress {
        val points = mutableListOf(at(corners[0].first, corners[0].second))
        corners.toList().zipWithNext { (x1, y1), (x2, y2) ->
            val n = maxOf(1, (Math.hypot(x2 - x1, y2 - y1) / step).toInt())
            for (i in 1..n) points.add(at(x1 + (x2 - x1) * i / n, y1 + (y2 - y1) * i / n))
        }
        return TrackProgress(points)
    }

    private fun TrackProgress.ride(x: Double, y: Double) {
        val p = at(x, y)
        update(p.latitude, p.longitude)
    }

    // Rides from one point to another with a fix every 10 m
    private fun TrackProgress.rideLine(x1: Double, y1: Double, x2: Double, y2: Double) {
        val n = maxOf(1, (Math.hypot(x2 - x1, y2 - y1) / 10).toInt())
        for (i in 0..n) ride(x1 + (x2 - x1) * i / n, y1 + (y2 - y1) * i / n)
    }

    @Test
    fun straightTrack() {
        val progress = track(0.0 to 0.0, 1000.0 to 0.0)
        assertEquals(1000.0, progress.length, 0.5)

        progress.rideLine(0.0, 5.0, 400.0, 5.0)
        assertEquals(400.0, progress.along, 1.0)
        assertEquals(600.0, progress.remaining, 1.0)
        assertEquals(5.0, progress.distanceToTrack, 0.5)
        assertFalse(progress.offTrack)
    }

    @Test
    fun besideTheMiddleOfALongSegment() {
        val progress = track(0.0 to 0.0, 2000.0 to 0.0, step = 5000.0)
        progress.ride(0.0, 0.0)
        progress.ride(300.0, 20.0)
        assertEquals(0, progress.segment)
        assertEquals(300.0, progress.along, 1.0)
        assertEquals(20.0, progress.distanceToTrack, 0.5)
        assertFalse(progress.offTrack)
        assertEquals(at(300.0, 0.0).latitude, progress.projected.latitude, 1e-6)
        assertEquals(at(300.0, 0.0).longitude, progress.projected.longitude, 1e-6)
    }

    @Test
    fun loopStartsAtTheStartAndEndsAtTheEnd() {
        val progress = track(0.0 to 0.0, 500.0 to 0.0, 500.0 to 500.0, 0.0 to 500.0, 0.0 to 0.0)
        progress.ride(0.0, 0.0)
        assertEquals(0.0, progress.along, 1.0)

        var previous = 0.0
        val corners = listOf(0.0 to 0.0, 500.0 to 0.0, 500.0 to 500.0, 0.0 to 500.0, 0.0 to 0.0)
        corners.zipWithNext { (x1, y1), (x2, y2) ->
            progress.rideLine(x1, y1, x2, y2)
            assertTrue(progress.along >= previous)
            previous = progress.along
        }
        assertEquals(2000.0, progress.along, 1.0)
        assertEquals(0.0, progress.remaining, 1.0)
    }

    @Test
    fun outAndBackKeepsTheDirection() {
        val progress = track(0.0 to 0.0, 1000.0 to 0.0, 0.0 to 0.0)
        // One lane out, the other lane back
        progress.rideLine(0.0, 3.0, 500.0, 3.0)
        assertEquals(500.0, progress.along, 1.0)
        progress.rideLine(500.0, 3.0, 1000.0, 3.0)
        progress.rideLine(1000.0, -3.0, 500.0, -3.0)
        assertEquals(1500.0, progress.along, 1.0)
        progress.rideLine(500.0, -3.0, 0.0, -3.0)
        assertEquals(2000.0, progress.along, 1.0)
    }

    @Test
    fun oneStrayFixDoesNotJump() {
        val progress = track(0.0 to 0.0, 2000.0 to 0.0)
        progress.rideLine(0.0, 0.0, 1500.0, 0.0)
        progress.ride(300.0, 0.0)
        assertTrue(progress.offTrack)
        assertEquals(1500.0, progress.along, 1.0)
        progress.ride(1510.0, 0.0)
        assertFalse(progress.offTrack)
        assertEquals(1510.0, progress.along, 1.0)
    }

    @Test
    fun jumpsBackAfterASustainedOffTrack() {
        val progress = track(0.0 to 0.0, 2000.0 to 0.0)
        progress.rideLine(0.0, 0.0, 1500.0, 0.0)
        progress.rideLine(1500.0, 200.0, 300.0, 200.0)
        assertTrue(progress.offTrack)
        assertEquals(1500.0, progress.along, 1.0)

        progress.rideLine(300.0, 0.0, 350.0, 0.0)
        assertFalse(progress.offTrack)
        assertEquals(350.0, progress.along, 1.0)
    }

    @Test
    fun singlePoint() {
        val progress = TrackProgress(listOf(at(0.0, 0.0)))
        progress.ride(10.0, 0.0)
        assertEquals(0.0, progress.length, 0.0)
        assertEquals(10.0, progress.distanceToTrack, 0.5)
    }
}
