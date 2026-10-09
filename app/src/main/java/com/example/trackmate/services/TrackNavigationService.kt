package com.example.trackmate.services

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.trackmate.R
import com.google.android.gms.location.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

const val POINT_VISIT_THRESHOLD = 30f
const val OFF_TRACK_THRESHOLD = 50f
const val LENGTH_SIMILARITY_RATIO = 0.15f
const val REQUIRED_MIDDLE_POINTS_RATIO = 0.5f

const val MAX_ACCURACY_METERS = 25f
const val MIN_RELIABLE_DT_SECONDS = 1f
const val MAX_PLAUSIBLE_SPEED_MPS = 138.89f // ~500 km/h

class TrackNavigationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private val navigationPath = mutableListOf<Location>()
    // Set once the track file is loaded, before location updates start
    @Volatile
    private var progress: TrackProgress? = null
    private var trackedFixes = 0
    private var closeFixes = 0
    private var startTime: Long = 0
    private var navigationCompleted = false
    private lateinit var workerThread: HandlerThread
    private lateinit var updateHandler: Handler
    private lateinit var updateRunnable: Runnable

    companion object {
        var isNavigating = false
        private var lastNavigationData: NewTravelRequest? = null
        private var lastNavigationPoints: List<TrackPoint>? = null
        var trackId: Int = -1
        // The travel's vehicle, chosen before Start; null lets the server use the track's
        var vehicleId: String? = null
        fun getLastNavigationData(): NewTravelRequest? = lastNavigationData
        fun getLastNavigationPoints(): List<TrackPoint>? = lastNavigationPoints
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        workerThread = HandlerThread("NavServiceThread")
        workerThread.start()
        updateHandler = Handler(workerThread.looper)

        updateRunnable = object : Runnable {
            override fun run() {
                if (navigationCompleted) return
                navigationPath.lastOrNull()?.let { sendNavigationUpdate(it) }
                updateHandler.postDelayed(this, 500L)
            }
        }
        updateHandler.post(updateRunnable)
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        trackId = intent?.getIntExtra("trackId", -1) ?: -1
        vehicleId = intent?.getStringExtra("vehicle")

        startForegroundService()

        CoroutineScope(Dispatchers.IO).launch {
            progress = loadReferenceTrack()
            if (progress != null) {
                startLocationUpdates()
                startTime = System.nanoTime()
            } else {
                Log.e("NAV_SERVICE", "Reference track empty, stopping navigation service")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (navigationCompleted) return
                val location = result.lastLocation ?: return
                if (navigationPath.lastOrNull()
                        ?.let { it.latitude == location.latitude && it.longitude == location.longitude } == true
                ) return
                navigationPath.add(location)
                updateHandler.post {
                    checkNavigationProgress(location)
                    sendNavigationUpdate(location)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback!!,
            workerThread.looper
        )
    }

    private fun checkNavigationProgress(currentLocation: Location) {
        val progress = progress ?: return
        progress.update(currentLocation.latitude, currentLocation.longitude)
        trackedFixes++
        if (!progress.offTrack) closeFixes++
        checkCompletionCriteria(progress)
    }

    private fun loadReferenceTrack(): TrackProgress? {
        val file = File(filesDir, "navigation_track.json")
        if (!file.exists()) return null

        return try {
            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val track = moshi.adapter(Track::class.java).fromJson(file.readText()) ?: return null
            if (track.track.isEmpty()) null
            else TrackProgress(track.track.map { TrackProgress.Point(it.latitude, it.longitude) })
        } catch (e: Exception) {
            Log.e("NAV_SERVICE", "Error loading track: ${e.message}")
            null
        }
    }

    private fun startForegroundService() {
        val channelId = "track_navigation_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                "Track Navigation",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Navigating route" })

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("TrackMate")
            .setContentText("Navigation in progress...")
            .setSmallIcon(R.drawable.baseline_navigation_24)
            .setOngoing(true)
            .build()
        startForeground(2, notification)
    }

    private fun currentSpeedMps(location: Location, prev: Location?): Float {
        if (location.hasSpeed() && location.speed <= MAX_PLAUSIBLE_SPEED_MPS) return location.speed
        if (prev == null) return 0f

        val dt = (location.time - prev.time) / 1000f
        val accurateEnough = (!prev.hasAccuracy() || prev.accuracy <= MAX_ACCURACY_METERS) &&
                (!location.hasAccuracy() || location.accuracy <= MAX_ACCURACY_METERS)
        if (!accurateEnough || dt < MIN_RELIABLE_DT_SECONDS) return 0f

        val speed = prev.distanceTo(location) / dt
        return if (speed <= MAX_PLAUSIBLE_SPEED_MPS) speed else 0f
    }

    private fun sendNavigationUpdate(location: Location) {
        val prev = navigationPath.getOrNull(navigationPath.size - 2)
        val speedKmh = currentSpeedMps(location, prev) * 3.6f

        val intent = Intent("com.example.trackmate.NAVIGATION_UPDATE").apply {
            putExtra("lat", location.latitude)
            putExtra("lng", location.longitude)
            putExtra("distance", calculateDistanceMeters() / 1000f)
            putExtra("duration", (System.nanoTime() - startTime) / 1_000_000)
            progress?.let {
                putExtra("segment", it.segment)
                putExtra("projectedLat", it.projected.latitude)
                putExtra("projectedLng", it.projected.longitude)
                putExtra("along", it.along.toFloat())
                putExtra("remaining", it.remaining.toFloat())
                putExtra("offTrack", it.offTrack)
            }
            putExtra("isFinished", navigationCompleted)
            putExtra("speed", speedKmh)
            if (location.hasBearing()) {
                putExtra("bearing", location.bearing)
            }
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    private fun checkCompletionCriteria(progress: TrackProgress) {
        if (navigationCompleted || navigationPath.isEmpty()) return
        val endReached = progress.remaining <= POINT_VISIT_THRESHOLD &&
                progress.distanceToTrack <= POINT_VISIT_THRESHOLD
        val middleEnough = closeFixes.toFloat() / trackedFixes >= REQUIRED_MIDDLE_POINTS_RATIO
        val lengthSimilar =
            calculateDistanceMeters() >= progress.length * (1f - LENGTH_SIMILARITY_RATIO)

        if (endReached && lengthSimilar && middleEnough) {
            navigationCompleted = true
            isNavigating = false
            lastNavigationData = calculateTravelData()
            lastNavigationPoints = calculateTravelPoints()
            updateHandler.removeCallbacks(updateRunnable)
            stopSelf()
        }
    }

    private fun calculateDistanceMeters() =
        navigationPath.zipWithNext { a, b -> a.distanceTo(b) }.sum()

    private fun calculateTravelData(): NewTravelRequest? {
        if (navigationPath.size < 2) return null
        var distance = 0f
        var maxSpeed = 0f

        navigationPath.zipWithNext { prev, curr ->
            distance += prev.distanceTo(curr)
            maxSpeed = maxOf(maxSpeed, currentSpeedMps(curr, prev))
        }

        val durationSec = (navigationPath.last().time - navigationPath.first().time) / 1000f
        return NewTravelRequest(
            id = trackId,
            time = durationSec,
            averageSpeed = if (durationSec > 0) distance / durationSec * 3.6f else 0f,
            maxSpeed = maxSpeed * 3.6f,
            distance = distance / 1000f,
            vehicle = vehicleId
        )
    }

    private fun calculateTravelPoints(): List<TrackPoint> {
        return navigationPath.mapIndexed { index, location ->
            val prev = navigationPath.getOrNull(index - 1)
            TrackPoint(
                location.latitude,
                location.longitude,
                location.time,
                currentSpeedMps(location, prev) * 3.6f
            )
        }
    }

    override fun onDestroy() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }

        // Keep navigationPath access confined to the worker thread to avoid racing onDestroy.
        updateHandler.removeCallbacksAndMessages(null)
        updateHandler.post {
            if (!navigationCompleted) {
                lastNavigationData = calculateTravelData()
                lastNavigationPoints = calculateTravelPoints()
            }
            isNavigating = false
            trackId = -1
        }
        workerThread.quitSafely()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
