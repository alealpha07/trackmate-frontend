package com.example.trackmate.track

import com.example.trackmate.api.Maneuver
import com.example.trackmate.api.SpeedLimit
import com.example.trackmate.api.TrackInstructions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationInstructionsTest {
    private fun maneuver(type: Int, along: Double, length: Double = 0.0, time: Double = 0.0) =
        Maneuver(type, "", emptyList(), along, length, time)

    private val maneuvers = listOf(
        maneuver(1, 0.0, 500.0, 50.0),
        maneuver(10, 500.0, 60.0, 6.0),
        maneuver(15, 560.0, 440.0, 44.0),
        maneuver(4, 1000.0)
    )
    private val instructions = TrackInstructions(
        "car", "en-US", maneuvers,
        listOf(SpeedLimit(100.0, 400.0, 30), SpeedLimit(400.0, 700.0, 50), SpeedLimit(800.0, 900.0, 30))
    )

    @Test
    fun nextSkipsTheDeparture() {
        assertEquals(1, nextManeuverIndex(maneuvers, 0.0))
        assertEquals(1, nextManeuverIndex(maneuvers, 499.0))
        assertEquals(2, nextManeuverIndex(maneuvers, 500.0))
        assertEquals(3, nextManeuverIndex(maneuvers, 700.0))
    }

    @Test
    fun arrivalStaysOnceEverythingIsBehind() {
        assertEquals(3, nextManeuverIndex(maneuvers, 1005.0))
        assertEquals(-1, nextManeuverIndex(emptyList(), 0.0))
        assertEquals(-1, nextManeuverIndex(listOf(maneuver(1, 0.0, 10.0, 1.0)), 0.0))
    }

    @Test
    fun thenShowsForCloseManeuvers() {
        assertTrue(showsThen(maneuvers, 1, 0f))
        assertFalse(showsThen(maneuvers, 2, 130f))
        assertTrue(showsThen(maneuvers, 2, 160f))
        assertFalse(showsThen(maneuvers, 3, 50f))
    }

    @Test
    fun speedLimitByAlong() {
        val limits = instructions.speedLimits
        assertNull(speedLimitAt(limits, 50.0))
        assertEquals(30, speedLimitAt(limits, 100.0))
        assertEquals(30, speedLimitAt(limits, 399.9))
        assertEquals(50, speedLimitAt(limits, 400.0))
        assertNull(speedLimitAt(limits, 750.0))
        assertEquals(30, speedLimitAt(limits, 850.0))
        assertNull(speedLimitAt(limits, 900.0))
        assertNull(speedLimitAt(emptyList(), 100.0))
    }

    @Test
    fun overTheLimitPastTheTolerance() {
        assertFalse(isOverLimit(55f, 50))
        assertTrue(isOverLimit(55.1f, 50))
        assertFalse(isOverLimit(200f, null))
    }

    @Test
    fun bannerStates() {
        assertEquals(BannerState.Loading, bannerState(true, null, false, 0.0, 0.0, 0f))
        assertEquals(BannerState.Unavailable, bannerState(false, null, false, 0.0, 0.0, 0f))
        assertEquals(BannerState.Unavailable, bannerState(false, instructions.copy(maneuvers = emptyList()), false, 0.0, 0.0, 0f))
        assertEquals(BannerState.BackToTrack(72.0), bannerState(true, null, true, 72.0, 0.0, 0f))
        assertEquals(BannerState.BackToTrack(72.0), bannerState(false, null, true, 72.0, 0.0, 0f))
        assertEquals(BannerState.BackToTrack(72.0), bannerState(false, instructions, true, 72.0, 300.0, 0f))
        assertEquals(BannerState.Next(1, 200.0, 2), bannerState(false, instructions, false, 0.0, 300.0, 20f))
        assertEquals(BannerState.Next(2, 40.0, null), bannerState(false, instructions, false, 0.0, 520.0, 20f))
        assertEquals(BannerState.Next(3, 0.0, null), bannerState(false, instructions, false, 0.0, 1010.0, 20f))
    }
}
