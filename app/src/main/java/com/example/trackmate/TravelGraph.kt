package com.example.trackmate

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

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

        loadTrackDetails()
        loadTravels(binding.performanceChart, binding.barChart)
        loadLeaderboard()
    }

    private fun loadTrackDetails() {
        apiCallCoroutine.launch {
            try {
                val response = api.getTrack(args.trackId)
                if (response.isSuccessful && response.body() != null) {
                    val trackDetails = response.body()!!
                    withContext(Dispatchers.Main) {
                        binding.txtTrackName.text = trackDetails.name

                        val trackLength = trackDetails.overallBest.distance
                        binding.txtTrackLength.text = "Length: ${"%.2f".format(trackLength)} km"

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
        _binding = null
    }
}
