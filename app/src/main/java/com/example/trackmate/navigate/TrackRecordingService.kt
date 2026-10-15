package com.example.trackmate.navigate

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.*
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.trackmate.R
import com.example.trackmate.api.NewTravelRequest
import com.example.trackmate.api.Track
import com.example.trackmate.api.TrackPoint
import com.google.android.gms.location.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

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
        // set on logout, so stopping doesn't save the recording
        private var discarded = false
        private val _pathPoints = mutableListOf<Pair<Location, Long>>()
        val pathPoints: List<Pair<Location, Long>>
            get() = _pathPoints.toList()
        fun getLastTravelData(): NewTravelRequest? = lastTravelData

        fun discard() {
            discarded = true
            isRecording = false
            _pathPoints.clear()
            lastTravelData = null
        }

        private val distance: FixDistance = { a, b ->
            FloatArray(1).also { Location.distanceBetween(a.lat, a.lng, b.lat, b.lng, it) }[0]
        }

        private fun Location.toFix(time: Long) =
            Fix(latitude, longitude, time, if (hasSpeed()) speed else null, if (hasAccuracy()) accuracy else null)

        private fun fixesOf(points: List<Pair<Location, Long>>) = points.map { (location, time) -> location.toFix(time) }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        // static, so the last recording would leak into this one
        _pathPoints.clear()
        lastTravelData = null
        discarded = false
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
            getString(R.string.record_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.record_channel_description)
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.record_notification))
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

    private fun saveToJsonAsync() {
        val fixes = fixesOf(pathPoints)
        CoroutineScope(Dispatchers.IO).launch {
            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
            val adapter = moshi.adapter(Track::class.java)

            val trackPointsList = fixes.mapIndexed { index, fix ->
                TrackPoint(fix.lat, fix.lng, fix.time, speedAtFixKmh(fixes.getOrNull(index - 1), fix, distance))
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

    private fun sendTrackUpdate() {
        val points = pathPoints
        val fixes = fixesOf(points)
        val intent = Intent("com.example.trackmate.TRACK_UPDATE")
        intent.putExtra("distance", travelledMeters(fixes, distance) / 1000)
        intent.putExtra("duration", (System.nanoTime() - startTime) / 1_000_000)

        if (points.isNotEmpty()) {
            val lastLocation = points.last().first
            intent.putExtra("lat", lastLocation.latitude)
            intent.putExtra("lng", lastLocation.longitude)
            if (lastLocation.hasBearing()) {
                intent.putExtra("bearing", lastLocation.bearing)
            }

            // same speed as the saved track
            intent.putExtra("speed", speedAtFixKmh(fixes.getOrNull(fixes.size - 2), fixes.last(), distance))
        }

        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        updateHandler.removeCallbacks(updateRunnable)
        if (discarded) return
        saveToJsonAsync()

        lastTravelData = travelStats(fixesOf(pathPoints), distance)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}