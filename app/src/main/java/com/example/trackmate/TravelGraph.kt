package com.example.trackmate

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.trackmate.databinding.FragmentTravelGraphBinding
import com.example.trackmate.services.*
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.utils.ColorTemplate
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

class LeaderboardAdapter :
    ListAdapter<LeaderboardItem, LeaderboardAdapter.ViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<LeaderboardItem>() {
        override fun areItemsTheSame(old: LeaderboardItem, new: LeaderboardItem) =
            old.userId == new.userId

        override fun areContentsTheSame(old: LeaderboardItem, new: LeaderboardItem) = old == new
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val userImage: ImageView = view.findViewById(R.id.userImage)
        val usernameText: TextView = view.findViewById(R.id.usernameText)
        val timeText: TextView = view.findViewById(R.id.timeText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user_leaderboard, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.usernameText.text = item.name
        holder.timeText.text = formatTime(item.time)
        (holder.userImage.context as? MainActivity)?.let { activity ->
            holder.userImage.loadProfileImage(item.userId, activity)
        }
    }
}

class TravelGraph : Fragment() {
    private lateinit var api: TrackService
    private lateinit var authApi: AuthService
    private lateinit var leaderboardAdapter: LeaderboardAdapter
    private lateinit var leaderboardRecycler: RecyclerView
    private var _binding: FragmentTravelGraphBinding? = null
    private val binding get() = _binding!!
    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)
    private val args: TravelGraphArgs by navArgs()

    private var myTravelsForMap: List<TravelItem> = emptyList()
    private val speedMapOverlays = mutableListOf<Overlay>()
    private var speedMapBounds: BoundingBox? = null
    private var isSpeedMapFullscreen = false
    private val exitFullscreenOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exitSpeedMapFullscreen()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTravelGraphBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        api = (requireActivity() as MainActivity).trackService
        authApi = (requireActivity() as MainActivity).authService
        leaderboardRecycler = binding.leaderboardRecycler
        leaderboardRecycler.layoutManager = LinearLayoutManager(requireContext())
        leaderboardAdapter = LeaderboardAdapter()
        leaderboardRecycler.adapter = leaderboardAdapter
        initSpeedMapView()

        loadTrackDetails()
        loadTravels(binding.performanceChart, binding.barChart)
        loadLeaderboard()
    }

    override fun onResume() {
        super.onResume()
        binding.speedMapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.speedMapView.onPause()
    }

    private fun loadTrackDetails() {
        apiCallCoroutine.launch {
            try {
                val response = api.getTrack(args.trackId)
                if (response.isSuccessful && response.body() != null) {
                    val trackDetails = response.body()!!
                    withContext(Dispatchers.Main) {
                        binding.txtTrackName.text = trackDetails.name

                        val trackLength = trackDetails.overallBest?.distance
                        binding.txtTrackLength.text = if (trackLength != null) {
                            "Length: ${"%.2f".format(trackLength)} km"
                        } else {
                            "Length: N/A"
                        }

                        binding.txtUserTravelCount.text =
                            "Your Travels: ${trackDetails.travelCount}"
                    }
                }
            } catch (e: Exception) {
                Log.e("API-ERROR", e.stackTraceToString())
            }
        }
    }

    private val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN)

    private fun loadTravels(lineChart: LineChart, barChart: BarChart) {
        apiCallCoroutine.launch {
            try {
                val response = api.getTravelsByTrack(args.trackId)
                if (response.isSuccessful && response.body() != null) {
                    val travels = response.body()!!
                    val userResponse = authApi.getUser()
                    if (userResponse.isSuccessful && userResponse.body() != null) {
                        val userId = userResponse.body()!!.id
                        withContext(Dispatchers.Main) {
                            setupTimeLineChart(lineChart, travels.sortedBy { travel ->
                                formatter.parse(travel.dateTimeString)
                            }.filter{ it.userId == userId})
                            setupSpeedComparisonBarChart(barChart, travels, userId)
                            setupSpeedMap(travels, userId)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("API-ERROR", e.stackTraceToString())
            }
        }
    }

    private fun setupSpeedComparisonBarChart(chart: BarChart, travels: List<TravelItem>, userId: Int) {
        if (travels.isEmpty()) return

        val userTravels = travels.filter { it.userId == userId }
        val otherTravels = travels.filter { it.userId != userId }

        val myBestMaxSpeed = userTravels.maxOfOrNull { it.maxSpeed } ?: 0f
        val globalMaxSpeed = travels.maxOfOrNull { it.maxSpeed } ?: 0f

        val myBestAvgSpeed = userTravels.maxOfOrNull { it.averageSpeed } ?: 0f
        val globalBestAvgSpeed = travels.maxOfOrNull { it.averageSpeed } ?: 0f

        val entries = listOf(
            BarEntry(0f, myBestMaxSpeed),
            BarEntry(1f, globalMaxSpeed),
            BarEntry(2f, myBestAvgSpeed),
            BarEntry(3f, globalBestAvgSpeed)
        )

        val labels = listOf(
            "My Max Speed",
            "Global Max Speed",
            "My Avg Speed",
            "Global Avg Speed"
        )

        val dataSet = BarDataSet(entries, "Speed Comparison").apply {
            colors = ColorTemplate.MATERIAL_COLORS.toList()
            valueTextColor = Color.BLACK
            valueTextSize = 14f
        }

        chart.data = BarData(dataSet)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            granularity = 1f
            textSize = 12f
            labelRotationAngle = -20f
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val index = value.toInt()
                    return labels.getOrNull(index) ?: ""
                }
            }
        }

        chart.axisLeft.apply {
            textSize = 12f
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    return "${value.toInt()} km/h"
                }
            }
        }

        chart.axisRight.isEnabled = false
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.extraBottomOffset = 40f

        chart.animateY(1000)
        chart.invalidate()
    }

    private fun initSpeedMapView() {
        val mapView = binding.speedMapView
        // The map gets reparented when toggling fullscreen; without this osmdroid would
        // tear down the tile provider and overlays on the transient detach.
        mapView.setDestroyMode(false)
        val mapStyle = getSavedMapStyle(requireContext())
        mapView.setTileSource(tileSourceFor(mapStyle))
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mapView.overlays.add(buildCopyrightOverlay(requireContext()))
        setSpeedMapInteractive(false)

        binding.btnSpeedMapFullscreen.setOnClickListener {
            if (isSpeedMapFullscreen) exitSpeedMapFullscreen() else enterSpeedMapFullscreen()
        }
        binding.btnSpeedMapZoomIn.setOnClickListener { mapView.controller.zoomIn() }
        binding.btnSpeedMapZoomOut.setOnClickListener { mapView.controller.zoomOut() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, exitFullscreenOnBack)
    }

    private fun setSpeedMapInteractive(enabled: Boolean) {
        val mapView = binding.speedMapView
        mapView.setMultiTouchControls(enabled)
        mapView.setOnTouchListener(if (enabled) null else View.OnTouchListener { _, _ -> true })
        binding.btnSpeedMapZoomIn.isVisible = enabled
        binding.btnSpeedMapZoomOut.isVisible = enabled
    }

    private fun enterSpeedMapFullscreen() {
        if (isSpeedMapFullscreen) return
        isSpeedMapFullscreen = true

        val section = binding.speedMapSection
        // Keep the inline slot's height so the page behind doesn't reflow
        binding.speedMapSlot.minimumHeight = binding.speedMapSlot.height
        binding.speedMapSlot.removeView(section)
        binding.fullscreenContainer.addView(
            section,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        binding.speedMapFrame.layoutParams = (binding.speedMapFrame.layoutParams as LinearLayout.LayoutParams).apply {
            height = 0
            weight = 1f
        }
        binding.speedMapFrame.isVisible = true
        binding.fullscreenContainer.isVisible = true

        setSystemChromeVisible(false)
        setSpeedMapInteractive(true)
        binding.btnSpeedMapFullscreen.setImageResource(R.drawable.ic_fullscreen_exit_24)
        binding.btnSpeedMapFullscreen.contentDescription = "Exit fullscreen"
        exitFullscreenOnBack.isEnabled = true
        binding.speedMapView.doOnNextLayout { fitSpeedMapToTrack() }
    }

    private fun exitSpeedMapFullscreen() {
        if (!isSpeedMapFullscreen) return
        isSpeedMapFullscreen = false

        val section = binding.speedMapSection
        binding.fullscreenContainer.removeView(section)
        binding.fullscreenContainer.isVisible = false
        binding.speedMapSlot.addView(
            section,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        binding.speedMapSlot.minimumHeight = 0
        binding.speedMapFrame.layoutParams = (binding.speedMapFrame.layoutParams as LinearLayout.LayoutParams).apply {
            height = (220 * resources.displayMetrics.density).roundToInt()
            weight = 0f
        }
        binding.speedMapFrame.isVisible = speedMapBounds != null

        setSystemChromeVisible(true)
        setSpeedMapInteractive(false)
        binding.btnSpeedMapFullscreen.setImageResource(R.drawable.ic_fullscreen_24)
        binding.btnSpeedMapFullscreen.contentDescription = "Open fullscreen"
        exitFullscreenOnBack.isEnabled = false
        binding.speedMapView.doOnNextLayout { fitSpeedMapToTrack() }
    }

    private fun setSystemChromeVisible(visible: Boolean) {
        val activity = requireActivity() as AppCompatActivity
        activity.supportActionBar?.let { if (visible) it.show() else it.hide() }
        activity.findViewById<View>(R.id.nav_view)?.isVisible = visible
    }

    private fun fitSpeedMapToTrack() {
        val bounds = speedMapBounds ?: return
        binding.speedMapView.zoomToBoundingBox(bounds, false, 60)
        binding.speedMapView.invalidate()
    }

    private fun setupSpeedMap(travels: List<TravelItem>, userId: Int) {
        myTravelsForMap = travels.filter { it.userId == userId }
            .sortedByDescending { formatter.parse(it.dateTimeString) }

        if (myTravelsForMap.isEmpty()) {
            binding.spinnerTravels.adapter = null
            renderSpeedMap(emptyList())
            return
        }

        val labels = myTravelsForMap.map { "${it.dateTimeString} - ${formatTime(it.time)}" }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTravels.adapter = adapter
        binding.spinnerTravels.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadSpeedMapForTravel(myTravelsForMap[position].id)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        loadSpeedMapForTravel(myTravelsForMap[0].id)
    }

    private fun loadSpeedMapForTravel(travelId: Int) {
        apiCallCoroutine.launch {
            val points = try {
                val fileResponse = api.getTravelFile(travelId)
                if (fileResponse.isSuccessful && fileResponse.body() != null) {
                    val json = fileResponse.body()!!.string()
                    val moshi = Moshi.Builder().build()
                    moshi.adapter(Track::class.java).fromJson(json)?.track ?: emptyList()
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e("API-ERROR", e.stackTraceToString())
                emptyList()
            }

            withContext(Dispatchers.Main) {
                renderSpeedMap(points)
            }
        }
    }

    private fun renderSpeedMap(points: List<TrackPoint>) {
        val mapView = binding.speedMapView
        speedMapOverlays.forEach { mapView.overlays.remove(it) }
        speedMapOverlays.clear()

        val hasTrack = points.size >= 2
        // In fullscreen the frame stays so the exit button remains reachable
        binding.speedMapFrame.isVisible = hasTrack || isSpeedMapFullscreen
        mapView.isVisible = hasTrack
        binding.speedLegendBar.isVisible = hasTrack
        binding.txtSpeedMin.isVisible = hasTrack
        binding.txtSpeedMax.isVisible = hasTrack

        if (!hasTrack) {
            speedMapBounds = null
            return
        }

        val minSpeed = points.minOf { it.speed }
        val maxSpeed = points.maxOf { it.speed }
        val range = (maxSpeed - minSpeed).takeIf { it > 0f } ?: 1f
        val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }

        for (i in 0 until points.size - 1) {
            val t = (points[i].speed - minSpeed) / range
            val segment = Polyline().apply {
                setPoints(listOf(geoPoints[i], geoPoints[i + 1]))
                outlinePaint.color = speedToColor(t)
                outlinePaint.strokeWidth = 10f
            }
            mapView.overlays.add(segment)
            speedMapOverlays.add(segment)
        }

        // Neutral grey so the endpoints don't read as part of the green-to-red speed scale
        val endpointColor = Color.parseColor("#424242")
        listOf('A' to geoPoints.first(), 'B' to geoPoints.last()).forEach { (letter, point) ->
            val marker = Marker(mapView).apply {
                position = point
                title = if (letter == 'A') "Start" else "Finish"
                icon = createLetterMarkerIcon(requireContext(), letter, fillColor = endpointColor)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(marker)
            speedMapOverlays.add(marker)
        }

        speedMapBounds = BoundingBox.fromGeoPoints(geoPoints)
        fitSpeedMapToTrack()

        binding.txtSpeedMin.text = "${minSpeed.roundToInt()} km/h"
        binding.txtSpeedMax.text = "${maxSpeed.roundToInt()} km/h"
    }

    private fun speedToColor(t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        val startColor = Color.parseColor("#4CAF50")
        val endColor = Color.parseColor("#F44336")
        val r = Color.red(startColor) + ((Color.red(endColor) - Color.red(startColor)) * clamped).roundToInt()
        val g = Color.green(startColor) + ((Color.green(endColor) - Color.green(startColor)) * clamped).roundToInt()
        val b = Color.blue(startColor) + ((Color.blue(endColor) - Color.blue(startColor)) * clamped).roundToInt()
        return Color.rgb(r, g, b)
    }

    private fun loadLeaderboard() {
        apiCallCoroutine.launch {
            try {
                val response = api.getLeaderboard(args.trackId)
                if (response.isSuccessful && response.body() != null) {
                    val leaderboard = response.body()!!
                    withContext(Dispatchers.Main) {
                        leaderboardAdapter.submitList(leaderboard)
                    }
                }
            } catch (e: Exception) {
                Log.e("API-ERROR", e.stackTraceToString())
            }
        }
    }

    private fun setupTimeLineChart(chart: LineChart, travels: List<TravelItem>) {
        val entries = travels.mapIndexed { index, travel ->
            Entry(index.toFloat(), travel.time)
        }

        val dataSet = LineDataSet(entries, "Time").apply {
            color = Color.BLUE
            setCircleColor(Color.BLUE)
            lineWidth = 2f
            circleRadius = 6f
            valueTextSize = 14f
            valueTextColor = Color.BLACK
            setDrawFilled(true)
            fillColor = Color.BLUE
            fillAlpha = 50
            setDrawValues(true)
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val totalSeconds = value.toInt()
                    val hours = totalSeconds / 3600
                    val minutes = (totalSeconds % 3600) / 60
                    val seconds = totalSeconds % 60
                    return "%02d:%02d:%02d".format(hours, minutes, seconds)
                }
            }
        }

        chart.data = LineData(dataSet)

        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            granularity = 1f
            textSize = 14f
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val index = value.toInt()
                    return if (index in travels.indices) {
                        travels[index].dateTimeString.substringAfter(" ")
                    } else ""
                }
            }
        }

        chart.axisLeft.apply {
            granularity = 1f
            textSize = 14f
            valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val totalSeconds = value.toInt()
                    val hours = totalSeconds / 3600
                    val minutes = (totalSeconds % 3600) / 60
                    val seconds = totalSeconds % 60
                    return "%02d:%02d:%02d".format(hours, minutes, seconds)
                }
            }
        }

        chart.axisRight.isEnabled = false

        chart.legend.apply {
            isEnabled = true
            textSize = 14f
            verticalAlignment =
                com.github.mikephil.charting.components.Legend.LegendVerticalAlignment.BOTTOM
            horizontalAlignment =
                com.github.mikephil.charting.components.Legend.LegendHorizontalAlignment.LEFT
            orientation =
                com.github.mikephil.charting.components.Legend.LegendOrientation.HORIZONTAL
            setDrawInside(false)
        }

        chart.extraBottomOffset = 30f
        chart.extraRightOffset = 35f
        chart.description.isEnabled = false
        chart.animateY(1000)
        chart.invalidate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (isSpeedMapFullscreen) {
            isSpeedMapFullscreen = false
            setSystemChromeVisible(true)
        }
        binding.speedMapView.onDetach()
        _binding = null
    }
}
