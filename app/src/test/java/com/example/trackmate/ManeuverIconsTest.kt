package com.example.trackmate

import com.example.trackmate.services.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Test

class ManeuverIconsTest {
    private fun maneuver(type: Int, text: String, vararg streets: String) =
        Maneuver(type, text, streets.toList(), 0.0, 0.0, 0.0)

    @Test
    fun streetForTurns() {
        assertEquals("Via dei Farnese", maneuver(15, "Turn left onto Via dei Farnese.", "Via dei Farnese").bannerText())
        assertEquals("SS9 / Via Emilia", maneuver(10, "Turn right onto SS9/Via Emilia.", "SS9", "Via Emilia").bannerText())
    }

    @Test
    fun sentenceWithoutStreetsAndForRoundaboutsAndArrival() {
        assertEquals("Turn right.", maneuver(10, "Turn right.").bannerText())
        val roundabout = "Enter the roundabout and take the 1st exit onto Viale Mariotti."
        assertEquals(roundabout, maneuver(26, roundabout, "Viale Mariotti").bannerText())
        assertEquals("You have arrived at your destination.", maneuver(4, "You have arrived at your destination.", "Via Kennedy").bannerText())
    }
}
