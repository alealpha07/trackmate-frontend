package com.example.trackmate

import android.view.View
import android.widget.TextView
import kotlin.math.roundToInt

class RideStatsCard(view: View) {
    private val txtDistance: TextView = view.findViewById(R.id.txtStatsDistance)
    private val txtTime: TextView = view.findViewById(R.id.txtStatsTime)
    private val txtSpeed: TextView = view.findViewById(R.id.txtStatsSpeed)

    fun showSpeed(speedKmh: Float) {
        txtSpeed.text = speedKmh.roundToInt().toString()
    }

    fun show(distanceKm: Float, durationMs: Long, speedKmh: Float) {
        txtDistance.text = "%.2f".format(distanceKm)
        txtTime.text = formatTime(durationMs / 1000f)
        showSpeed(speedKmh)
    }

    fun reset() = show(0f, 0L, 0f)
}
