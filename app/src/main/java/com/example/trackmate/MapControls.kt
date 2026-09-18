package com.example.trackmate

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

const val DEFAULT_MAP_ZOOM = 16.0
const val TRACKING_ZOOM_LEVEL = 18.5

fun interpolateHeading(from: Float, to: Float, t: Float): Float {
    val diff = ((to - from + 540f) % 360f) - 180f
    return (from + diff * t + 360f) % 360f
}

fun computeOffset(from: GeoPoint, bearingDeg: Double, distanceMeters: Double): GeoPoint {
    val earthRadius = 6371000.0
    val bearingRad = Math.toRadians(bearingDeg)
    val lat1 = Math.toRadians(from.latitude)
    val lon1 = Math.toRadians(from.longitude)
    val angDist = distanceMeters / earthRadius
    val lat2 = Math.asin(
        Math.sin(lat1) * Math.cos(angDist) +
                Math.cos(lat1) * Math.sin(angDist) * Math.cos(bearingRad)
    )
    val lon2 = lon1 + Math.atan2(
        Math.sin(bearingRad) * Math.sin(angDist) * Math.cos(lat1),
        Math.cos(angDist) - Math.sin(lat1) * Math.sin(lat2)
    )
    return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
}

/**
 * Animates the on-screen position/bearing smoothly between successive (infrequent) GPS fixes,
 * so the camera pan and map rotation don't visibly snap every time a new fix arrives. This is
 * purely a rendering aid: it interpolates for display only and never invents or persists points.
 */
class MapMotionAnimator(
    private val durationMs: Long = 450L,
    private val onFrame: (position: GeoPoint, bearing: Float) -> Unit
) {
    private var animator: ValueAnimator? = null
    private var currentPoint: GeoPoint? = null
    private var currentBearing: Float = 0f

    fun animateTo(target: GeoPoint, targetBearing: Float? = null) {
        val start = currentPoint ?: target
        val startBearing = currentBearing
        val endBearing = targetBearing ?: startBearing

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val point = GeoPoint(
                    start.latitude + (target.latitude - start.latitude) * t,
                    start.longitude + (target.longitude - start.longitude) * t
                )
                val bearing = interpolateHeading(startBearing, endBearing, t)
                currentPoint = point
                currentBearing = bearing
                onFrame(point, bearing)
            }
        }
        animator?.start()
    }

    /** Places the position/bearing immediately, with no animation (e.g. on first fix or resync). */
    fun snapTo(target: GeoPoint, targetBearing: Float) {
        animator?.cancel()
        currentPoint = target
        currentBearing = targetBearing
        onFrame(target, targetBearing)
    }

    fun cancel() {
        animator?.cancel()
    }

    /** Cancels any in-flight animation and forgets the last position, so the next animateTo()
     * call snaps immediately instead of animating in from a stale/unrelated point. */
    fun reset() {
        animator?.cancel()
        currentPoint = null
        currentBearing = 0f
    }
}

fun setupMapControls(
    fragment: Fragment,
    mapView: MapView,
    myLocationOverlay: MyLocationNewOverlay,
    btnLayers: View,
    btnMyLocation: View,
    btnZoomIn: View,
    btnZoomOut: View,
    onStyleChanged: (MapStyle) -> Unit = {}
) {
    val context = fragment.requireContext()

    mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

    btnLayers.setOnClickListener {
        val styles = MapStyle.entries.toTypedArray()
        val current = getSavedMapStyle(context)
        val labels = styles.map { it.label }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Map style")
            .setSingleChoiceItems(labels, styles.indexOf(current)) { dialog, which ->
                val selected = styles[which]
                saveMapStyle(context, selected)
                mapView.setTileSource(tileSourceFor(selected))
                mapView.invalidate()
                onStyleChanged(selected)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    btnZoomIn.setOnClickListener { mapView.controller.zoomIn() }
    btnZoomOut.setOnClickListener { mapView.controller.zoomOut() }

    btnMyLocation.setOnClickListener {
        val location = myLocationOverlay.myLocation
        if (location != null) {
            mapView.controller.animateTo(location, 16.0, 500L)
        } else {
            Toast.makeText(context, "Current location not available yet", Toast.LENGTH_SHORT).show()
        }
    }
}
