package com.example.trackmate.navigate

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import com.example.trackmate.MainActivity
import com.example.trackmate.R
import com.example.trackmate.api.IncreaseQuestRequest
import com.example.trackmate.api.NewTrackRequest
import com.example.trackmate.api.NewTravelRequest
import com.example.trackmate.api.QuestService
import com.example.trackmate.api.QuestType
import com.example.trackmate.map.AnimatedMyLocationOverlay
import com.example.trackmate.map.DEFAULT_MAP_ZOOM
import com.example.trackmate.map.DRAG_RESUME_FOLLOW_TIME
import com.example.trackmate.map.MapCompassController
import com.example.trackmate.map.MapCredit
import com.example.trackmate.map.MapMotionAnimator
import com.example.trackmate.map.TRACKING_ZOOM_LEVEL
import com.example.trackmate.map.applyPrimaryLocationIcons
import com.example.trackmate.map.computeOffset
import com.example.trackmate.map.getSavedMapStyle
import com.example.trackmate.map.setupMapControls
import com.example.trackmate.map.tileSourceFor
import com.example.trackmate.track.MAX_PLAUSIBLE_SPEED_MPS
import com.example.trackmate.track.TrackNavigationService
import com.example.trackmate.util.Vehicle
import com.example.trackmate.util.hasLocationPermission
import com.example.trackmate.util.locationGranted
import com.example.trackmate.util.requestLocationWithRationale
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

class Navigate : Fragment() {

