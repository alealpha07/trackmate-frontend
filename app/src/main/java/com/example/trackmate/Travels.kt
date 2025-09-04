package com.example.trackmate

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.trackmate.services.TrackItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.app.AlertDialog
import android.widget.EditText
import androidx.navigation.fragment.findNavController
import com.example.trackmate.services.EditTrackRequest
import com.example.trackmate.services.TrackService
import com.example.trackmate.services.TravelItem

class TravelAdapter(
    private val travels: MutableList<TravelItem>,
    private val onRenavigate: (TravelItem) -> Unit
) : RecyclerView.Adapter<TravelAdapter.TravelViewHolder>() {

    inner class TravelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txtTrackName)
        val txtTime: TextView = view.findViewById(R.id.txtTime)
        val txtMaxSpeed: TextView = view.findViewById(R.id.txtMaxSpeed)
        val txtAvgSpeed: TextView = view.findViewById(R.id.txtAvgSpeed)
        val txtDate: TextView = view.findViewById(R.id.txtDate)
        val btnRenavigate: ImageButton = view.findViewById(R.id.btnRenavigate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TravelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_travel, parent, false)
        return TravelViewHolder(view)
    }

    override fun onBindViewHolder(holder: TravelViewHolder, position: Int) {
        val travel = travels[position]
        holder.txtName.text = travel.name
        holder.txtTime.text = "Time: ${formatTime(travel.time)}"
        holder.txtMaxSpeed.text = "Max Speed: ${String.format("%.2f", travel.maxSpeed)} km/h"
        holder.txtAvgSpeed.text =
            "Best Avg Speed: ${String.format("%.2f", travel.averageSpeed)} km/h"
        holder.txtDate.text = "Date: ${travel.dateTimeString}"
        holder.btnRenavigate.setOnClickListener { onRenavigate(travel) }
    }

    override fun getItemCount(): Int = travels.size

    fun updateList(newTravels: List<TravelItem>) {
        travels.clear()
        travels.addAll(newTravels)
        notifyDataSetChanged()
    }
}

class Travels : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: TravelAdapter
    private lateinit var api: TrackService
    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_travels, container, false)
        recyclerView = view.findViewById(R.id.recyclerTravel)

        adapter = TravelAdapter(
            mutableListOf(),
            onRenavigate = { travel -> reNavigate(travel) }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        api = (requireActivity() as MainActivity).trackService

        loadTravels()
        return view
    }

    private fun loadTravels() {
        apiCallCoroutine.launch {
            try {
                val response = api.getTravels()
                if (response.isSuccessful && response.body() != null) {
                    val travels = response.body()!!
                    withContext(Dispatchers.Main) {
                        adapter.updateList(travels)
                    }
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }

    private fun reNavigate(travel: TravelItem) {
        if (travel.trackId != null) {
            findNavController().navigate(TravelsDirections.actionTravelsToTrackNavigation(travel.trackId))
        }

    }
}
