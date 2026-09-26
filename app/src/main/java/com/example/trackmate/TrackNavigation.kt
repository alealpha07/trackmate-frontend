package com.example.trackmate

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.navArgs
import com.example.trackmate.services.*
import com.google.android.gms.location.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.*
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

fun formatTime(seconds: Float): String {
    val totalSeconds = seconds.toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, secs)
}

class TrackNavigation : Fragment() {

    private lateinit var mapView: MapView
    private lateinit var myLocationOverlay: AnimatedMyLocationOverlay
    private lateinit var compass: MapCompassController
    private lateinit var btnRecord: Button
    private lateinit var txtDistance: TextView
    private lateinit var txtDuration: TextView
    private lateinit var txtTrackName: TextView
    private lateinit var txtTrackLength: TextView
    private lateinit var txtUserBest: TextView
    private lateinit var txtUserBestAvg: TextView
    private lateinit var txtUserBestSpd: TextView
    private lateinit var statsLayout: LinearLayout
    private lateinit var txtCurrentSpeed: TextView
    private lateinit var btnMoreDetails: Button

    private var referencePolyline: Polyline? = null
    private var passedPolyline: Polyline? = null
    private val pathPoints = mutableListOf<GeoPoint>()
    private val referencePoints = mutableListOf<GeoPoint>()

    private var offTrackDialog: AlertDialog? = null
    private var offTrackShown = false
    private var navigationFinishHandled = false
    private var userIsInteracting = false
    private var needsBearingSnap = false
    private var isNavigatingUiActive = false

