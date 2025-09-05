package com.example.trackmate

import android.Manifest
import android.app.AlertDialog
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
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import com.example.trackmate.services.IncreaseQuestRequest
import com.example.trackmate.services.NewTrackRequest
import com.example.trackmate.services.NewTravelRequest
import com.example.trackmate.services.QuestService
import com.example.trackmate.services.QuestType
import com.example.trackmate.services.TrackNavigationService
import com.example.trackmate.services.TrackRecordingService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

const val DRAG_RESUME_FOLLOW_TIME: Long = 5000

class Navigate : Fragment(), OnMapReadyCallback {

    private lateinit var mapView: MapView
    private lateinit var btnRecord: Button
    private lateinit var btnOpenLibrary: Button
    private lateinit var googleMap: GoogleMap
    private lateinit var txtDistance: TextView
    private lateinit var txtDuration: TextView
    private lateinit var txtCurrentSpeed: TextView
    private lateinit var questApi: QuestService
    private var polyline: Polyline? = null
    private val pathPoints = mutableListOf<LatLng>()
    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)
    private var userIsInteracting = false
    private val handler = Handler(Looper.getMainLooper())
    private val resumeFollowRunnable = Runnable {
        userIsInteracting = false
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                enableMyLocation()
            } else {
                Toast.makeText(requireContext(), "Location permission denied", Toast.LENGTH_SHORT)
                    .show()
            }
        }

    private fun enableMyLocation() {
        if (!::googleMap.isInitialized) return

        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            googleMap.isMyLocationEnabled = true

            val fusedLocationClient =
                LocationServices.getFusedLocationProviderClient(requireContext())
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val currentLatLng = LatLng(location.latitude, location.longitude)
                    googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 16f))
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

            txtCurrentSpeed.text = "Speed: ${String.format("%.2f", speed)} km/h"
            txtDistance.text = "Distance: ${String.format("%.2f", distance)} km"
            txtDuration.text = "Duration: ${formatTime((duration / 1000).toFloat())}"

            val lat = intent.getDoubleExtra("lat", Double.NaN)
            val lng = intent.getDoubleExtra("lng", Double.NaN)

            if (!lat.isNaN() && !lng.isNaN()) {
                val newPoint = LatLng(lat, lng)
                pathPoints.add(newPoint)
                updatePolyline()
            }
        }
    }

    private val trackFileSavedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val dialogView = layoutInflater.inflate(R.layout.dialog_track_name, null)
            val editText = dialogView.findViewById<EditText>(R.id.editTrackName)

            AlertDialog.Builder(requireContext())
                .setTitle("Save Track")
                .setMessage("Do you want to save this track?")
                .setView(dialogView)
                .setPositiveButton("Save") { _, _ ->
                    saveTrack(editText.text.toString())
                }
                .setNegativeButton("Discard", null)
                .show()
        }
    }

    private fun updatePolyline() {
        if (!::googleMap.isInitialized) return

        if (polyline == null) {
            val polylineOptions = PolylineOptions()
                .color(
                    ContextCompat.getColor(
                        requireContext(),
                        com.google.android.material.R.color.design_default_color_primary
                    )
                )
                .width(10f)
                .addAll(pathPoints)
            polyline = googleMap.addPolyline(polylineOptions)
        } else {
            polyline?.points = pathPoints
        }

        if (!userIsInteracting && pathPoints.isNotEmpty()) {
            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pathPoints.last(), 18f))
        }
    }

    private fun clearPolyline() {
        polyline?.remove()
        polyline = null
        pathPoints.clear()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_navigate, container, false)

        questApi = (activity as MainActivity).questService
        mapView = view.findViewById(R.id.mapView)
        btnRecord = view.findViewById(R.id.btnRecord)
        btnOpenLibrary = view.findViewById(R.id.btnOpenLibrary)
        txtCurrentSpeed = view.findViewById(R.id.txtCurrentSpeed)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)
        btnRecord.text =
            if (TrackRecordingService.isRecording) "Stop Recording" else "Start Recording"
        btnRecord.setOnClickListener {
            if (TrackRecordingService.isRecording) stopRecording() else startRecording()
        }
        btnOpenLibrary.setOnClickListener {
            findNavController().navigate(R.id.savedTracks)
        }
        txtDistance = view.findViewById(R.id.txtDistance)
        txtDuration = view.findViewById(R.id.txtDuration)
        return view
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.isZoomControlsEnabled = true
        googleMap.uiSettings.isMyLocationButtonEnabled = true

        googleMap.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                userIsInteracting = true
                handler.removeCallbacks(resumeFollowRunnable)
                handler.postDelayed(resumeFollowRunnable, DRAG_RESUME_FOLLOW_TIME)
            }
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
    }

    private fun startRecording() {
        clearPolyline()
        TrackRecordingService.isRecording = true
        btnRecord.text = "Stop Recording"
        btnOpenLibrary.visibility = View.GONE

        val intent = Intent(requireContext(), TrackRecordingService::class.java)
        requireContext().startForegroundService(intent)
    }

    private fun stopRecording() {
        TrackRecordingService.isRecording = false
        btnRecord.text = "Start Recording"
        btnOpenLibrary.visibility = View.VISIBLE

        val intent = Intent(requireContext(), TrackRecordingService::class.java)
        requireContext().stopService(intent)
    }

    private fun saveTrack(trackName: String) {
        val file = File(requireContext().filesDir, "track.json")
        if (!file.exists()) {
            Toast.makeText(requireContext(), "No track file found", Toast.LENGTH_SHORT).show()
            return
        }

        val requestFile = file.asRequestBody("application/json".toMediaTypeOrNull())
        val body = MultipartBody.Part.createFormData("file", file.name, requestFile)

        val api = (requireActivity() as MainActivity).trackService

        apiCallCoroutine.launch {
            try {
                val createResponse = api.createTrack(NewTrackRequest(trackName))
                if (!createResponse.isSuccessful || createResponse.body() == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(),
                            createResponse.errorBody()?.string() ?: "Error",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    Log.d("API-CALL", createResponse.message())
                    return@launch
                } else {
                    questApi.increaseQuest(
                        IncreaseQuestRequest(
                            QuestType.RECORD_TRACK.toString(),
                            1
                        )
                    )
                }
                val newTrack = createResponse.body()!!

                val uploadResponse = api.uploadTrack(body, newTrack.id)
                if (!uploadResponse.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(),
                            uploadResponse.errorBody()?.string() ?: "Error",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                var travelData = TrackRecordingService.getLastTravelData()?.copy(id = newTrack.id)
                if (travelData == null) {
                    travelData = NewTravelRequest(newTrack.id, 0.0f, 0.0f, 0.0f, 0.0f)
                }
                val travelResponse = api.createTravel(travelData)
                if (travelResponse.isSuccessful) {
                    questApi.increaseQuest(
                        IncreaseQuestRequest(
                            QuestType.TRAVEL_DISTANCE.toString(),
                            travelData.distance.toInt()
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    if (travelResponse.isSuccessful) {
                        Toast.makeText(
                            requireContext(),
                            travelResponse.body()?.string() ?: "Success",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            travelResponse.errorBody()?.string() ?: "Error",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (TrackNavigationService.isNavigating) {
            val trackId = TrackNavigationService.trackId
            val action = NavigateDirections.actionNavigateToTrackNavigation(trackId)
            findNavController().navigate(action)
        }
        mapView.onResume()
        if (TrackRecordingService.isRecording) {
            val recordedPoints = TrackRecordingService.pathPoints
            if (recordedPoints.isNotEmpty()) {
                pathPoints.clear()
                pathPoints.addAll(recordedPoints.map {
                    LatLng(
                        it.first.latitude,
                        it.first.longitude
                    )
                })
                updatePolyline()
            }
            btnOpenLibrary.visibility = View.GONE
        } else {
            btnOpenLibrary.visibility = View.VISIBLE
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
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(trackUpdateReceiver)
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(trackFileSavedReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (this::mapView.isInitialized) {
            mapView.onSaveInstanceState(outState)
        }
    }
}
