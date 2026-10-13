package com.example.trackmate

import android.content.Context
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.example.trackmate.services.Maneuver
import kotlin.math.roundToInt

private enum class ManeuverIcon(@DrawableRes val drawable: Int, val mirrored: Boolean = false) {
    STRAIGHT(R.drawable.ic_maneuver_straight_24),
    SLIGHT_RIGHT(R.drawable.ic_maneuver_slight_right_24),
    SLIGHT_LEFT(R.drawable.ic_maneuver_slight_right_24, mirrored = true),
    RIGHT(R.drawable.ic_maneuver_right_24),
    LEFT(R.drawable.ic_maneuver_right_24, mirrored = true),
    SHARP_RIGHT(R.drawable.ic_maneuver_sharp_right_24),
    SHARP_LEFT(R.drawable.ic_maneuver_sharp_right_24, mirrored = true),
    UTURN_LEFT(R.drawable.ic_maneuver_uturn_left_24),
    UTURN_RIGHT(R.drawable.ic_maneuver_uturn_left_24, mirrored = true),
    ROUNDABOUT(R.drawable.ic_maneuver_roundabout_24),
    MERGE(R.drawable.ic_maneuver_merge_24),
    DEPART(R.drawable.ic_maneuver_depart_24),
    ARRIVE(R.drawable.ic_maneuver_arrive_24);

    companion object {
        fun of(type: Int) = when (type) {
            1, 2, 3 -> DEPART
            4, 5, 6 -> ARRIVE
            9, 18, 20, 23 -> SLIGHT_RIGHT
            16, 19, 21, 24 -> SLIGHT_LEFT
            10 -> RIGHT
            15 -> LEFT
            11 -> SHARP_RIGHT
            14 -> SHARP_LEFT
            12 -> UTURN_RIGHT
            13 -> UTURN_LEFT
            26, 27 -> ROUNDABOUT
            25, 37, 38 -> MERGE
            else -> STRAIGHT
        }
    }
}

fun ImageView.showManeuver(maneuver: Maneuver) {
    val icon = ManeuverIcon.of(maneuver.type)
    setImageResource(icon.drawable)
    scaleX = if (icon.mirrored) -1f else 1f
    contentDescription = maneuver.text
}

fun Maneuver.bannerText(): String =
    if (streets.isEmpty() || ManeuverIcon.of(type) in setOf(ManeuverIcon.ROUNDABOUT, ManeuverIcon.ARRIVE)) text
    else streets.joinToString(" / ")

fun formatManeuverDistance(context: Context, meters: Double): String =
    if (meters >= 1000) context.getString(R.string.navigate_distance_km, meters / 1000)
    else context.getString(R.string.navigate_distance_m, (meters / 10).roundToInt() * 10)
