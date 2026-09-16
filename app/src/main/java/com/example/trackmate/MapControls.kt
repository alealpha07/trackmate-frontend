package com.example.trackmate

import android.app.AlertDialog
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

fun setupMapControls(
    fragment: Fragment,
    mapView: MapView,
    myLocationOverlay: MyLocationNewOverlay,
    btnLayers: View,
    btnMyLocation: View,
    btnZoomIn: View,
    btnZoomOut: View
) {
    val context = fragment.requireContext()

    mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

    btnLayers.setOnClickListener {
        val styles = MapStyle.entries.toTypedArray()
        val current = getSavedMapStyle(context)
        val labels = styles.map { it.label }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Map style")
            .setSingleChoiceItems(labels, styles.indexOf(current)) { dialog, which ->
                val selected = styles[which]
                saveMapStyle(context, selected)
                mapView.setTileSource(tileSourceFor(selected))
                mapView.invalidate()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    btnZoomIn.setOnClickListener { mapView.controller.zoomIn() }
    btnZoomOut.setOnClickListener { mapView.controller.zoomOut() }

    btnMyLocation.setOnClickListener {
        val location = myLocationOverlay.myLocation
        if (location != null) {
            mapView.controller.animateTo(location, 16.0, 500L)
        } else {
            Toast.makeText(context, "Current location not available yet", Toast.LENGTH_SHORT).show()
        }
    }
}
