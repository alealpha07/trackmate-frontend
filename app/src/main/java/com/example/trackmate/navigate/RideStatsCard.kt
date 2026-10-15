package com.example.trackmate.navigate

import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.trackmate.R
import com.example.trackmate.track.isOverLimit
import com.example.trackmate.util.formatTime
import kotlin.math.roundToInt

private val OVER_LIMIT_COLOR = Color.parseColor("#D32F2F")

class RideStatsCard(view: View) {
    private val txtDistance: TextView = view.findViewById(R.id.txtStatsDistance)
    private val txtTime: TextView = view.findViewById(R.id.txtStatsTime)
    private val txtSpeed: TextView = view.findViewById(R.id.txtStatsSpeed)
    private val txtSpeedLimit: TextView = view.findViewById(R.id.txtSpeedLimit)
    private val speedColor = ContextCompat.getColor(view.context, R.color.map_card_text)
    private var speedLimitKmh: Int? = null
    private var speedKmh = 0f

    init {
        reset()
    }

    fun showSpeed(speedKmh: Float) {
        this.speedKmh = speedKmh
        txtSpeed.text = speedKmh.roundToInt().toString()
        txtSpeed.setTextColor(if (isOverLimit(speedKmh, speedLimitKmh)) OVER_LIMIT_COLOR else speedColor)
    }

    fun showSpeedLimit(kmh: Int?) {
        if (kmh == speedLimitKmh) return
        speedLimitKmh = kmh
        txtSpeedLimit.visibility = if (kmh == null) View.GONE else View.VISIBLE
        if (kmh != null) {
            txtSpeedLimit.text = kmh.toString()
            txtSpeedLimit.contentDescription = txtSpeedLimit.context.getString(R.string.speed_limit_description, kmh)
        }
        showSpeed(speedKmh)
    }

    fun show(distanceKm: Float, durationMs: Long, speedKmh: Float) {
        txtDistance.text = "%.2f".format(distanceKm)
        txtTime.text = formatTime(durationMs / 1000f)
        showSpeed(speedKmh)
    }

    fun reset() {
        showSpeedLimit(null)
        show(0f, 0L, 0f)
    }
}
