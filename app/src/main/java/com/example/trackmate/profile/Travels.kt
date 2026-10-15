package com.example.trackmate.profile

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.trackmate.MainActivity
import com.example.trackmate.R
import com.example.trackmate.api.TrackService
import com.example.trackmate.api.TravelItem
import com.example.trackmate.util.Vehicle
import com.example.trackmate.util.bindVehicle
import com.example.trackmate.util.formatTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.app.AlertDialog
import android.widget.EditText
import androidx.navigation.fragment.findNavController

class TravelAdapter(
    private val travels: MutableList<TravelItem>,
    private val onRenavigate: (TravelItem) -> Unit,
    private val onDelete: (TravelItem) -> Unit
) : RecyclerView.Adapter<TravelAdapter.TravelViewHolder>() {

    inner class TravelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txtTrackName)
        val imgVehicle: ImageView = view.findViewById(R.id.imgVehicle)
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
        holder.imgVehicle.bindVehicle(Vehicle.fromId(travel.vehicle))
        val res = holder.itemView.resources
        holder.txtTime.text = res.getString(R.string.travel_time, formatTime(travel.time))
        holder.txtMaxSpeed.text =
            res.getString(R.string.travel_max_speed, res.getString(R.string.speed_kmh, "%.2f".format(travel.maxSpeed)))
        holder.txtAvgSpeed.text =
            res.getString(R.string.travel_avg_speed, res.getString(R.string.speed_kmh, "%.2f".format(travel.averageSpeed)))
        holder.txtDate.text = res.getString(R.string.travel_date, travel.dateTimeString)
        holder.btnRenavigate.setOnClickListener { onRenavigate(travel) }
        holder.itemView.setOnLongClickListener {
            onDelete(travel)
            true
        }
    }

    override fun getItemCount(): Int = travels.size

    fun remove(travel: TravelItem) {
        val index = travels.indexOf(travel)
        if (index < 0) return
        travels.removeAt(index)
        notifyItemRemoved(index)
    }

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
            onRenavigate = { travel -> reNavigate(travel) },
            onDelete = { travel -> confirmDelete(travel) }
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

    private fun confirmDelete(travel: TravelItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.travel_delete_title)
            .setMessage(R.string.travel_delete_message)
            .setPositiveButton(R.string.travel_delete_confirm) { _, _ -> deleteTravel(travel) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun deleteTravel(travel: TravelItem) {
        val appContext = requireContext().applicationContext
        apiCallCoroutine.launch {
            try {
                val response = api.deleteTravel(travel.id)
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        adapter.remove(travel)
                        Toast.makeText(appContext, R.string.travel_deleted, Toast.LENGTH_SHORT).show()
                    } else {
                        val errorMsg = response.errorBody()?.string() ?: appContext.getString(R.string.error_generic)
                        Toast.makeText(appContext, errorMsg, Toast.LENGTH_SHORT).show()
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
