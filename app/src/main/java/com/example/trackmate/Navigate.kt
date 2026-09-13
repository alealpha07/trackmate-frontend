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

const val DRAG_RESUME_FOLLOW_TIME: Long = 5000

class Navigate : Fragment() {

    private lateinit var mapView: MapView
    private lateinit var btnRecord: Button
    private lateinit var btnOpenLibrary: Button
    private lateinit var myLocationOverlay: MyLocationNewOverlay
    private lateinit var txtDistance: TextView
    private lateinit var txtDuration: TextView
    private lateinit var txtCurrentSpeed: TextView
    private lateinit var questApi: QuestService
    private var polyline: Polyline? = null
    private val pathPoints = mutableListOf<GeoPoint>()
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

            txtCurrentSpeed.text = "Speed: ${String.format("%.2f", speed)} km/h"
            txtDistance.text = "Distance: ${String.format("%.2f", distance)} km"
            txtDuration.text = "Duration: ${formatTime((duration / 1000).toFloat())}"

            val lat = intent.getDoubleExtra("lat", Double.NaN)
            val lng = intent.getDoubleExtra("lng", Double.NaN)

            if (!lat.isNaN() && !lng.isNaN()) {
                val newPoint = GeoPoint(lat, lng)
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
        if (!::mapView.isInitialized) return

        if (polyline == null) {
            polyline = Polyline().apply {
                outlinePaint.color = ContextCompat.getColor(
                    requireContext(),
                    com.google.android.material.R.color.design_default_color_primary
                )
                outlinePaint.strokeWidth = 10f
            }
            mapView.overlays.add(polyline)
        }
        polyline?.setPoints(pathPoints)
        mapView.invalidate()

        if (!userIsInteracting && pathPoints.isNotEmpty()) {
            mapView.controller.animateTo(pathPoints.last(), 18.0, 1000L)
        }
    }

    private fun clearPolyline() {
        polyline?.let { mapView.overlays.remove(it) }
        polyline = null
        pathPoints.clear()
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
        txtCurrentSpeed = view.findViewById(R.id.txtCurrentSpeed)
        setupMap(view)
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

    private fun setupMap(view: View) {
        mapView.setTileSource(tileSourceFor(getSavedMapStyle(requireContext())))
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(16.0)

        mapView.overlays.add(buildCopyrightOverlay(requireContext()))

        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(requireContext()), mapView)
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
                    GeoPoint(
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
        mapView.onDetach()
    }
}