    private val handler = Handler(Looper.getMainLooper())
    private val resumeFollowRunnable = Runnable { userIsInteracting = false }

    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)
    private lateinit var api: TrackService
    private lateinit var questApi: QuestService
    private val args: TrackNavigationArgs by navArgs()

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var idleSpeedCallback: LocationCallback? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                enableMyLocation()
                if (!TrackNavigationService.isNavigating) startIdleSpeedUpdates()
            } else {
                Toast.makeText(requireContext(), "Location permission denied", Toast.LENGTH_SHORT)
                    .show()
            }
        }

    private fun enableMyLocation() {
        if (!::mapView.isInitialized) return

        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            myLocationOverlay.enableMyLocation()
        }
    }

    // Keeps the speed readout live while previewing/finishing a track, i.e. outside of an
    // active navigation, which otherwise drives txtCurrentSpeed via its own broadcast.
    private fun startIdleSpeedUpdates() {
        if (idleSpeedCallback != null) return
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()

        idleSpeedCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val speedKmh = if (location.hasSpeed() && location.speed <= MAX_PLAUSIBLE_SPEED_MPS) {
                    location.speed * 3.6f
                } else 0f
                txtCurrentSpeed.text = "Speed: ${speedKmh.roundToInt()} km/h"
            }
        }
        fusedLocationClient.requestLocationUpdates(request, idleSpeedCallback!!, Looper.getMainLooper())
    }

    private fun stopIdleSpeedUpdates() {
        idleSpeedCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        idleSpeedCallback = null
    }


    // Interpolates the map's position/rotation between successive GPS fixes so panning and
    // tilting read as continuous motion instead of a snap on every ~500ms location update.
    private var motionAnimator = createMotionAnimator()

    private fun createMotionAnimator() =
        MapMotionAnimator { point, bearing -> onLocationFrame(point, bearing) }

    private val navigationUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val distance = intent.getFloatExtra("distance", 0f)
            val duration = intent.getLongExtra("duration", 0L)
            val lat = intent.getDoubleExtra("lat", Double.NaN)
            val lng = intent.getDoubleExtra("lng", Double.NaN)
            val isFinished = intent.getBooleanExtra("isFinished", false)
            val nearestIndex = intent.getIntExtra("nearestIndex", -1)
            val offTrack = intent.getBooleanExtra("offTrack", false)
            val bearing = intent.getFloatExtra("bearing", Float.NaN)
            val speed = intent.getFloatExtra("speed", 0f)

            txtCurrentSpeed.text = "Speed: ${speed.roundToInt()} km/h"
            txtDistance.text = "Distance: ${String.format("%.2f", distance)} km"
            txtDuration.text = "Duration: ${formatTime((duration / 1000).toFloat())}"

            if (!lat.isNaN() && !lng.isNaN()) {
                val newPoint = GeoPoint(lat, lng)
                pathPoints.add(newPoint)

                val resolvedBearing = if (bearing.isNaN()) null else bearing

                if (needsBearingSnap && resolvedBearing != null) {
                    needsBearingSnap = false
                    motionAnimator.snapTo(newPoint, resolvedBearing)
                } else {
                    motionAnimator.animateTo(newPoint, resolvedBearing)
                }
            }

            if (nearestIndex >= 0 && referencePoints.isNotEmpty()) {
                val clampedIndex = nearestIndex.coerceAtMost(referencePoints.size - 1)
                passedPolyline?.setPoints(referencePoints.subList(0, clampedIndex + 1))
                mapView.invalidate()
            }

            if (offTrack && !offTrackShown) {
                offTrackShown = true
                showOffTrackDialog()
            } else if (!offTrack && offTrackShown) {
                offTrackShown = false
                dismissOffTrackDialog()
            }

            if (isFinished && !navigationFinishHandled) {
                navigationFinishHandled = true
                stopNavigation(true)
            }
        }
    }

    private fun showOffTrackDialog() {
        if (offTrackDialog?.isShowing == true) return
        offTrackDialog = AlertDialog.Builder(requireContext())
            .setTitle("Off track")
            .setMessage("You are off the route. Please get back to the track.")
            .setCancelable(false)
            .setPositiveButton("OK", null)
            .create()
        offTrackDialog?.show()
    }

    private fun dismissOffTrackDialog() {
        offTrackDialog?.dismiss()
        offTrackDialog = null
    }

    private fun onLocationFrame(point: GeoPoint, bearing: Float) {
        if (!::mapView.isInitialized) return
        // Keep the cursor in step with the animated camera instead of the raw GPS fix
        myLocationOverlay.setAnimatedPosition(point, bearing)

        if (!userIsInteracting) {
            // The look-ahead offset follows the direction of travel even when locked north
            val cameraTarget = computeOffset(point, bearing.toDouble(), 30.0)
            mapView.mapOrientation = -compass.resolveBearing(bearing)
            mapView.controller.setCenter(cameraTarget)
        }
        compass.syncButtonRotation()
        mapView.invalidate()
    }

    private fun resetTravelledPath() {
        pathPoints.clear()
        motionAnimator.reset()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_track_navigation, container, false)

        mapView = view.findViewById(R.id.mapView)
        btnRecord = view.findViewById(R.id.btnRecord)
        txtTrackName = view.findViewById(R.id.txtTrackName)
        txtTrackLength = view.findViewById(R.id.txtTrackLength)
        txtUserBest = view.findViewById(R.id.txtUserBest)
        txtUserBestAvg = view.findViewById(R.id.txtUserBestAvg)
        txtUserBestSpd = view.findViewById(R.id.txtUserBestSpd)
        txtDistance = view.findViewById(R.id.txtDistance)
        txtDuration = view.findViewById(R.id.txtDuration)
        statsLayout = view.findViewById(R.id.statsLayout)
        txtCurrentSpeed = view.findViewById(R.id.txtCurrentSpeed)
        btnMoreDetails = view.findViewById(R.id.btnMoreDetails)

        api = (requireActivity() as MainActivity).trackService
        questApi = (requireActivity() as MainActivity).questService

        setupMap(view)

        btnRecord.text =
            if (TrackNavigationService.isNavigating) "Cancel Navigation" else "Start Navigation"
        btnRecord.setOnClickListener { if (TrackNavigationService.isNavigating) stopNavigation() else startNavigation() }
        btnMoreDetails.setOnClickListener{
            val action = TrackNavigationDirections.actionTrackNavigationToTravelGraph(args.trackId)
            findNavController().navigate(action)
        }
        return view
    }

    private fun setupMap(view: View) {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
        // Without this, detaching the view from its window (e.g. via forceMapViewRebind())
        // makes osmdroid tear down the tile provider and clear all overlays permanently -
        // it's meant for a real teardown, not a transient reattach.
        mapView.setDestroyMode(false)
        val mapStyle = getSavedMapStyle(requireContext())
        mapView.setTileSource(tileSourceFor(mapStyle))
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(DEFAULT_MAP_ZOOM)
        applyOverlayTextColor(mapStyle, txtDistance, txtDuration, txtCurrentSpeed)

        mapView.overlays.add(buildCopyrightOverlay(requireContext()))

        myLocationOverlay = AnimatedMyLocationOverlay(GpsMyLocationProvider(requireContext()), mapView)
        applyPrimaryLocationIcons(requireContext(), myLocationOverlay)
        mapView.overlays.add(myLocationOverlay)

        setupMapControls(
            fragment = this,
            mapView = mapView,
            myLocationOverlay = myLocationOverlay,
            btnLayers = view.findViewById(R.id.btnLayers),
            btnMyLocation = view.findViewById(R.id.btnMyLocation),
            btnZoomIn = view.findViewById(R.id.btnZoomIn),
            btnZoomOut = view.findViewById(R.id.btnZoomOut),
            onStyleChanged = { style ->
                applyOverlayTextColor(style, txtDistance, txtDuration, txtCurrentSpeed)
            }
        )
        compass = MapCompassController(mapView, view.findViewById(R.id.btnCompass))

        mapView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    userIsInteracting = true
                    handler.removeCallbacks(resumeFollowRunnable)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.postDelayed(resumeFollowRunnable, DRAG_RESUME_FOLLOW_TIME)
                }
            }
            false
        }

        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            == PackageManager.PERMISSION_GRANTED
        ) {
            enableMyLocation()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        loadTrackData()
    }


    private fun loadTrackData() {
        apiCallCoroutine.launch {
            try {
                var track: Track? = null
                val fileResponse = api.getTrackFile(args.trackId)
                if (fileResponse.isSuccessful && fileResponse.body() != null) {
                    val file = File(requireContext().filesDir, "navigation.json")
                    fileResponse.body()!!.byteStream().use { it.copyTo(FileOutputStream(file)) }
                    val moshi = com.squareup.moshi.Moshi.Builder().build()
                    val adapter = moshi.adapter(Track::class.java)
                    track = adapter.fromJson(file.readText())
                }

                val response = api.getTrack(args.trackId)
                if (response.isSuccessful && response.body() != null) {
                    val details = response.body()!!
                    withContext(Dispatchers.Main) {
                        txtTrackName.text = details.name
                        details.userBest?.let {
                            txtUserBest.text = "Your Best Time: ${formatTime(it.time)}"
                            txtUserBestAvg.text = "Your Best Avg Speed: ${it.averageSpeed} km/h"
                            txtUserBestSpd.text = "Your Best Speed: ${it.maxSpeed} km/h"
                        }
                    }
                }

                track?.let { t ->
                    val points = t.track.map { GeoPoint(it.latitude, it.longitude) }
                    if (points.isNotEmpty()) {
                        withContext(Dispatchers.Main) { setupTrackOnMap(points) }
                    }
                }

            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }

    private fun setupTrackOnMap(points: List<GeoPoint>) {
        referencePoints.clear()
        referencePoints.addAll(points)

        referencePolyline?.let { mapView.overlays.remove(it) }
        referencePolyline = Polyline().apply {
            setPoints(points)
            outlinePaint.color = ContextCompat.getColor(
                requireContext(),
                com.google.android.material.R.color.design_default_color_primary
            )
            outlinePaint.strokeWidth = 8f
        }
        mapView.overlays.add(referencePolyline)

        passedPolyline?.let { mapView.overlays.remove(it) }
        passedPolyline = Polyline().apply {
            setPoints(emptyList())
            outlinePaint.color = ContextCompat.getColor(requireContext(), android.R.color.darker_gray)
            outlinePaint.strokeWidth = 10f
        }
        mapView.overlays.add(passedPolyline)

        val startMarker = Marker(mapView).apply {
            position = points.first()
            title = "Start"
            icon = createLetterMarkerIcon(requireContext(), 'A')
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
        mapView.overlays.add(startMarker)

        val finishMarker = Marker(mapView).apply {
            position = points.last()
            title = "Finish"
            icon = createLetterMarkerIcon(requireContext(), 'B')
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
        mapView.overlays.add(finishMarker)

        // Keep the location cursor drawn above the track overlays added above it.
        if (::myLocationOverlay.isInitialized) {
            mapView.overlays.remove(myLocationOverlay)
            mapView.overlays.add(myLocationOverlay)
        }

        mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, 100)
        mapView.invalidate()

        var distance = 0.0
        for (i in 0 until points.size - 1) {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(
                points[i].latitude,
                points[i].longitude,
                points[i + 1].latitude,
                points[i + 1].longitude,
                results
            )
            distance += results[0]
        }
        txtTrackLength.text = "Length: %.2f km".format(distance / 1000)
        btnRecord.isEnabled = true
    }

    // Work around a rendering bug where mapOrientation keeps being updated correctly
    // (verified: the value is set and reads back correctly on a shown, correctly-sized,
    // hardware-accelerated view) but the MapView's rotation stops visually applying after
    // the app is backgrounded and resumed. Removing and re-adding the view to its parent
    // forces Android to fully tear down and rebuild its attachment/render state, rather than
    // reusing whatever got stuck. Re-added at the same index/LayoutParams to preserve both
    // z-order (it must stay drawn below the stats/buttons) and its ConstraintLayout constraints.
    private fun forceMapViewRebind() {
        val parent = mapView.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(mapView)
        val layoutParams = mapView.layoutParams
        parent.removeView(mapView)
        parent.addView(mapView, index, layoutParams)
    }

    override fun onResume() {
        super.onResume()
        forceMapViewRebind()
        mapView.onResume()
        motionAnimator = createMotionAnimator()
        if (TrackNavigationService.isNavigating) {
            if (args.trackId != TrackNavigationService.trackId){
                stopNavigation()
            }
            else{
                statsLayout.visibility = View.GONE
                txtDistance.visibility = View.VISIBLE
                txtDuration.visibility = View.VISIBLE
                txtCurrentSpeed.visibility = View.VISIBLE
                btnRecord.text = "Cancel Navigation"
                mapView.controller.setZoom(TRACKING_ZOOM_LEVEL)
                needsBearingSnap = true
                isNavigatingUiActive = true
                compass.setActive(true)
                myLocationOverlay.followsAnimation = true
            }
        } else if (isNavigatingUiActive) {
            stopNavigation(true)
        } else {
            startIdleSpeedUpdates()
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            navigationUpdateReceiver,
            IntentFilter("com.example.trackmate.NAVIGATION_UPDATE")
        )
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        stopIdleSpeedUpdates()
        motionAnimator.cancel()
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(navigationUpdateReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mapView.onDetach()
    }

    private fun startNavigation() {
        if (referencePoints.isEmpty()) {
            Toast.makeText(requireContext(), "Reference track not loaded yet", Toast.LENGTH_SHORT)
                .show()
            return
        }

        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location == null) {
                Toast.makeText(
                    requireContext(),
                    "Unable to get current location",
                    Toast.LENGTH_SHORT
                ).show()
                return@addOnSuccessListener
            }
            val start = referencePoints.first()
            val distArray = FloatArray(1)
            android.location.Location.distanceBetween(
                location.latitude,
                location.longitude,
                start.latitude,
                start.longitude,
                distArray
            )
            val dist = distArray[0]
            if (dist > OFF_TRACK_THRESHOLD) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Too far from start")
                    .setMessage("You are ${dist} meters away from the start. Move closer to the start (within ${OFF_TRACK_THRESHOLD} m) to begin navigation.")
                    .setPositiveButton("OK", null)
                    .show()
                return@addOnSuccessListener
            }

            resetTravelledPath()
            navigationFinishHandled = false
            isNavigatingUiActive = true
            stopIdleSpeedUpdates()
            TrackNavigationService.isNavigating = true
            statsLayout.visibility = View.GONE
            btnRecord.text = "Cancel Navigation"
            compass.setActive(true)
            myLocationOverlay.followsAnimation = true
            mapView.controller.setZoom(TRACKING_ZOOM_LEVEL)

            val sourceFile = File(requireContext().filesDir, "navigation.json")
            val destinationFile = File(requireContext().filesDir, "navigation_track.json")
            sourceFile.copyTo(destinationFile, overwrite = true)

            val intent = Intent(requireContext(), TrackNavigationService::class.java)
            intent.putExtra("trackId", args.trackId)
            requireContext().startForegroundService(intent)

            txtDistance.visibility = View.VISIBLE
            txtDuration.visibility = View.VISIBLE
            txtCurrentSpeed.visibility = View.VISIBLE
        }.addOnFailureListener {
            Toast.makeText(
                requireContext(),
                "Unable to get last location to start navigation",
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    private fun endNavigation() {
        TrackNavigationService.isNavigating = false
        isNavigatingUiActive = false
        btnRecord.text = "Start Navigation"
        compass.setActive(false)
        myLocationOverlay.followsAnimation = false

        txtDistance.text = "Distance: 0.00 km"
        txtDuration.text = "Duration: 0:0:0"
        txtCurrentSpeed.text = "Speed: 0 km/h"
        txtDistance.visibility = View.GONE
        txtDuration.visibility = View.GONE
        statsLayout.visibility = View.VISIBLE

        val intent = Intent(requireContext(), TrackNavigationService::class.java)
        requireContext().stopService(intent)
        startIdleSpeedUpdates()
    }

    private fun stopNavigation(isFinished: Boolean = false) {
        if (isFinished) {
            saveTravel()
            endNavigation()
            AlertDialog.Builder(requireContext())
                .setTitle("Navigation Completed")
                .setPositiveButton("OK", null)
                .show()
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle("Cancel Navigation")
                .setMessage("Are you sure you want to stop? Your progress will be lost.")
                .setPositiveButton("Confirm") { _, _ ->
                    endNavigation()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun saveTravel() {
        val travelData = TrackNavigationService.getLastNavigationData()
        if (travelData == null) {
            Toast.makeText(requireContext(), "No travel data to save.", Toast.LENGTH_SHORT).show()
            return
        }

        apiCallCoroutine.launch {
            try {
                val travelResponse = api.createTravel(travelData)
                if (travelResponse.isSuccessful) {
                    questApi.increaseQuest(
                        IncreaseQuestRequest(
                            QuestType.TRAVEL_DISTANCE.toString(),
                            travelData.distance.toInt()
                        )
                    )
                    questApi.increaseQuest(
                        IncreaseQuestRequest(
                            QuestType.NAVIGATE_TRACK.toString(),
                            1
                        )
                    )
                    travelResponse.body()?.id?.let { travelId -> uploadTravelPoints(travelId) }
                }
                withContext(Dispatchers.Main) {
                    if (travelResponse.isSuccessful) {
                        Toast.makeText(
                            requireContext(),
                            "Travel data saved successfully!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            travelResponse.errorBody()?.string() ?: "Error saving travel data",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }

    private suspend fun uploadTravelPoints(travelId: Int) {
        val points = TrackNavigationService.getLastNavigationPoints() ?: return
        try {
            val moshi = com.squareup.moshi.Moshi.Builder().build()
            val adapter = moshi.adapter(Track::class.java)
            val json = adapter.toJson(Track(points))

            val file = File(requireContext().filesDir, "travel_points.json")
            file.writeText(json)

            val requestFile = file.asRequestBody("application/json".toMediaTypeOrNull())
            val body = MultipartBody.Part.createFormData("file", file.name, requestFile)
            api.uploadTravelFile(body, travelId)
        } catch (e: Exception) {
            Log.d("API-ERROR", e.stackTraceToString())
        }
    }
}
