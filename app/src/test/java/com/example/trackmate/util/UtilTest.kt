package com.example.trackmate.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UtilTest {
    @Test
    fun timeAsHoursMinutesSeconds() {
        assertEquals("00:00:00", formatTime(0f))
        assertEquals("00:00:59", formatTime(59.9f))
        assertEquals("01:02:05", formatTime(3725f))
        assertEquals("27:46:40", formatTime(100_000f))
    }

    @Test
    fun vehiclesByTheBackendIds() {
        assertEquals(listOf("car", "motorcycle", "bicycle", "foot"), Vehicle.entries.map { it.id })
        assertEquals(Vehicle.BICYCLE, Vehicle.fromId("bicycle"))
        assertNull(Vehicle.fromId("BICYCLE"))
        assertNull(Vehicle.fromId("plane"))
        assertNull(Vehicle.fromId(null))
    }

    @Test
    fun vehicleClassesMatchTheBackend() {
        assertEquals(listOf(Vehicle.CAR, Vehicle.MOTORCYCLE), Vehicle.MOTORCYCLE.classVehicles)
        assertEquals(listOf(Vehicle.BICYCLE), Vehicle.BICYCLE.classVehicles)
        assertEquals(listOf(Vehicle.FOOT), Vehicle.FOOT.classVehicles)
    }
}
