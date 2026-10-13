package com.example.trackmate.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceGuideTest {
    private fun maneuver(type: Int, along: Double, pre: String, post: String? = null) =
        Maneuver(type, pre, emptyList(), along, 0.0, 0.0, pre, post)

    private val maneuvers = listOf(
        maneuver(1, 0.0, "Bike east.", "Continue for 400 meters."),
        maneuver(10, 416.0, "Turn right. Then Turn left onto Via A.", "Continue for 20 meters."),
        maneuver(15, 439.0, "Turn left onto Via A.", "Continue for 2 kilometers."),
        maneuver(26, 2600.0, "Enter the roundabout and take the 1st exit onto Via B."),
        maneuver(27, 2605.0, "Exit the roundabout onto Via B.", "Continue for 300 meters."),
        maneuver(4, 2900.0, "You have arrived at your destination.")
    )

    private fun ride(guide: VoiceGuide, speedKmh: Float, path: Iterable<Int>) =
        path.mapNotNull { guide.update(it.toDouble(), speedKmh, false) }

    @Test
    fun bicycleRideSpeaksEachStageOnce() {
        val prompts = ride(VoiceGuide(maneuvers, "bicycle"), 18f, 0..3000 step 2)
        assertEquals(
            listOf(
                VoicePrompt.Depart(0),
                VoicePrompt.Early(1, 100),
                VoicePrompt.Now(1),
                VoicePrompt.After(2),
                VoicePrompt.Early(3, 100),
                VoicePrompt.Now(3),
                VoicePrompt.Now(5)
            ),
            prompts
        )
    }

    @Test
    fun carWarnsEarlierAtSpeed() {
        val slow = ride(VoiceGuide(maneuvers, "car"), 30f, 0..2000 step 2)
        assertEquals(VoicePrompt.Early(1, 300), slow[1])
        val fast = ride(VoiceGuide(maneuvers, "car"), 90f, 1500..2600 step 2)
        assertEquals(VoicePrompt.Early(3, 500), fast.first())
    }

    @Test
    fun jitterDoesNotRepeat() {
        val guide = VoiceGuide(maneuvers, "bicycle")
        val path = listOf(0, 310, 318, 312, 320, 380, 390, 384, 392, 388)
        assertEquals(
            listOf(VoicePrompt.Depart(0), VoicePrompt.Early(1, 100), VoicePrompt.Now(1)),
            ride(guide, 18f, path)
        )
    }

    @Test
    fun closeTurnWithoutCueIsStillSaid() {
        val close = listOf(
            maneuver(1, 0.0, "Drive east."),
            maneuver(10, 1346.0, "Turn right onto Via E. Then Turn right onto Strada C."),
            maneuver(10, 1346.5, "Turn right onto Strada C."),
            maneuver(10, 1452.0, "Turn right onto Strada P."),
            maneuver(4, 3000.0, "You have arrived at your destination.")
        )
        val prompts = ride(VoiceGuide(close, "car"), 72f, 1000..1500 step 4)
        assertEquals(listOf(VoicePrompt.Now(1), VoicePrompt.Now(3)), prompts.filterIsInstance<VoicePrompt.Now>())
    }

    @Test
    fun cueFromAnUnsaidManeuverDoesNotSilenceTheNext() {
        val guide = VoiceGuide(maneuvers, "bicycle")
        assertEquals(VoicePrompt.Now(2), guide.update(430.0, 18f, false))
    }

    @Test
    fun offTrackIsSaidOncePerExcursion() {
        val guide = VoiceGuide(maneuvers, "bicycle")
        guide.update(0.0, 18f, false)
        assertEquals(VoicePrompt.OffTrack, guide.update(100.0, 18f, true))
        assertNull(guide.update(110.0, 18f, true))
        assertNull(guide.update(120.0, 18f, false))
        assertEquals(VoicePrompt.OffTrack, guide.update(130.0, 18f, true))
    }

    @Test
    fun arrivalIsSaidOnce() {
        val guide = VoiceGuide(maneuvers, "bicycle")
        assertEquals(VoicePrompt.Now(5), guide.arrival())
        assertNull(guide.arrival())
        assertNull(guide.update(2890.0, 18f, false))
    }

    @Test
    fun spokenDistancesAreRounded() {
        assertEquals(300, roundSpokenMeters(287.0))
        assertEquals(40, roundSpokenMeters(42.0))
        assertEquals(10, roundSpokenMeters(4.0))
    }
}
