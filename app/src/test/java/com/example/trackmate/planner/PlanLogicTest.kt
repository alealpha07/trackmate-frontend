package com.example.trackmate.planner

import com.example.trackmate.api.PlanMessage
import com.example.trackmate.api.PlanProgress
import com.example.trackmate.api.buildMoshi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PlanLogicTest {
    private val defaultLocale = Locale.getDefault()
    private val adapter = buildMoshi().adapter(PlanMessage::class.java)

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun coordinatesInTheUsualWays() {
        for (text in listOf("44.8, 10.3", "44.8 10.3", "44.8,10.3", "44.8;10.3", "44,8; 10,3", "44,8 10,3")) {
            assertEquals(text, PlanPoint(44.8, 10.3), parseCoordinates(text))
        }
        assertEquals(PlanPoint(-33.86, 151.21), parseCoordinates("-33.86, 151.21"))
        for (text in listOf("91, 10", "44, 181", "Parma", "44.8", "44.8, 10.3, 5", "")) {
            assertNull(text, parseCoordinates(text))
        }
    }

    @Test
    fun pointTextPrefersTheLabel() {
        assertEquals("44.78150, 9.84600", pointText(PlanPoint(44.7815, 9.846)))
        assertEquals("Parma", pointText(PlanPoint(44.8, 10.3, "Parma")))
        assertEquals("", pointText(null))
    }

    @Test
    fun pinsAreAThenNumbersThenB() {
        assertEquals(listOf("A", "1", "2", "B"), (0 until 4).map { pinLabel(it, 4) })
        assertEquals(listOf("A", "B"), (0 until 2).map { pinLabel(it, 2) })
    }

    @Test
    fun routeProblems() {
        val parma = PlanPoint(44.8015, 10.3279)
        val fidenza = PlanPoint(44.866, 10.061)
        val milano = PlanPoint(45.4642, 9.19)
        assertNull(routeProblem(listOf(null, parma)))
        assertNull(routeProblem(listOf(parma, null)))
        assertNull(routeProblem(listOf(parma, fidenza)))
        assertEquals(RouteProblem.EmptyStop, routeProblem(listOf(parma, null, fidenza)))

        val tooFar = routeProblem(listOf(parma, fidenza, milano)) as RouteProblem.LegTooFar
        assertEquals(1, tooFar.leg)
        assertTrue(tooFar.meters in MAX_LEG_METERS..100_000.0)
    }

    @Test
    fun haversineOnAMeridian() {
        // one degree of latitude on the mean earth radius
        assertEquals(111_195.0, haversineMeters(PlanPoint(44.0, 10.0), PlanPoint(45.0, 10.0)), 1.0)
        assertEquals(0.0, haversineMeters(PlanPoint(44.0, 10.0), PlanPoint(44.0, 10.0)), 0.0)
    }

    @Test
    fun numbersFollowTheLocale() {
        Locale.setDefault(Locale.US)
        assertEquals("3.3 km", formatDistance(3265.0))
        assertEquals("1,234.5 km", formatDistance(1_234_500.0))
        assertEquals("57.7", formatNumber(57.66, 0))
        assertEquals("58", formatNumber(58.0, 0))
        Locale.setDefault(Locale.ITALY)
        assertEquals("3,3 km", formatDistance(3265.0))
        Locale.setDefault(Locale.GERMANY)
        assertEquals("1.234,5 km", formatDistance(1_234_500.0))
    }

    @Test
    fun durationsInMinutesAndHours() {
        assertEquals("1 min", formatDuration(10.0))
        assertEquals("12 min", formatDuration(720.0))
        assertEquals("1 h 00 min", formatDuration(3599.0))
        assertEquals("2 h 01 min", formatDuration(7260.0))
    }

    @Test
    fun theStreamGivesProgressThenTheRoute() = runTest {
        val progress = mutableListOf<PlanProgress>()
        val route = readPlanMessages(lines(
            """{"progress":{"done":0,"total":0}}""",
            "",
            """{"progress":{"done":0,"total":2}}""",
            """{"progress":{"done":2,"total":2}}""",
            """{"route":{"track":[{"lat":44.78,"lng":9.84,"timestamp":0,"speed":0}],"distance":2488.4,"duration":597.2,"risk":900.1,"legs":[{"distance":2488.4,"duration":597.2,"risk":900.1,"from":{"lat":44.78,"lng":9.84},"to":{"lat":44.78,"lng":9.85}}]}}"""
        ), adapter) { progress += it }
        // a total of 0 means nothing to download
        assertEquals(listOf(PlanProgress(0, 2), PlanProgress(2, 2)), progress)
        assertEquals(2488.4, route.distance, 0.0)
        assertEquals(1, route.legs.size)
        assertNull(route.offBikeways)
    }

    @Test
    fun anErrorLineStopsTheStream() {
        val error = planError(lines("""{"progress":{"done":0,"total":2}}""", """{"error":"Map data is unavailable","status":502}"""))
        assertEquals("Map data is unavailable", error.message)
    }

    @Test
    fun aStreamWithoutARouteIsAnError() {
        assertEquals("", planError(lines("""{"progress":{"done":0,"total":0}}""")).message)
    }

    private fun planError(source: Buffer): PlanError =
        assertThrows(PlanError::class.java) { runBlocking { readPlanMessages(source, adapter) {} } }

    private fun lines(vararg lines: String) = Buffer().writeUtf8(lines.joinToString("\n") + "\n")
}
