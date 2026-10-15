package com.example.trackmate.navigate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.hypot

class TravelStatsTest {
    // 0.00001 degrees is one meter, enough for the maths here
    private val flat: FixDistance = { a, b -> (hypot(b.lat - a.lat, b.lng - a.lng) * 100_000).toFloat() }

    // y meters north of a base point, at t seconds
    private fun fix(y: Double, t: Double, speedMps: Float? = null, accuracy: Float? = null) =
        Fix(45.0 + y / 100_000, 9.0, (t * 1000).toLong(), speedMps, accuracy)

    @Test
    fun noTravelBelowTwoFixes() {
        assertNull(travelStats(emptyList(), flat))
        assertNull(travelStats(listOf(fix(0.0, 0.0)), flat))
    }

    @Test
    fun distanceTimeAndSpeedsFromThePositions() {
        // 50 m every 10 s is 18 km/h
        val travel = travelStats(listOf(fix(0.0, 0.0), fix(50.0, 10.0), fix(100.0, 20.0), fix(150.0, 30.0)), flat)!!
        assertEquals(-1, travel.id)
        assertEquals(0.15f, travel.distance, 0.001f)
        assertEquals(30f, travel.time, 0f)
        assertEquals(18f, travel.averageSpeed, 0.01f)
        assertEquals(18f, travel.maxSpeed, 0.01f)
        assertNull(travel.vehicle)
    }

    @Test
    fun theFixSpeedWinsOverThePositions() {
        val travel = travelStats(listOf(fix(0.0, 0.0), fix(50.0, 10.0, speedMps = 10f)), flat)!!
        assertEquals(36f, travel.maxSpeed, 0.01f)
        assertEquals(18f, travel.averageSpeed, 0.01f)
    }

    @Test
    fun unreliableFixesDontSetTheMaxSpeed() {
        val inaccurate = travelStats(listOf(fix(0.0, 0.0), fix(500.0, 10.0, accuracy = 50f)), flat)!!
        assertEquals(0f, inaccurate.maxSpeed, 0f)
        assertEquals(0.5f, inaccurate.distance, 0.001f)

        // under a second apart
        val tooClose = travelStats(listOf(fix(0.0, 0.0), fix(20.0, 0.5)), flat)!!
        assertEquals(0f, tooClose.maxSpeed, 0f)

        val implausible = travelStats(listOf(fix(0.0, 0.0), fix(10.0, 10.0, speedMps = 200f)), flat)!!
        assertEquals(0f, implausible.maxSpeed, 0f)
    }

    @Test
    fun valuesAreRoundedToTwoDecimals() {
        val travel = travelStats(listOf(fix(0.0, 0.0), fix(1234.567, 123.4567)), flat)!!
        assertEquals(123.46f, travel.time, 0f)
        assertEquals(1.23f, travel.distance, 0f)
    }

    @Test
    fun speedAtEachFix() {
        assertEquals(0f, speedAtFixKmh(null, fix(0.0, 0.0), flat), 0f)
        assertEquals(18f, speedAtFixKmh(null, fix(0.0, 0.0, speedMps = 5f), flat), 0.001f)
        assertEquals(36f, speedAtFixKmh(fix(0.0, 0.0), fix(100.0, 10.0), flat), 0.01f)
        assertEquals(0f, speedAtFixKmh(fix(0.0, 0.0), fix(100.0, 10.0, accuracy = 30f), flat), 0f)
        assertEquals(0f, speedAtFixKmh(null, fix(0.0, 0.0, speedMps = 150f), flat), 0f)
    }

    @Test
    fun travelledMetersAddsTheSteps() {
        assertEquals(0f, travelledMeters(emptyList(), flat), 0f)
        assertEquals(300f, travelledMeters(listOf(fix(0.0, 0.0), fix(100.0, 10.0), fix(300.0, 20.0)), flat), 0.01f)
    }
}
