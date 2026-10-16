package com.example.trackmate.navigate

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.trackmate.MainActivity
import com.example.trackmate.R
import com.example.trackmate.api.EditTrackRequest
import com.example.trackmate.api.TrackItem
import com.example.trackmate.api.TrackService
import com.example.trackmate.util.Vehicle
import com.example.trackmate.util.bindVehicle
import com.example.trackmate.util.formatTime
import com.example.trackmate.util.showLoadError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.app.AlertDialog
import androidx.navigation.fragment.findNavController

class TrackAdapter(
    private val tracks: MutableList<TrackItem>,
    private val onEdit: (TrackItem) -> Unit,
    private val onDelete: (TrackItem) -> Unit,
    private val onRenavigate: (TrackItem) -> Unit
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    inner class TrackViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txtTrackName)
        val imgVehicle: ImageView = view.findViewById(R.id.imgVehicle)
        val txtBestTime: TextView = view.findViewById(R.id.txtBestTime)
        val txtMaxSpeed: TextView = view.findViewById(R.id.txtMaxSpeed)
        val txtBestAvgSpeed: TextView = view.findViewById(R.id.txtBestAvgSpeed)
        val btnEdit: ImageButton = view.findViewById(R.id.btnEdit)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        val btnRenavigate: ImageButton = view.findViewById(R.id.btnRenavigate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val track = tracks[position]
        holder.txtName.text = track.name
        holder.imgVehicle.bindVehicle(Vehicle.fromId(track.vehicle))
        val res = holder.itemView.resources
        val missing = res.getString(R.string.value_missing)
        val speed = { value: Float? -> value?.let { res.getString(R.string.speed_kmh, "%.2f".format(it)) } ?: missing }
        holder.txtBestTime.text = res.getString(R.string.track_best_time, track.bestTime?.let { formatTime(it) } ?: missing)
        holder.txtMaxSpeed.text = res.getString(R.string.track_max_speed, speed(track.maxSpeed))
        holder.txtBestAvgSpeed.text = res.getString(R.string.track_best_avg_speed, speed(track.bestAverageSpeed))

        holder.btnEdit.setOnClickListener { onEdit(track) }
        holder.btnDelete.setOnClickListener { onDelete(track) }
        holder.btnRenavigate.setOnClickListener { onRenavigate(track) }
    }

    override fun getItemCount(): Int = tracks.size

    fun updateList(newTracks: List<TrackItem>) {
        tracks.clear()
        tracks.addAll(newTracks)
        notifyDataSetChanged()
    }
}

class SavedTracks : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: TrackAdapter
    private lateinit var api: TrackService
    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_saved_tracks, container, false)
        recyclerView = view.findViewById(R.id.recyclerTracks)

        adapter = TrackAdapter(
            mutableListOf(),
            onEdit = { track -> editTrack(track) },
            onDelete = { track -> deleteTrack(track) },
            onRenavigate = { track -> reNavigate(track) }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        api = (requireActivity() as MainActivity).trackService

        loadTracks()
        return view
    }

    private fun loadTracks() {
        apiCallCoroutine.launch {
            try {
                val response = api.getTracks()
                if (response.isSuccessful && response.body() != null) {
                    val tracks = response.body()!!
                    withContext(Dispatchers.Main) {
                        adapter.updateList(tracks)
                    }
                }
            } catch (e: Exception) {
                showLoadError(e)
            }
        }
    }

    private fun editTrack(track: TrackItem) {
        val failedText = getString(R.string.track_save_failed)
        val trackVehicle = Vehicle.fromId(track.vehicle) ?: Vehicle.CAR
        val profile = Vehicle.profile(requireContext())
        showTrackNameDialog(
            title = getString(R.string.track_edit),
            message = getString(R.string.track_edit_message, track.name),
            name = track.name,
            vehicle = trackVehicle,
            // the track's vehicle stays selectable
            vehicles = Vehicle.entries.filter { it in profile || it == trackVehicle },
            saveLabel = getString(R.string.dialog_save),
            cancelLabel = getString(R.string.dialog_cancel)
        ) { name, vehicle, done ->
            apiCallCoroutine.launch {
                // the server refuses a class change if others travelled it
                val error = try {
                    val response = api.editTrack(EditTrackRequest(track.id, name, vehicle!!.id))
                    if (response.isSuccessful) null else response.errorBody()?.string() ?: failedText
                } catch (e: Exception) {
                    Log.d("API-ERROR", e.stackTraceToString())
                    failedText
                }
                withContext(Dispatchers.Main) {
                    done(error)
                    if (error == null && isAdded) {
                        Toast.makeText(requireContext(), R.string.track_updated, Toast.LENGTH_SHORT).show()
                        loadTracks()
                    }
                }
            }
        }
    }

    private fun deleteTrack(track: TrackItem) {

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.track_delete)
            .setMessage(getString(R.string.track_delete_message, track.name))
            .setPositiveButton(R.string.dialog_yes) { _, _ ->
                apiCallCoroutine.launch {
                    try {
                        val response = api.deleteTrack(track.id)
                        withContext(Dispatchers.Main) {
                            if (response.isSuccessful) {
                                Toast.makeText(
                                    requireContext(),
                                    response.body()?.string() ?: getString(R.string.track_deleted),
                                    Toast.LENGTH_SHORT
                                ).show()
                                loadTracks()
                            } else {
                                Toast.makeText(
                                    requireContext(),
                                    response.errorBody()?.string() ?: getString(R.string.error_generic),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("API-ERROR", e.stackTraceToString())
                    }
                }
            }
            .setNegativeButton("No", null)
            .show()

    }

    private fun reNavigate(track: TrackItem) {
        findNavController().navigate(SavedTracksDirections.actionSavedTracksToTrackNavigation(track.id))
    }
}
