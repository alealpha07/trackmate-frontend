package com.example.trackmate

import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.example.trackmate.services.BannerState
import com.example.trackmate.services.TrackInstructions

class NavigationBanner(private val root: View, onRetry: () -> Unit) {
    private val icon: ImageView = root.findViewById(R.id.imgManeuver)
    private val progress: ProgressBar = root.findViewById(R.id.bannerProgress)
    private val distance: TextView = root.findViewById(R.id.txtManeuverDistance)
    private val text: TextView = root.findViewById(R.id.txtManeuverText)
    private val thenRow: View = root.findViewById(R.id.bannerThen)
    private val thenIcon: ImageView = root.findViewById(R.id.imgThenManeuver)
    private val thenText: TextView = root.findViewById(R.id.txtThenText)
    private var state: BannerState? = null

    init {
        root.setOnClickListener { if (state == BannerState.Unavailable) onRetry() }
    }

    fun hide() {
        root.visibility = View.GONE
        state = null
    }

    fun show(state: BannerState, instructions: TrackInstructions?) {
        this.state = state
        root.visibility = View.VISIBLE
        root.isClickable = state == BannerState.Unavailable
        root.setBackgroundResource(
            if (state is BannerState.BackToTrack) R.drawable.bg_navigation_banner_alert else R.drawable.bg_navigation_banner
        )
        progress.visibility = if (state == BannerState.Loading) View.VISIBLE else View.GONE
        icon.visibility = if (state == BannerState.Loading) View.GONE else View.VISIBLE
        distance.visibility = View.GONE
        thenRow.visibility = View.GONE
        icon.scaleX = 1f
        val context = root.context

        when (state) {
            BannerState.Loading -> text.setText(R.string.directions_loading)
            BannerState.Unavailable -> {
                icon.setImageResource(R.drawable.ic_refresh_24)
                icon.contentDescription = null
                text.setText(R.string.directions_unavailable)
            }
            is BannerState.BackToTrack -> {
                icon.setImageResource(R.drawable.ic_warning_24)
                icon.contentDescription = null
                distance.visibility = View.VISIBLE
                distance.text = formatManeuverDistance(context, state.meters)
                text.setText(R.string.directions_back_to_track)
            }
            is BannerState.Next -> {
                val maneuvers = instructions?.maneuvers ?: return
                val next = maneuvers[state.index]
                icon.showManeuver(next)
                distance.visibility = View.VISIBLE
                distance.text = formatManeuverDistance(context, state.meters)
                text.text = next.bannerText()
                state.thenIndex?.let { maneuvers.getOrNull(it) }?.let { then ->
                    thenRow.visibility = View.VISIBLE
                    thenIcon.showManeuver(then)
                    thenText.text = then.bannerText()
                }
            }
        }
    }
}