    private lateinit var mapView: MapView
    private lateinit var btnRecord: Button
    private lateinit var btnOpenLibrary: Button
    private lateinit var btnPlanRoute: Button
    private lateinit var myLocationOverlay: AnimatedMyLocationOverlay
    private lateinit var compass: MapCompassController
    private lateinit var rideStats: RideStatsCard
    private lateinit var questApi: QuestService
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var idleSpeedCallback: LocationCallback? = null
    private var polyline: Polyline? = null
    private val pathPoints = mutableListOf<GeoPoint>()
    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)
    private var userIsInteracting = false
    private val handler = Handler(Looper.getMainLooper())
    private val resumeFollowRunnable = Runnable {
        userIsInteracting = false
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.locationGranted()) {
                enableMyLocation()
                if (!TrackRecordingService.isRecording) startIdleSpeedUpdates()
            } else {
                Toast.makeText(requireContext(), R.string.location_denied, Toast.LENGTH_SHORT)
                    .show()
            }
        }

    // live speed while not recording
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
                rideStats.showSpeed(speedKmh)
            }
        }
        fusedLocationClient.requestLocationUpdates(request, idleSpeedCallback!!, Looper.getMainLooper())
    }

    private fun stopIdleSpeedUpdates() {
        idleSpeedCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        idleSpeedCallback = null
    }

    private fun enableMyLocation() {
        if (!::mapView.isInitialized) return

        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            myLocationOverlay.enableMyLocation()
            myLocationOverlay.runOnFirstFix {
                handler.post {
                    myLocationOverlay.myLocation?.let { location ->
                        mapView.controller.animateTo(location, 16.0, 1000L)
                    }
                }
            }
        }
    }

    private val trackUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val distance = intent.getFloatExtra("distance", 0f)
            val duration = intent.getLongExtra("duration", 0L)
            val speed = intent.getFloatExtra("speed", 0f)

            rideStats.show(distance, duration, speed)

            val lat = intent.getDoubleExtra("lat", Double.NaN)
            val lng = intent.getDoubleExtra("lng", Double.NaN)
            val bearing = intent.getFloatExtra("bearing", Float.NaN)

            if (!lat.isNaN() && !lng.isNaN()) {
                val newPoint = GeoPoint(lat, lng)
                if (pathPoints.lastOrNull() != newPoint) {
                    pathPoints.add(newPoint)
                }
                motionAnimator.animateTo(newPoint, if (bearing.isNaN()) null else bearing)
            }
        }
    }

    private val trackFileSavedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            showTrackNameDialog(
                title = getString(R.string.record_save_title),
                message = getString(R.string.record_save_message),
                vehicle = Vehicle.lastUsed(requireContext()),
                vehicles = Vehicle.profile(requireContext()),
                saveLabel = getString(R.string.dialog_save),
                cancelLabel = getString(R.string.dialog_discard),
                confirmCancel = getString(R.string.record_discard_confirm),
                onCancel = ::deleteRecordedFile
            ) { name, vehicle, done -> saveTrack(name, vehicle!!, done) }
        }
    }

    // smooth map motion between GPS fixes, display only
    private val motionAnimator = MapMotionAnimator { point, bearing -> onLocationFrame(point, bearing) }

    private fun ensurePolyline(): Polyline {
        polyline?.let { return it }
        val newPolyline = Polyline().apply {
            outlinePaint.color = ContextCompat.getColor(
                requireContext(),
                com.google.android.material.R.color.design_default_color_primary
            )
            outlinePaint.strokeWidth = 10f
        }
        polyline = newPolyline
        mapView.overlays.add(newPolyline)
        return newPolyline
    }

    private fun redrawPolyline() {
        if (!::mapView.isInitialized) return
        ensurePolyline().setPoints(pathPoints)
        mapView.invalidate()
    }

    private fun onLocationFrame(point: GeoPoint, bearing: Float) {
        if (!::mapView.isInitialized) return

        val displayedPoints = if (pathPoints.isNotEmpty()) pathPoints.dropLast(1) + point else listOf(point)
        ensurePolyline().setPoints(displayedPoints)
        // cursor on the tip of the animated line
        myLocationOverlay.setAnimatedPosition(point, bearing)

        if (!userIsInteracting) {
            // look-ahead follows the direction of travel
            val cameraTarget = computeOffset(point, bearing.toDouble(), 30.0)
            mapView.mapOrientation = -compass.resolveBearing(bearing)
            mapView.controller.setCenter(cameraTarget)
        }
        compass.syncButtonRotation()
        mapView.invalidate()
    }

    private fun clearPolyline() {
        polyline?.let { mapView.overlays.remove(it) }
        polyline = null
        pathPoints.clear()
        motionAnimator.reset()
        if (::mapView.isInitialized) mapView.invalidate()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_navigate, container, false)

        questApi = (activity as MainActivity).questService
        mapView = view.findViewById(R.id.mapView)
        btnRecord = view.findViewById(R.id.btnRecord)
        btnOpenLibrary = view.findViewById(R.id.btnOpenLibrary)
        btnPlanRoute = view.findViewById(R.id.btnPlanRoute)
        rideStats = RideStatsCard(view.findViewById(R.id.rideStats))
        setupMap(view)
        // for the save dialog when offline
        val appContext = requireContext().applicationContext
        val profileApi = (activity as MainActivity).profileService
        apiCallCoroutine.launch { Vehicle.loadProfile(appContext, profileApi) }
        btnRecord.setText(if (TrackRecordingService.isRecording) R.string.record_stop else R.string.record_start)
        btnRecord.setOnClickListener {
            if (TrackRecordingService.isRecording) stopRecording() else startRecording()
        }
        btnOpenLibrary.setOnClickListener {
            findNavController().navigate(R.id.savedTracks)
        }
        btnPlanRoute.setOnClickListener {
            findNavController().navigate(R.id.trackPlanner)
        }
        return view
    }

    private fun setupMap(view: View) {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
        val mapStyle = getSavedMapStyle(requireContext())
        mapView.setTileSource(tileSourceFor(requireContext(), mapStyle))
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(DEFAULT_MAP_ZOOM)

        MapCredit(this, view.findViewById(R.id.mapCredit), mapView)

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
            btnZoomOut = view.findViewById(R.id.btnZoomOut)
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

        if (requireContext().hasLocationPermission()) {
            enableMyLocation()
        } else {
            requestLocationWithRationale(requestPermissionLauncher)
        }
    }

    private fun startRecording() {
        if (!requireContext().hasLocationPermission()) {
            requestLocationWithRationale(requestPermissionLauncher)
            return
        }
        clearPolyline()
        stopIdleSpeedUpdates()
        TrackRecordingService.isRecording = true
        btnRecord.setText(R.string.record_stop)
        btnOpenLibrary.visibility = View.GONE
        btnPlanRoute.visibility = View.GONE
        compass.setActive(true)
        myLocationOverlay.followsAnimation = true
        mapView.controller.setZoom(TRACKING_ZOOM_LEVEL)

        val intent = Intent(requireContext(), TrackRecordingService::class.java)
        requireContext().startForegroundService(intent)
    }

    private fun stopRecording() {
        TrackRecordingService.isRecording = false
        btnRecord.setText(R.string.record_start)
        btnOpenLibrary.visibility = View.VISIBLE
        btnPlanRoute.visibility = View.VISIBLE
        compass.setActive(false)
        myLocationOverlay.followsAnimation = false

        val intent = Intent(requireContext(), TrackRecordingService::class.java)
        requireContext().stopService(intent)
        startIdleSpeedUpdates()
    }

    private fun saveTrack(trackName: String, vehicle: Vehicle, done: (String?) -> Unit) {
        val file = File(requireContext().filesDir, "track.json")
        if (!file.exists()) {
            done(getString(R.string.record_file_missing))
            return
        }
        val appContext = requireContext().applicationContext
        val failedText = getString(R.string.track_save_failed)
        val api = (requireActivity() as MainActivity).trackService

        apiCallCoroutine.launch {
            var trackId: Int? = null
            val error = try {
                val createResponse = api.createTrack(NewTrackRequest(trackName, vehicle.id))
                trackId = createResponse.body()?.id
                if (!createResponse.isSuccessful || trackId == null) {
                    createResponse.errorBody()?.string()?.takeIf { it.isNotBlank() } ?: failedText
                } else {
                    val requestFile = file.asRequestBody("application/json".toMediaTypeOrNull())
                    val body = MultipartBody.Part.createFormData("file", file.name, requestFile)
                    val uploadResponse = api.uploadTrack(body, trackId)
                    if (uploadResponse.isSuccessful) null
                    else uploadResponse.errorBody()?.string()?.takeIf { it.isNotBlank() } ?: failedText
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
                failedText
            }
            if (error != null) {
                // delete the track if its file failed, saving again recreates it
                trackId?.let { id -> runCatching { api.deleteTrack(id) } }
                withContext(Dispatchers.Main) { done(error) }
                return@launch
            }
            Vehicle.saveLastUsed(appContext, vehicle)

            // the track is saved, only the travel stats would be missing
            val travelError = try {
                questApi.increaseQuest(IncreaseQuestRequest(QuestType.RECORD_TRACK.toString(), 1, vehicle.id))
                val travelData = TrackRecordingService.getLastTravelData()?.copy(id = trackId!!, vehicle = vehicle.id)
                    ?: NewTravelRequest(trackId!!, 0.0f, 0.0f, 0.0f, 0.0f, vehicle.id)
                val travelResponse = api.createTravel(travelData)
                if (travelResponse.isSuccessful) {
                    questApi.increaseQuest(
                        IncreaseQuestRequest(QuestType.TRAVEL_DISTANCE.toString(), travelData.distance.toInt(), vehicle.id)
                    )
                    travelResponse.body()?.id?.let { travelId ->
                        val travelRequestFile = file.asRequestBody("application/json".toMediaTypeOrNull())
                        val travelBody = MultipartBody.Part.createFormData("file", file.name, travelRequestFile)
                        api.uploadTravelFile(travelBody, travelId)
                    }
                    null
                } else {
                    travelResponse.errorBody()?.string() ?: appContext.getString(R.string.error_generic)
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
                appContext.getString(R.string.record_travel_failed)
            }
            file.delete()
            withContext(Dispatchers.Main) {
                done(null)
                Toast.makeText(appContext, travelError ?: appContext.getString(R.string.record_saved), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteRecordedFile() {
        File(requireContext().filesDir, "track.json").delete()
    }

    override fun onResume() {
        super.onResume()
        if (TrackNavigationService.isNavigating) {
            val trackId = TrackNavigationService.trackId
            val action = NavigateDirections.actionNavigateToTrackNavigation(trackId)
            findNavController().navigate(action)
        }
        mapView.onResume()
        compass.setActive(TrackRecordingService.isRecording)
        myLocationOverlay.followsAnimation = TrackRecordingService.isRecording
        if (TrackRecordingService.isRecording) {
            mapView.controller.setZoom(TRACKING_ZOOM_LEVEL)
            val recordedPoints = TrackRecordingService.pathPoints
            if (recordedPoints.isNotEmpty()) {
                pathPoints.clear()
                pathPoints.addAll(recordedPoints.map {
                    GeoPoint(
                        it.first.latitude,
                        it.first.longitude
                    )
                })
                motionAnimator.snapTo(pathPoints.last(), 0f)
                redrawPolyline()
            }
            btnOpenLibrary.visibility = View.GONE
            btnPlanRoute.visibility = View.GONE
        } else {
            btnOpenLibrary.visibility = View.VISIBLE
            btnPlanRoute.visibility = View.VISIBLE
            if (!TrackNavigationService.isNavigating) startIdleSpeedUpdates()
        }
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(
                trackUpdateReceiver,
                IntentFilter("com.example.trackmate.TRACK_UPDATE")
            )
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(
                trackFileSavedReceiver,
                IntentFilter("com.example.trackmate.TRACK_FILE_SAVED")
            )
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        stopIdleSpeedUpdates()
        motionAnimator.cancel()
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(trackUpdateReceiver)
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(trackFileSavedReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mapView.onDetach()
    }
}
