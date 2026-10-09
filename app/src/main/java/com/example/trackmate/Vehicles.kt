package com.example.trackmate

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import com.example.trackmate.services.ProfileService
import com.google.android.material.chip.ChipGroup

/** The backend's vehicles (GET /vehicle). A track can be travelled by any vehicle of its class. */
enum class Vehicle(
    val id: String,
    val vehicleClass: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @IdRes private val toggleButton: Int
) {
    CAR("car", "motor", R.string.vehicle_car, R.drawable.ic_vehicle_car_24, R.id.btnVehicleCar),
    MOTORCYCLE("motorcycle", "motor", R.string.vehicle_motorcycle, R.drawable.ic_vehicle_motorcycle_24, R.id.btnVehicleMotorcycle),
    BICYCLE("bicycle", "bicycle", R.string.vehicle_bicycle, R.drawable.ic_vehicle_bicycle_24, R.id.btnVehicleBicycle),
    FOOT("foot", "foot", R.string.vehicle_foot, R.drawable.ic_vehicle_foot_24, R.id.btnVehicleFoot);

    /** The vehicles that can travel its tracks: car and motorcycle share theirs. */
    val classVehicles: List<Vehicle> get() = entries.filter { it.vehicleClass == vehicleClass }

    private val classOrder: List<Vehicle> get() = listOf(this) + (classVehicles - this)

    /** "Car or Motorcycle": every vehicle of its class, this one first. */
    fun classLabel(context: Context): String {
        val names = classOrder.map { context.getString(it.label) }
        return if (names.size == 2) context.getString(R.string.vehicle_or, names[0], names[1]) else names[0]
    }

    /** The icons of every vehicle of its class side by side, this one first. */
    fun classIcon(context: Context): Drawable {
        val icons = classOrder.map { ContextCompat.getDrawable(context, it.icon)!!.mutate() }
        if (icons.size == 1) return icons[0]
        val step = icons[0].intrinsicWidth + (4 * context.resources.displayMetrics.density).toInt()
        return LayerDrawable(icons.toTypedArray()).apply {
            icons.indices.forEach { i -> setLayerInset(i, i * step, 0, (icons.size - 1 - i) * step, 0) }
        }
    }

    companion object {
        private const val PREFS_NAME = "vehicle_prefs"
        private const val KEY_LAST_USED = "last_used"
        private const val KEY_PROFILE = "profile"

        fun fromId(id: String?): Vehicle? = entries.firstOrNull { it.id == id }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun lastUsed(context: Context): Vehicle = fromId(prefs(context).getString(KEY_LAST_USED, null)) ?: CAR

        fun saveLastUsed(context: Context, vehicle: Vehicle) {
            prefs(context).edit().putString(KEY_LAST_USED, vehicle.id).apply()
        }

        /**
         * The vehicles in the user's profile as last loaded, every vehicle before that. Kept on the device so a
         * recording can be saved offline.
         */
        fun profile(context: Context): List<Vehicle> {
            val ids = prefs(context).getString(KEY_PROFILE, null)?.split(",") ?: return entries
            return entries.filter { it.id in ids }.ifEmpty { entries }
        }

        fun saveProfile(context: Context, vehicles: List<Vehicle>) {
            prefs(context).edit().putString(KEY_PROFILE, vehicles.joinToString(",") { it.id }).apply()
        }

        /** The profile's vehicles from the server, kept for [profile]; the kept ones when it can't be reached. */
        suspend fun loadProfile(context: Context, api: ProfileService): List<Vehicle> {
            try {
                val response = api.getVehicles()
                val vehicles = response.body()?.takeIf { response.isSuccessful }?.mapNotNull { fromId(it) }
                if (!vehicles.isNullOrEmpty()) {
                    saveProfile(context, vehicles)
                    return vehicles
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
            return profile(context)
        }

        /**
         * Sets up the vehicle toggle of dialog_track_name.xml with the [offered] vehicles, on [selected] when offered;
         * returns the chosen vehicle.
         */
        fun bindToggle(dialogView: View, selected: Vehicle, offered: List<Vehicle>): () -> Vehicle {
            val toggle = dialogView.findViewById<MaterialButtonToggleGroup>(R.id.vehicleToggle)
            val label = dialogView.findViewById<TextView>(R.id.txtVehicle)
            fun chosen() = entries.first { it.toggleButton == toggle.checkedButtonId }
            fun showLabel() {
                label.text = dialogView.context.getString(R.string.vehicle_label, dialogView.context.getString(chosen().label))
            }
            entries.forEach {
                dialogView.findViewById<View>(it.toggleButton).visibility = if (it in offered) View.VISIBLE else View.GONE
            }
            toggle.check((selected.takeIf { it in offered } ?: offered.first()).toggleButton)
            showLabel()
            toggle.addOnButtonCheckedListener { _, _, checked -> if (checked) showLabel() }
            return ::chosen
        }

        /** For dialog_track_name.xml when the vehicle is already decided. */
        fun hideToggle(dialogView: View) {
            dialogView.findViewById<View>(R.id.vehicleSection).visibility = View.GONE
        }
    }
}

/** "Vehicle: Car or Motorcycle" with both icons, for the track overviews; hidden when unknown. */
fun TextView.showTrackVehicle(vehicle: Vehicle?) {
    visibility = if (vehicle == null) View.GONE else View.VISIBLE
    vehicle ?: return
    text = context.getString(R.string.vehicle_label, vehicle.classLabel(context))
    setCompoundDrawablesRelativeWithIntrinsicBounds(vehicle.classIcon(context), null, null, null)
}

/** A track or travel row's vehicle icon; hidden when unknown. */
fun ImageView.bindVehicle(vehicle: Vehicle?) {
    visibility = if (vehicle == null) View.GONE else View.VISIBLE
    vehicle ?: return
    setImageResource(vehicle.icon)
    contentDescription = context.getString(vehicle.label)
}

private val VEHICLE_CHIPS = mapOf(R.id.chipCar to Vehicle.CAR, R.id.chipMotorcycle to Vehicle.MOTORCYCLE)

/**
 * view_vehicle_chips.xml: shown only on car/motorcycle tracks, starting on the track's vehicle. [onSelected] runs
 * for that first one too.
 */
fun ChipGroup.bindVehicleChips(trackVehicle: Vehicle, onSelected: (Vehicle) -> Unit) {
    visibility = if (trackVehicle.vehicleClass == Vehicle.CAR.vehicleClass) View.VISIBLE else View.GONE
    setOnCheckedStateChangeListener(null)
    VEHICLE_CHIPS.entries.firstOrNull { it.value == trackVehicle }?.let { check(it.key) }
    setOnCheckedStateChangeListener { _, checkedIds ->
        checkedIds.firstOrNull()?.let { VEHICLE_CHIPS[it] }?.let(onSelected)
    }
    onSelected(trackVehicle)
}
