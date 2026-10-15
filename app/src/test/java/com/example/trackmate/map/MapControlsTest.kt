package com.example.trackmate.map

import org.junit.Assert.assertEquals
import org.junit.Test
import org.osmdroid.util.GeoPoint

class MapControlsTest {
    @Test
    fun headingTurnsTheShortWay() {
        assertEquals(0f, interpolateHeading(350f, 10f, 0.5f), 0.001f)
        assertEquals(0f, interpolateHeading(10f, 350f, 0.5f), 0.001f)
        assertEquals(45f, interpolateHeading(0f, 90f, 0.5f), 0.001f)
        assertEquals(315f, interpolateHeading(0f, 270f, 0.5f), 0.001f)
        assertEquals(350f, interpolateHeading(350f, 10f, 0f), 0.001f)
        assertEquals(10f, interpolateHeading(350f, 10f, 1f), 0.001f)
    }

    @Test
    fun offsetAlongABearing() {
        val start = GeoPoint(45.0, 9.0)
        // 1 km is 1/6371 of a radian
        val degrees = Math.toDegrees(1000.0 / 6_371_000.0)
        val north = computeOffset(start, 0.0, 1000.0)
        assertEquals(45.0 + degrees, north.latitude, 1e-9)
        assertEquals(9.0, north.longitude, 1e-9)
        val east = computeOffset(start, 90.0, 1000.0)
        assertEquals(45.0, east.latitude, 1e-4)
        assertEquals(9.0 + degrees / Math.cos(Math.toRadians(45.0)), east.longitude, 1e-5)
    }

    @Test
    fun followZoomGoesOutWithSpeed() {
        assertEquals(TRACKING_ZOOM_LEVEL, followZoomFor(0f), 0.0)
        assertEquals(TRACKING_ZOOM_LEVEL, followZoomFor(FOLLOW_ZOOM_SLOW_KMH), 0.0)
        assertEquals((TRACKING_ZOOM_LEVEL + FAST_TRACKING_ZOOM_LEVEL) / 2, followZoomFor(65f), 1e-6)
        assertEquals(FAST_TRACKING_ZOOM_LEVEL, followZoomFor(FOLLOW_ZOOM_FAST_KMH), 0.0)
        assertEquals(FAST_TRACKING_ZOOM_LEVEL, followZoomFor(200f), 0.0)
    }

    @Test
    fun lookAheadDoublesPerZoomLevelOut() {
        assertEquals(FOLLOW_LOOK_AHEAD_METERS, followLookAhead(TRACKING_ZOOM_LEVEL), 1e-9)
        assertEquals(FOLLOW_LOOK_AHEAD_METERS * 2, followLookAhead(TRACKING_ZOOM_LEVEL - 1), 1e-9)
    }
}
