package com.example.trackmate.map

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.trackmate.R
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

const val MAP_CREDIT_COLLAPSE_MS = 5000L

// OSM lets its credit fold if it starts open and folds on touch or after a few seconds, Stadia's always shows
class MapCredit(fragment: Fragment, root: View, mapView: MapView) {
    private val text: TextView = root.findViewById(R.id.txtMapCredit)
    private val handler = Handler(Looper.getMainLooper())
    private val collapse = Runnable { setExpanded(false) }
    private var expanded = true

    init {
        text.setOnClickListener { fragment.findNavController().navigate(R.id.credits) }
        root.findViewById<View>(R.id.btnMapCreditInfo).setOnClickListener { setExpanded(!expanded) }
        mapView.overlays.add(object : Overlay() {
            override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
                if (expanded && event.actionMasked == MotionEvent.ACTION_DOWN) setExpanded(false)
                return false
            }
        })
        setExpanded(true)
        handler.postDelayed(collapse, MAP_CREDIT_COLLAPSE_MS)
    }

    private fun setExpanded(value: Boolean) {
        handler.removeCallbacks(collapse)
        expanded = value
        text.setText(if (value) R.string.map_credit_full else R.string.map_credit)
    }
}
