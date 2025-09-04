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

class TrackAdapter(
    private val tracks: MutableList<TrackItem>,
    private val onEdit: (TrackItem) -> Unit,
    private val onDelete: (TrackItem) -> Unit,
    private val onRenavigate: (TrackItem) -> Unit
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    inner class TrackViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txtTrackName)
        val txtBestTime: TextView = view.findViewById(R.id.txtBestTime)
        val txtMaxSpeed: TextView = view.findViewById(R.id.txtMaxSpeed)
        val txtBestAvgSpeed: TextView = view.findViewById(R.id.txtBestAvgSpeed)
        val btnEdit: Button = view.findViewById(R.id.btnEdit)
        val btnDelete: Button = view.findViewById(R.id.btnDelete)
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
        holder.txtBestTime.text = "Best Time: ${formatTime(track.bestTime)}"
        holder.txtMaxSpeed.text = "Max Speed: ${String.format("%.2f", track.maxSpeed)} km/h"
        holder.txtBestAvgSpeed.text =
            "Best Avg Speed: ${String.format("%.2f", track.bestAverageSpeed)} km/h"

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
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }

    private fun editTrack(track: TrackItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_track_name, null)
        val editText = dialogView.findViewById<EditText>(R.id.editTrackName)
        editText.setText(track.name)

        AlertDialog.Builder(requireContext())
            .setTitle("Edit Track")
            .setMessage("Editing track ${track.name}")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    apiCallCoroutine.launch {
                        try {
                            val response = api.editTrack(EditTrackRequest(track.id, name))
                            withContext(Dispatchers.Main) {
                                if (response.isSuccessful) {
                                    Toast.makeText(
                                        requireContext(),
                                        response.body()?.string() ?: "Success",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    loadTracks()
                                } else {
                                    Toast.makeText(
                                        requireContext(),
                                        response.errorBody()?.string() ?: "Error",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("API-ERROR", e.stackTraceToString())
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteTrack(track: TrackItem) {

        AlertDialog.Builder(requireContext())
            .setTitle("Delete Track")
            .setMessage("Do you really want to delete the track ${track.name} ?")
            .setPositiveButton("Yes") { _, _ ->
                apiCallCoroutine.launch {
                    try {
                        val response = api.deleteTrack(track.id)
                        withContext(Dispatchers.Main) {
                            if (response.isSuccessful) {
                                Toast.makeText(
                                    requireContext(),
                                    response.body()?.string() ?: "Success",
                                    Toast.LENGTH_SHORT
                                ).show()
                                loadTracks()
                            } else {
                                Toast.makeText(
                                    requireContext(),
                                    response.errorBody()?.string() ?: "Error",
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
