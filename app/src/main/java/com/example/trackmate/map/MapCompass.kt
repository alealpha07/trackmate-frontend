package com.example.trackmate.map

import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.view.isVisible
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.osmdroid.views.MapView

// compass button: toggles rotating map and north up
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

    fun setActive(active: Boolean) {
        button.isVisible = active
        syncButtonRotation()
    }

    fun resolveBearing(travelBearing: Float): Float = if (isNorthLocked) 0f else travelBearing

    fun syncButtonRotation() {
        button.rotation = mapView.mapOrientation
    }

    private fun toggleNorthLock() {
        isNorthLocked = !isNorthLocked
        applyStyle()
        // snap to north right away
        if (isNorthLocked) {
            mapView.mapOrientation = 0f
            mapView.invalidate()
        }
        syncButtonRotation()
    }

    private fun applyStyle() {
        // tinted background when north is locked
        button.backgroundTintList = ColorStateList.valueOf(
            if (isNorthLocked) Color.parseColor("#EDE7F6") else Color.WHITE
        )
        button.contentDescription =
            if (isNorthLocked) "Map locked to north. Tap to follow direction of travel"
            else "Map follows direction of travel. Tap to lock to north"
    }
}
