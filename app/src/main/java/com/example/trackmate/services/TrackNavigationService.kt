package com.example.trackmate.services

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.trackmate.R
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlin.math.min

const val POINT_VISIT_THRESHOLD = 30f
const val OFF_TRACK_THRESHOLD = 50f
const val LENGTH_SIMILARITY_RATIO = 0.15f
const val REQUIRED_MIDDLE_POINTS_RATIO = 0.5f
const val IS_BETWEEN_POINTS_THRESHOLD = 0.10

class TrackNavigationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private val navigationPath = mutableListOf<Location>()
    private val referenceTrackPoints = mutableListOf<LatLng>()
    private val visitedIndices = mutableSetOf<Int>()

    private var currentTargetIndex = 1
    private var referenceTrackLengthMeters = 0f
    private var lastNearestIndex = 0
    private var lastOffTrack = false
    private var startTime: Long = 0
    private lateinit var updateHandler: Handler
    private lateinit var updateRunnable: Runnable

    companion object {
        var isNavigating = false
        private var lastNavigationData: NewTravelRequest? = null
        var trackId: Int = -1
        fun getLastNavigationData(): NewTravelRequest? = lastNavigationData
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        updateHandler = Handler(Looper.getMainLooper())
        updateRunnable = object : Runnable {
            override fun run() {
                navigationPath.lastOrNull()?.let { sendNavigationUpdate(it) }
                updateHandler.postDelayed(this, 500L)
            }
        }
        updateHandler.post(updateRunnable)
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        trackId = intent?.getIntExtra("trackId", -1) ?: -1
        loadReferenceTrack()
        startForegroundService()
        if (referenceTrackPoints.isNotEmpty()) {
            startLocationUpdates()
            startTime = System.nanoTime()
        }
        return START_NOT_STICKY
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                if (navigationPath.lastOrNull()
                        ?.let { it.latitude == location.latitude && it.longitude == location.longitude } == true
                ) return
                navigationPath.add(location)
                checkNavigationProgress(location)
                sendNavigationUpdate(location)
            }
        }

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback!!,
            Looper.getMainLooper()
        )
    }

    private fun checkNavigationProgress(currentLocation: Location) {
        if (referenceTrackPoints.isEmpty()) return

        val nearestIdx = findNearestReferenceIndex(currentLocation)
        val nearestPoint = referenceTrackPoints[nearestIdx]
        val distanceToNearest = FloatArray(1).apply {
            Location.distanceBetween(
                currentLocation.latitude,
                currentLocation.longitude,
                nearestPoint.latitude,
                nearestPoint.longitude,
                this
            )
        }[0]

        if (distanceToNearest <= POINT_VISIT_THRESHOLD) visitedIndices.add(nearestIdx)

        // Advance targets while within threshold or between points
        while (currentTargetIndex < referenceTrackPoints.size) {
            val target = referenceTrackPoints[currentTargetIndex]
            val next = referenceTrackPoints.getOrNull(currentTargetIndex + 1)
            val distanceToTarget = FloatArray(1).apply {
                Location.distanceBetween(
                    currentLocation.latitude,
                    currentLocation.longitude,
                    target.latitude,
                    target.longitude,
                    this
                )
            }[0]

            val passed = distanceToTarget <= POINT_VISIT_THRESHOLD ||
                    (next != null && isBetweenPoints(currentLocation, target, next))

            if (passed) {
                visitedIndices.add(currentTargetIndex)
                currentTargetIndex++
            } else break
        }

        lastNearestIndex = nearestIdx
        lastOffTrack = distanceToNearest > OFF_TRACK_THRESHOLD

        checkCompletionCriteria()
    }

    private fun isBetweenPoints(
        user: Location,
        start: LatLng,
        end: LatLng,
        margin: Double = IS_BETWEEN_POINTS_THRESHOLD,
        maxDistance: Float = POINT_VISIT_THRESHOLD
    ): Boolean {
        val dx = end.latitude - start.latitude
        val dy = end.longitude - start.longitude
        val t =
            ((user.latitude - start.latitude) * dx + (user.longitude - start.longitude) * dy) / (dx * dx + dy * dy)
        if (t < -margin || t > 1.0 + margin) return false

        val distance = distancePointToSegment(
            user.latitude,
            user.longitude,
            user.latitude,
            user.longitude,
            start.latitude,
            start.longitude
        )
        return distance <= maxDistance
    }

    private fun loadReferenceTrack() {
        val file = File(filesDir, "navigation_track.json")
        if (!file.exists()) return

        try {
            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val track = moshi.adapter(Track::class.java).fromJson(file.readText()) ?: return
            referenceTrackPoints.clear()
            referenceTrackPoints.addAll(track.track.map { LatLng(it.latitude, it.longitude) })

            referenceTrackLengthMeters = referenceTrackPoints.windowed(2).sumOf {
                val startPoint = it[0]
                val endPoint = it[1]
                FloatArray(1).apply {
                    Location.distanceBetween(
                        startPoint.latitude,
                        startPoint.longitude,
                        endPoint.latitude,
                        endPoint.longitude,
                        this
                    )
                }[0].toDouble()
            }.toFloat()
        } catch (e: Exception) {
            Log.e("NAV_SERVICE", "Error loading track: ${e.message}")
        }
    }

    private fun findNearestReferenceIndex(loc: Location): Int {
        return referenceTrackPoints.indices.minByOrNull { idx ->
            FloatArray(1).apply {
                Location.distanceBetween(
                    loc.latitude,
                    loc.longitude,
                    referenceTrackPoints[idx].latitude,
                    referenceTrackPoints[idx].longitude,
                    this
                )
            }[0]
        } ?: 0
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

    private fun sendNavigationUpdate(location: Location) {
        val speedKmh = if (navigationPath.size >= 2) {
            val prev = navigationPath[navigationPath.size - 2]
            val dt = (location.time - prev.time) / 1000f // seconds
            if (dt > 0) prev.distanceTo(location) / dt * 3.6f else 0f
        } else {
            location.speed * 3.6f
        }

        val intent = Intent("com.example.trackmate.NAVIGATION_UPDATE").apply {
            putExtra("lat", location.latitude)
            putExtra("lng", location.longitude)
            putExtra("distance", calculateDistanceMeters() / 1000f)
            putExtra("duration", (System.nanoTime() - startTime) / 1_000_000)
            putExtra("progress", currentTargetIndex.toFloat() / referenceTrackPoints.size.toFloat())
            putExtra("nearestIndex", lastNearestIndex)
            putExtra("offTrack", lastOffTrack)
            putExtra("isFinished", currentTargetIndex >= referenceTrackPoints.size)
            putExtra("speed", speedKmh)
            referenceTrackPoints.getOrNull(currentTargetIndex)?.let {
                putExtra("nextLat", it.latitude)
                putExtra("nextLng", it.longitude)
            }
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    private fun checkCompletionCriteria() {
        if (referenceTrackPoints.isEmpty() || navigationPath.isEmpty()) return
        val endReached = visitedIndices.contains(referenceTrackPoints.lastIndex)
        val middleEnough = isPathFollowingReference()
        val traveledMeters = calculateDistanceMeters()
        val lengthSimilar =
            traveledMeters >= referenceTrackLengthMeters * (1f - LENGTH_SIMILARITY_RATIO)

        if (endReached && lengthSimilar && middleEnough) {
            currentTargetIndex = referenceTrackPoints.size
            isNavigating = false
            lastNavigationData = calculateTravelData()
            stopSelf()
        }
    }

    private fun isPathFollowingReference(): Boolean {
        if (referenceTrackPoints.size < 2 || navigationPath.size < 2) return false

        val closeCount = navigationPath.zipWithNext { start, end ->
            referenceTrackPoints.windowed(2).minOf { seg ->
                distancePointToSegment(
                    start.latitude,
                    start.longitude,
                    end.latitude,
                    end.longitude,
                    seg[0].latitude,
                    seg[0].longitude
                )
            }
        }.count { it <= OFF_TRACK_THRESHOLD }

        return closeCount.toFloat() / (navigationPath.size - 1) >= REQUIRED_MIDDLE_POINTS_RATIO
    }

    private fun distancePointToSegment(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
        refLat1: Double,
        refLng1: Double
    ): Float {
        val a = FloatArray(1)
        val b = FloatArray(1)
        val c = FloatArray(1)
        Location.distanceBetween(lat1, lng1, refLat1, refLng1, a)
        Location.distanceBetween(lat2, lng2, refLat1, refLng1, b)
        Location.distanceBetween(lat1, lng1, lat2, lng2, c)
        return min(a[0], min(b[0], c[0]))
    }

    private fun calculateDistanceMeters() =
        navigationPath.zipWithNext { a, b -> a.distanceTo(b) }.sum()

    private fun calculateTravelData(): NewTravelRequest? {
        if (navigationPath.size < 2) return null
        var distance = 0f
        var maxSpeed = 0f

        navigationPath.zipWithNext { prev, curr ->
            distance += prev.distanceTo(curr)
            val dt = (curr.time - prev.time) / 1000f
            if (dt > 0) maxSpeed = maxOf(maxSpeed, prev.distanceTo(curr) / dt)
        }

        val durationSec = (navigationPath.last().time - navigationPath.first().time) / 1000f
        return NewTravelRequest(
            id = trackId,
            time = durationSec,
            averageSpeed = if (durationSec > 0) distance / durationSec * 3.6f else 0f,
            maxSpeed = maxSpeed * 3.6f,
            distance = distance / 1000f
        )
    }

    override fun onDestroy() {
        lastNavigationData = calculateTravelData()
        navigationPath.clear()
        referenceTrackPoints.clear()
        visitedIndices.clear()
        currentTargetIndex = 1
        isNavigating = false
        trackId = -1
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        updateHandler.removeCallbacks(updateRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
