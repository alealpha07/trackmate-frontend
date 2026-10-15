package com.example.trackmate.map

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.trackmate.R
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.abs
import kotlin.math.pow

const val DEFAULT_MAP_ZOOM = 16.0
const val TRACKING_ZOOM_LEVEL = 18.5
const val FAST_TRACKING_ZOOM_LEVEL = 16.0
const val DRAG_RESUME_FOLLOW_TIME: Long = 5000
// follow zoom goes from street level to motorway level
const val FOLLOW_ZOOM_SLOW_KMH = 20f
const val FOLLOW_ZOOM_FAST_KMH = 110f
const val FOLLOW_LOOK_AHEAD_METERS = 30.0

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

fun followZoomFor(speedKmh: Float): Double {
    val t = ((speedKmh - FOLLOW_ZOOM_SLOW_KMH) / (FOLLOW_ZOOM_FAST_KMH - FOLLOW_ZOOM_SLOW_KMH)).coerceIn(0f, 1f)
    return TRACKING_ZOOM_LEVEL + (FAST_TRACKING_ZOOM_LEVEL - TRACKING_ZOOM_LEVEL) * t
}

// scaled with the zoom so the cursor keeps its place
fun followLookAhead(zoom: Double) = FOLLOW_LOOK_AHEAD_METERS * 2.0.pow(TRACKING_ZOOM_LEVEL - zoom)

// zooms out with speed, the user's zoom stays as an offset
class FollowZoom(private val mapView: MapView) {
    private var speedKmh = 0f
    private var applied = TRACKING_ZOOM_LEVEL

    fun reset(clearSpeed: Boolean = false) {
        if (clearSpeed) speedKmh = 0f
        applied = followZoomFor(speedKmh)
        mapView.controller.setZoom(applied)
    }

    // smoothed so one noisy fix doesn't pump the zoom
    fun onSpeed(kmh: Float) {
        speedKmh += (kmh - speedKmh) * 0.3f
    }

    // called every frame, moves a small part of the way
    fun step() {
        val delta = (followZoomFor(speedKmh) - applied) * 0.05
        if (abs(delta) < 0.0005) return
        applied += delta
        mapView.controller.setZoom(
            (mapView.zoomLevelDouble + delta).coerceIn(mapView.minZoomLevel, mapView.maxZoomLevel)
        )
    }
}

class MapMotionAnimator(
    // about the GPS fix interval, so the glide doesn't stop early
    private val durationMs: Long = 950L,
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

    fun snapTo(target: GeoPoint, targetBearing: Float) {
        animator?.cancel()
        currentPoint = target
        currentBearing = targetBearing
        onFrame(target, targetBearing)
    }

    fun cancel() {
        animator?.cancel()
    }

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
    // The planner centers above its bottom sheet
    centerOnLocation: (GeoPoint) -> Unit = { mapView.controller.animateTo(it, 16.0, 500L) }
) {
    val context = fragment.requireContext()

    mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

    btnLayers.setOnClickListener {
        val styles = MapStyle.entries.toTypedArray()
        val current = getSavedMapStyle(context)
        val labels = styles.map { context.getString(it.label) }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(R.string.map_style)
            .setSingleChoiceItems(labels, styles.indexOf(current)) { dialog, which ->
                val selected = styles[which]
                saveMapStyle(context, selected)
                mapView.setTileSource(tileSourceFor(context, selected))
                mapView.invalidate()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    btnZoomIn.setOnClickListener { mapView.controller.zoomIn() }
    btnZoomOut.setOnClickListener { mapView.controller.zoomOut() }

    btnMyLocation.setOnClickListener {
        val location = myLocationOverlay.myLocation
        if (location != null) {
            centerOnLocation(location)
        } else {
            Toast.makeText(context, R.string.map_location_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
}
