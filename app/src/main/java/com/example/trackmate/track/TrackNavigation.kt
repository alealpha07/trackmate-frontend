package com.example.trackmate.track

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
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.navArgs
import com.example.trackmate.MainActivity
import com.example.trackmate.R
import com.example.trackmate.api.IncreaseQuestRequest
import com.example.trackmate.api.InstructionsService
import com.example.trackmate.api.ProfileService
import com.example.trackmate.api.QuestService
import com.example.trackmate.api.QuestType
import com.example.trackmate.api.Track
import com.example.trackmate.api.TrackInstructions
import com.example.trackmate.api.TrackService
import com.example.trackmate.api.TravelStatistics
import com.example.trackmate.map.AnimatedMyLocationOverlay
import com.example.trackmate.map.DEFAULT_MAP_ZOOM
import com.example.trackmate.map.DRAG_RESUME_FOLLOW_TIME
import com.example.trackmate.map.FollowZoom
import com.example.trackmate.map.MapCompassController
import com.example.trackmate.map.MapCredit
import com.example.trackmate.map.MapMotionAnimator
import com.example.trackmate.map.applyPrimaryLocationIcons
import com.example.trackmate.map.computeOffset
import com.example.trackmate.map.createLetterMarkerIcon
import com.example.trackmate.map.followLookAhead
import com.example.trackmate.map.getSavedMapStyle
import com.example.trackmate.map.setupMapControls
import com.example.trackmate.map.tileSourceFor
import com.example.trackmate.navigate.RideStatsCard
import com.example.trackmate.util.Vehicle
import com.example.trackmate.util.formatTime
import com.example.trackmate.util.showTrackVehicle
import com.google.android.gms.location.*
import com.google.android.material.button.MaterialButtonToggleGroup
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
import com.example.trackmate.util.hasLocationPermission
import com.example.trackmate.util.locationGranted
import com.example.trackmate.util.requestLocationWithRationale
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

class TrackNavigation : Fragment() {

    private lateinit var mapView: MapView
    private lateinit var myLocationOverlay: AnimatedMyLocationOverlay
    private lateinit var compass: MapCompassController
    private lateinit var btnRecord: Button
    private lateinit var txtTrackName: TextView
    private lateinit var txtTrackVehicle: TextView
    private lateinit var txtTrackLength: TextView
    private lateinit var txtUserBest: TextView
    private lateinit var txtUserBestAvg: TextView
    private lateinit var txtUserBestSpd: TextView
    private lateinit var statsLayout: LinearLayout
    private lateinit var rideStats: RideStatsCard
    private lateinit var banner: NavigationBanner
    private lateinit var btnMoreDetails: Button
    private lateinit var txtVehicleMissing: TextView
    private var trackVehicle: Vehicle? = null
    private var travelVehicle: Vehicle? = null
    // with more than one, Start asks which
    private var offeredVehicles = emptyList<Vehicle>()

    private var referencePolyline: Polyline? = null
    private var passedPolyline: Polyline? = null
    private val pathPoints = mutableListOf<GeoPoint>()
    private val referencePoints = mutableListOf<GeoPoint>()

    private var instructions: TrackInstructions? = null
    private var instructionsLoading = false
    private var instructionsJob: Job? = null
    private var lastAlong = 0.0
    private var lastOffTrack = false
    private var lastDistanceToTrack = 0.0
    private var lastSpeed = 0f
    private var navigationFinishHandled = false
    private var userIsInteracting = false
    private var needsBearingSnap = false
    private var isNavigatingUiActive = false

