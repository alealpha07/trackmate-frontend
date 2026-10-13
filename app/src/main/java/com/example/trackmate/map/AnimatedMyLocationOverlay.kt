package com.example.trackmate.map

import android.animation.ValueAnimator
import android.location.Location
import android.view.animation.LinearInterpolator
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

// about the GPS fix interval
private const val IDLE_GLIDE_MS = 900L
// farther than this the cursor jumps
private const val MAX_GLIDE_DISTANCE_METERS = 200f

// location overlay with a smoothly moving cursor
class AnimatedMyLocationOverlay(
    provider: IMyLocationProvider,
    mapView: MapView
) : MyLocationNewOverlay(provider, mapView) {

    private var latestFix: Location? = null
    private var displayed: Location? = null
    private var glide: ValueAnimator? = null

    var followsAnimation = false
        set(value) {
            field = value
            glide?.cancel()
            // back to raw fixes
            if (!value) latestFix?.let { show(it) }
        }

    override fun setLocation(location: Location) {
        latestFix = location
        if (!followsAnimation) glideTo(location)
    }

    fun setAnimatedPosition(point: GeoPoint, bearing: Float) {
        if (!followsAnimation) return
        // copy the fix to keep its accuracy and bearing
        val frame = latestFix?.let { Location(it) } ?: Location("animated")
        frame.latitude = point.latitude
        frame.longitude = point.longitude
        // arrow only when GPS reports a bearing
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
