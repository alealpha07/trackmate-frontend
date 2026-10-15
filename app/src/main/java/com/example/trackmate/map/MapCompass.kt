package com.example.trackmate.map

import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.trackmate.R
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
        val context = button.context
        button.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, if (isNorthLocked) R.color.map_control_active else R.color.map_control)
        )
        button.contentDescription = context.getString(
            if (isNorthLocked) R.string.map_compass_locked else R.string.map_compass_follow
        )
    }
}
