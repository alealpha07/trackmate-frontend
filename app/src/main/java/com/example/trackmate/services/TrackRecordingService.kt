package com.example.trackmate.services

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.*
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.*
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@JsonClass(generateAdapter = true)
data class TrackPoint(
    @Json(name = "lat") val latitude: Double,
    @Json(name = "lng") val longitude: Double,
    val timestamp: Long,
    val speed: Float = 0f // km/h
)

@JsonClass(generateAdapter = true)
data class Track(
    val track: List<TrackPoint>
)

class TrackRecordingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private lateinit var updateHandler: Handler
    private lateinit var updateRunnable: Runnable
    private lateinit var updateThread: HandlerThread
    private var startTime: Long = 0

    companion object {
        var isRecording = false
        private var lastTravelData: NewTravelRequest? = null
        private val _pathPoints = mutableListOf<Pair<Location, Long>>()
        val pathPoints: List<Pair<Location, Long>>
            get() = _pathPoints.toList()
        fun getLastTravelData(): NewTravelRequest? = lastTravelData

        // Needed for more precise maximum speed calculation, need to discard bad quality points
        private const val MAX_ACCURACY_METERS = 25f
        private const val MIN_RELIABLE_DT_SECONDS = 1f
        private const val MAX_PLAUSIBLE_SPEED_MPS = 138.89f // ~500 km/h
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startForegroundService()
        startLocationUpdates()
        startTime = System.nanoTime()

        updateThread = HandlerThread("TrackUpdateThread")
        updateThread.start()
        updateHandler = Handler(updateThread.looper)

        updateRunnable = object : Runnable {
            override fun run() {
                sendTrackUpdate()
                updateHandler.postDelayed(this, 500L)
            }
        }
        updateHandler.post(updateRunnable)
    }

    private fun startForegroundService() {
        val channelId = "track_recording_channel"
        val channel = NotificationChannel(
            channelId,
            "Track Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Recording your route in background"
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("TrackMate")
            .setContentText("Recording in progress...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return

                // make sure no duplicates are saved
                if (pathPoints.isNotEmpty() && pathPoints.last().first.latitude == location.latitude &&
                    pathPoints.last().first.longitude == location.longitude
                ) {
                    return
                }

                val fixTime = if (location.time > 0) location.time else System.currentTimeMillis()
                _pathPoints.add(location to fixTime)
            }
        }

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback!!,
            Looper.getMainLooper()
        )
    }

    private fun speedAtPointKmh(prev: Location?, curr: Location): Float {
        val speedMps = when {
            curr.hasSpeed() -> curr.speed
            prev != null -> {
                val dt = (curr.time - prev.time) / 1000f
                val accurateEnough = (!prev.hasAccuracy() || prev.accuracy <= MAX_ACCURACY_METERS) &&
                    (!curr.hasAccuracy() || curr.accuracy <= MAX_ACCURACY_METERS)
                if (accurateEnough && dt >= MIN_RELIABLE_DT_SECONDS) prev.distanceTo(curr) / dt else null
            }
            else -> null
        }
        return if (speedMps != null && speedMps <= MAX_PLAUSIBLE_SPEED_MPS) speedMps * 3.6f else 0f
    }

    private fun saveToJsonAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
            val adapter = moshi.adapter(Track::class.java)

            val points = pathPoints
            val trackPointsList = points.mapIndexed { index, (location, time) ->
                val prev = points.getOrNull(index - 1)?.first
                TrackPoint(location.latitude, location.longitude, time, speedAtPointKmh(prev, location))
            }

            val trackData = Track(trackPointsList)
            val json = adapter.toJson(trackData)

            val file = File(filesDir, "track.json")
            if (file.parentFile?.exists() == false) {
                file.parentFile?.mkdirs()
            }
            file.writeText(json)

            val intent = Intent("com.example.trackmate.TRACK_FILE_SAVED")
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        }
    }

    private fun calculateDistanceMeters() =
        pathPoints.zipWithNext { a, b -> a.first.distanceTo(b.first) }.sum()

    private fun sendTrackUpdate() {
        val intent = Intent("com.example.trackmate.TRACK_UPDATE")
        intent.putExtra("distance", calculateDistanceMeters() / 1000)
        intent.putExtra("duration", (System.nanoTime() - startTime) / 1_000_000)

        if (pathPoints.isNotEmpty()) {
            val lastLocation = pathPoints.last().first
            intent.putExtra("lat", lastLocation.latitude)
            intent.putExtra("lng", lastLocation.longitude)
            if (lastLocation.hasBearing()) {
                intent.putExtra("bearing", lastLocation.bearing)
            }

            // Same per-fix speed as the saved track: GPS-reported speed, else distance/time between fixes
            val prevLocation = pathPoints.getOrNull(pathPoints.size - 2)?.first
            intent.putExtra("speed", speedAtPointKmh(prevLocation, lastLocation))
        }

        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        saveToJsonAsync()
        updateHandler.removeCallbacks(updateRunnable)

        lastTravelData = calculateTravelData()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun calculateTravelData(): NewTravelRequest? {
        if (pathPoints.size < 2) return null

        var distanceMeters = 0f
        var maxSpeedMps = 0f

        for (i in 1 until pathPoints.size) {
            val (prev, t1) = pathPoints[i - 1]
            val (curr, t2) = pathPoints[i]

            distanceMeters += prev.distanceTo(curr)

            val dt = (t2 - t1) / 1000f // seconds
            val accurateEnough = (!prev.hasAccuracy() || prev.accuracy <= MAX_ACCURACY_METERS) &&
                (!curr.hasAccuracy() || curr.accuracy <= MAX_ACCURACY_METERS)
            val speedMps = when {
                curr.hasSpeed() -> curr.speed
                accurateEnough && dt >= MIN_RELIABLE_DT_SECONDS -> prev.distanceTo(curr) / dt
                else -> null
            }
            if (speedMps != null && speedMps <= MAX_PLAUSIBLE_SPEED_MPS && speedMps > maxSpeedMps) {
                maxSpeedMps = speedMps
            }
        }

        val startTime = pathPoints.first().second
        val endTime = pathPoints.last().second
        val durationSec = (endTime - startTime) / 1000f

        val distanceKm = distanceMeters / 1000f
        val avgSpeedKmh = if (durationSec > 0) (distanceMeters / durationSec) * 3.6f else 0f
        val maxSpeedKmh = maxSpeedMps * 3.6f

        fun formatFloat(value: Float): Float {
            return "%.2f".format(Locale.US, value).toFloat()
        }

        return NewTravelRequest(
            id = -1,
            time = formatFloat(durationSec),
            averageSpeed = formatFloat(avgSpeedKmh),
            maxSpeed = formatFloat(maxSpeedKmh),
            distance = formatFloat(distanceKm)
        )
    }

}