package com.example.trackmate

import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.view.isVisible
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.osmdroid.views.MapView

/**
 * Drives the compass button shown in the map controls while recording/navigating.
 * Tapping toggles between the map rotating with the direction of travel and the map
 * locked north-up. The button rotates so its red needle always points at north on screen.
 */
class MapCompassController(
    private val mapView: MapView,
    private val button: FloatingActionButton
) {
    var isNorthLocked = false
        private set

    init {
        // The icon carries its own colors
        button.imageTintList = null
        button.setOnClickListener { toggleNorthLock() }
        applyStyle()
        button.isVisible = false
    }

    /** Shows the button; only meaningful while bearings drive the map. */
    fun setActive(active: Boolean) {
        button.isVisible = active
        syncButtonRotation()
    }

    /** The bearing the map should be rotated to, given the current direction of travel. */
    fun resolveBearing(travelBearing: Float): Float = if (isNorthLocked) 0f else travelBearing

    /** Points the button's needle at north, matching the map's current rotation. */
    fun syncButtonRotation() {
        button.rotation = mapView.mapOrientation
    }

    private fun toggleNorthLock() {
        isNorthLocked = !isNorthLocked
        applyStyle()
        // Deliberate tap, so snap to north right away instead of waiting for the next GPS fix
        if (isNorthLocked) {
            mapView.mapOrientation = 0f
            mapView.invalidate()
        }
        syncButtonRotation()
    }

    private fun applyStyle() {
        // Tinted background marks the north lock as on
        button.backgroundTintList = ColorStateList.valueOf(
            if (isNorthLocked) Color.parseColor("#EDE7F6") else Color.WHITE
        )
        button.contentDescription =
            if (isNorthLocked) "Map locked to north. Tap to follow direction of travel"
            else "Map follows direction of travel. Tap to lock to north"
    }
}
