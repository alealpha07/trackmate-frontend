package com.example.trackmate

import android.animation.ValueAnimator
import android.location.Location
import android.view.animation.LinearInterpolator
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

// Roughly the GPS fix interval, so each glide runs into the next fix instead of stopping early
private const val IDLE_GLIDE_MS = 900L
// Farther than this (first fix, GPS catching up after a gap) the cursor jumps instead of sliding
private const val MAX_GLIDE_DISTANCE_METERS = 200f

/**
 * MyLocationNewOverlay whose cursor moves smoothly instead of jumping to every raw GPS fix.
 * During recording/navigation it follows the MapMotionAnimator frames, so it stays in step
 * with the camera and the polyline; otherwise it glides between its own fixes.
 */
class AnimatedMyLocationOverlay(
    provider: IMyLocationProvider,
    mapView: MapView
) : MyLocationNewOverlay(provider, mapView) {

    private var latestFix: Location? = null
    private var displayed: Location? = null
    private var glide: ValueAnimator? = null

    /** While true, raw fixes are held back and the cursor only moves via [setAnimatedPosition]. */
    var followsAnimation = false
        set(value) {
            field = value
            glide?.cancel()
            // Back to raw fixes: jump to the real position right away
            if (!value) latestFix?.let { show(it) }
        }

    // Called (on the main thread) for every raw fix from the location provider
    override fun setLocation(location: Location) {
        latestFix = location
        if (!followsAnimation) glideTo(location)
    }

    fun setAnimatedPosition(point: GeoPoint, bearing: Float) {
        if (!followsAnimation) return
        // Copy the real fix so accuracy (and whether a bearing exists at all) still comes from GPS
        val frame = latestFix?.let { Location(it) } ?: Location("animated")
        frame.latitude = point.latitude
        frame.longitude = point.longitude
        // The arrow replaces the dot only when GPS actually reports a bearing, same as before
        if (frame.hasBearing()) frame.bearing = bearing
        show(frame)
    }

    private fun glideTo(target: Location) {
        glide?.cancel()
        val start = displayed
        if (start == null || start.distanceTo(target) > MAX_GLIDE_DISTANCE_METERS) {
            show(target)
            return
        }

        val turnsSmoothly = start.hasBearing() && target.hasBearing()
        glide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = IDLE_GLIDE_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val frame = Location(target)
                frame.latitude = start.latitude + (target.latitude - start.latitude) * t
                frame.longitude = start.longitude + (target.longitude - start.longitude) * t
                if (turnsSmoothly) frame.bearing = interpolateHeading(start.bearing, target.bearing, t)
                show(frame)
            }
            start()
        }
    }

    private fun show(location: Location) {
        displayed = location
        super.setLocation(location)
    }

    override fun onDetach(mapView: MapView?) {
        glide?.cancel()
        super.onDetach(mapView)
    }
}