    private val handler = Handler(Looper.getMainLooper())
    private val resumeFollowRunnable = Runnable { userIsInteracting = false }

    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)
    private lateinit var api: TrackService
    private lateinit var questApi: QuestService
    private lateinit var profileApi: ProfileService
    private lateinit var instructionsApi: InstructionsService
    private val args: TrackNavigationArgs by navArgs()

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var idleSpeedCallback: LocationCallback? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.locationGranted()) {
                enableMyLocation()
                if (!TrackNavigationService.isNavigating) startIdleSpeedUpdates()
            } else {
                Toast.makeText(requireContext(), R.string.location_denied, Toast.LENGTH_SHORT)
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

    // live speed while not navigating
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


    // smooth map motion between GPS fixes
    private var motionAnimator = createMotionAnimator()
    private lateinit var followZoom: FollowZoom

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
            val segment = intent.getIntExtra("segment", -1)
            val projectedLat = intent.getDoubleExtra("projectedLat", Double.NaN)
            val projectedLng = intent.getDoubleExtra("projectedLng", Double.NaN)
            val offTrack = intent.getBooleanExtra("offTrack", false)
            val bearing = intent.getFloatExtra("bearing", Float.NaN)
            val speed = intent.getFloatExtra("speed", 0f)

            followZoom.onSpeed(speed)
            rideStats.show(distance, duration, speed)
            if (intent.hasExtra("along")) {
                lastAlong = intent.getFloatExtra("along", 0f).toDouble()
                lastOffTrack = offTrack
                lastDistanceToTrack = intent.getFloatExtra("distanceToTrack", 0f).toDouble()
            }
            lastSpeed = speed
            if (isNavigatingUiActive) showDirections()

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

            if (segment >= 0 && !projectedLat.isNaN() && referencePoints.isNotEmpty()) {
                val passedPoints = referencePoints.subList(0, segment.coerceAtMost(referencePoints.size - 1) + 1)
                passedPolyline?.setPoints(passedPoints + GeoPoint(projectedLat, projectedLng))
                mapView.invalidate()
            }

            if (isFinished && !navigationFinishHandled) {
                navigationFinishHandled = true
                stopNavigation(true)
            }
        }
    }

    private fun showDirections() {
        banner.show(
            bannerState(instructionsLoading, instructions, lastOffTrack, lastDistanceToTrack, lastAlong, lastSpeed),
            instructions
        )
        val limits = instructions?.speedLimits.orEmpty()
        rideStats.showSpeedLimit(if (lastOffTrack) null else speedLimitAt(limits, lastAlong))
    }

    private fun loadInstructions(vehicleId: String?) {
        instructionsJob?.cancel()
        instructions = null
        TrackNavigationService.instructions = null
        instructionsLoading = true
        showDirections()
        instructionsJob = lifecycleScope.launch {
            val result = try {
                instructionsApi.getInstructions(args.trackId, vehicleId).takeIf { it.isSuccessful }?.body()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
                null
            }
            instructionsLoading = false
            instructions = result
            TrackNavigationService.instructions = result
            if (isNavigatingUiActive) showDirections()
        }
    }

    private fun clearInstructions() {
        instructionsJob?.cancel()
        instructionsJob = null
        instructions = null
        TrackNavigationService.instructions = null
        instructionsLoading = false
        lastAlong = 0.0
        lastOffTrack = false
        banner.hide()
    }

    private fun onLocationFrame(point: GeoPoint, bearing: Float) {
        if (!::mapView.isInitialized) return
        // cursor follows the animated camera
        myLocationOverlay.setAnimatedPosition(point, bearing)

        if (!userIsInteracting) {
            // look-ahead follows the direction of travel
            followZoom.step()
            val cameraTarget = computeOffset(point, bearing.toDouble(), followLookAhead(mapView.zoomLevelDouble))
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
        txtTrackVehicle = view.findViewById(R.id.txtTrackVehicle)
        txtTrackLength = view.findViewById(R.id.txtTrackLength)
        txtUserBest = view.findViewById(R.id.txtUserBest)
        txtUserBestAvg = view.findViewById(R.id.txtUserBestAvg)
        txtUserBestSpd = view.findViewById(R.id.txtUserBestSpd)
        statsLayout = view.findViewById(R.id.statsLayout)
        rideStats = RideStatsCard(view.findViewById(R.id.rideStats))
        banner = NavigationBanner(view.findViewById(R.id.navBanner)) { loadInstructions(TrackNavigationService.vehicleId) }
        btnMoreDetails = view.findViewById(R.id.btnMoreDetails)
        txtVehicleMissing = view.findViewById(R.id.txtVehicleMissing)

        api = (requireActivity() as MainActivity).trackService
        questApi = (requireActivity() as MainActivity).questService
        profileApi = (requireActivity() as MainActivity).profileService
        instructionsApi = (requireActivity() as MainActivity).instructionsService

        setupMap(view)

        btnRecord.setText(
            if (TrackNavigationService.isNavigating) R.string.navigation_cancel else R.string.navigation_start
        )
        btnRecord.setOnClickListener { if (TrackNavigationService.isNavigating) stopNavigation() else startNavigation() }
        btnMoreDetails.setOnClickListener{
            val action = TrackNavigationDirections.actionTrackNavigationToTravelGraph(args.trackId)
            findNavController().navigate(action)
        }
        return view
    }

    private fun setupMap(view: View) {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())
        // keep overlays when the view is detached by forceMapViewRebind
        mapView.setDestroyMode(false)
        val mapStyle = getSavedMapStyle(requireContext())
        mapView.setTileSource(tileSourceFor(requireContext(), mapStyle))
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(DEFAULT_MAP_ZOOM)
        followZoom = FollowZoom(mapView)

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

        loadTrackData()
    }


    private fun loadTrackData() {
        apiCallCoroutine.launch {
            try {
                var track: Track? = null
                val fileResponse = api.getTrackFile(args.trackId)
                if (fileResponse.code() == 403) {
                    withContext(Dispatchers.Main) { leaveUnavailableTrack() }
                    return@launch
                }
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
                    val vehicle = Vehicle.fromId(details.vehicle)
                    val profile = Vehicle.loadProfile(requireContext().applicationContext, profileApi)
                    withContext(Dispatchers.Main) {
                        txtTrackName.text = details.name
                        txtTrackVehicle.showTrackVehicle(vehicle)
                        trackVehicle = vehicle
                        showBest(details.userBest)
                        vehicle?.let { bindTravelVehicle(it, profile) }
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

    private fun bindTravelVehicle(trackVehicle: Vehicle, profile: List<Vehicle>) {
        offeredVehicles = trackVehicle.classVehicles.filter { it in profile }
        val lastUsed = Vehicle.lastUsed(requireContext())
        travelVehicle = offeredVehicles.firstOrNull { it == lastUsed } ?: offeredVehicles.firstOrNull()

        txtVehicleMissing.text = getString(R.string.navigate_vehicle_missing, trackVehicle.classLabel(requireContext()))
        txtVehicleMissing.visibility = if (offeredVehicles.isEmpty()) View.VISIBLE else View.GONE
        updateStartButton()
    }

    private fun chooseTravelVehicle(onChosen: () -> Unit) {
        if (offeredVehicles.size < 2) {
            onChosen()
            return
        }
        val buttons = mapOf(Vehicle.CAR to R.id.btnTravelCar, Vehicle.MOTORCYCLE to R.id.btnTravelMotorcycle)
        val dialogView = layoutInflater.inflate(R.layout.dialog_navigate_by, null)
        val toggle = dialogView.findViewById<MaterialButtonToggleGroup>(R.id.travelVehicleToggle)
        buttons[travelVehicle]?.let { toggle.check(it) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.navigate_by)
            .setView(dialogView)
            .setPositiveButton(R.string.navigate_start) { _, _ ->
                travelVehicle = buttons.entries.first { it.value == toggle.checkedButtonId }.key
                onChosen()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun updateStartButton() {
        // without details the server uses the track's vehicle
        btnRecord.isEnabled = TrackNavigationService.isNavigating ||
            (referencePoints.isNotEmpty() && (trackVehicle == null || travelVehicle != null))
    }

    private fun showBest(best: TravelStatistics?) {
        val speed = { value: Float -> getString(R.string.speed_kmh, "%.2f".format(value)) }
        txtUserBest.text = getString(R.string.navigation_best_time, best?.let { formatTime(it.time) } ?: "-")
        txtUserBestAvg.text = getString(R.string.navigation_best_avg_speed, best?.let { speed(it.averageSpeed) } ?: "-")
        txtUserBestSpd.text = getString(R.string.navigation_best_speed, best?.let { speed(it.maxSpeed) } ?: "-")
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
            title = getString(R.string.marker_start)
            icon = createLetterMarkerIcon(requireContext(), 'A')
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
        mapView.overlays.add(startMarker)

        val finishMarker = Marker(mapView).apply {
            position = points.last()
            title = getString(R.string.marker_finish)
            icon = createLetterMarkerIcon(requireContext(), 'B')
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
        mapView.overlays.add(finishMarker)

        // keep the location cursor on top
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
        txtTrackLength.text = getString(R.string.track_length, "%.2f".format(distance / 1000))
        updateStartButton()
    }

    // map rotation stops applying after the app resumes, re-adding the view fixes it
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
                btnRecord.setText(R.string.navigation_cancel)
                followZoom.reset()
                needsBearingSnap = true
                isNavigatingUiActive = true
                compass.setActive(true)
                myLocationOverlay.followsAnimation = true
                if (instructions == null) instructions = TrackNavigationService.instructions
                if (instructions == null && instructionsJob?.isActive != true) {
                    loadInstructions(TrackNavigationService.vehicleId)
                } else {
                    showDirections()
                }
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
        // a running navigation already has its own copy
        File(requireContext().filesDir, "navigation.json").delete()
    }

    private fun startNavigation() {
        if (referencePoints.isEmpty()) {
            Toast.makeText(requireContext(), R.string.navigation_track_loading, Toast.LENGTH_SHORT)
                .show()
            return
        }

        if (!requireContext().hasLocationPermission()) {
            requestLocationWithRationale(requestPermissionLauncher)
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location == null) {
                Toast.makeText(
                    requireContext(),
                    R.string.navigation_no_location,
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
                    .setTitle(R.string.navigation_too_far_title)
                    .setMessage(getString(R.string.navigation_too_far_message, dist.roundToInt(), OFF_TRACK_THRESHOLD.roundToInt()))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@addOnSuccessListener
            }

            chooseTravelVehicle { beginNavigation() }
        }.addOnFailureListener {
            Toast.makeText(
                requireContext(),
                R.string.navigation_no_location,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun beginNavigation() {
        resetTravelledPath()
        navigationFinishHandled = false
        isNavigatingUiActive = true
        stopIdleSpeedUpdates()
        TrackNavigationService.isNavigating = true
        statsLayout.visibility = View.GONE
        btnRecord.setText(R.string.navigation_cancel)
        compass.setActive(true)
        myLocationOverlay.followsAnimation = true
        followZoom.reset(clearSpeed = true)

        val sourceFile = File(requireContext().filesDir, "navigation.json")
        val destinationFile = File(requireContext().filesDir, "navigation_track.json")
        sourceFile.copyTo(destinationFile, overwrite = true)

        travelVehicle?.let { Vehicle.saveLastUsed(requireContext(), it) }
        val intent = Intent(requireContext(), TrackNavigationService::class.java)
        intent.putExtra("trackId", args.trackId)
        intent.putExtra("vehicle", travelVehicle?.id)
        requireContext().startForegroundService(intent)
        loadInstructions(travelVehicle?.id)
    }


    private fun endNavigation() {
        TrackNavigationService.isNavigating = false
        isNavigatingUiActive = false
        btnRecord.setText(R.string.navigation_start)
        compass.setActive(false)
        myLocationOverlay.followsAnimation = false

        rideStats.reset()
        clearInstructions()
        statsLayout.visibility = View.VISIBLE

        val intent = Intent(requireContext(), TrackNavigationService::class.java)
        requireContext().stopService(intent)
        File(requireContext().filesDir, "navigation_track.json").delete()
        startIdleSpeedUpdates()
    }

    private fun stopNavigation(isFinished: Boolean = false) {
        if (isFinished) {
            saveTravel()
            endNavigation()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.navigation_completed)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.navigation_cancel)
                .setMessage(R.string.navigation_cancel_message)
                .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                    endNavigation()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    private fun saveTravel() {
        val travelData = TrackNavigationService.getLastNavigationData()
        if (travelData == null) {
            Toast.makeText(requireContext(), R.string.navigation_no_travel, Toast.LENGTH_SHORT).show()
            return
        }
        val questVehicle = travelData.vehicle ?: trackVehicle?.id

        apiCallCoroutine.launch {
            try {
                val travelResponse = api.createTravel(travelData)
                if (travelResponse.isSuccessful) {
                    if (questVehicle != null) {
                        questApi.increaseQuest(
                            IncreaseQuestRequest(
                                QuestType.TRAVEL_DISTANCE.toString(),
                                travelData.distance.toInt(),
                                questVehicle
                            )
                        )
                        questApi.increaseQuest(
                            IncreaseQuestRequest(
                                QuestType.NAVIGATE_TRACK.toString(),
                                1,
                                questVehicle
                            )
                        )
                    }
                    travelResponse.body()?.id?.let { travelId -> uploadTravelPoints(travelId) }
                }
                withContext(Dispatchers.Main) {
                    if (travelResponse.isSuccessful) {
                        Toast.makeText(
                            requireContext(),
                            R.string.navigation_travel_saved,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            travelResponse.errorBody()?.string() ?: getString(R.string.navigation_travel_failed),
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
        } finally {
            context?.let { File(it.filesDir, "travel_points.json").delete() }
        }
    }
}

// someone else's track without a post, for example after the post was deleted
internal fun Fragment.leaveUnavailableTrack() {
    if (!isAdded) return
    Toast.makeText(requireContext(), R.string.track_unavailable, Toast.LENGTH_LONG).show()
    findNavController().popBackStack()
}
