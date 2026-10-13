package com.example.trackmate.navigate

import android.app.AlertDialog
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.example.trackmate.R
import com.example.trackmate.util.Vehicle
import com.example.trackmate.util.showAboveKeyboard
import com.google.android.material.textfield.TextInputLayout

// stays open until the save works, so a recording is never lost
fun Fragment.showTrackNameDialog(
    title: String,
    message: String? = null,
    name: String = "",
    vehicle: Vehicle?,
    vehicles: List<Vehicle> = Vehicle.entries,
    saveLabel: String,
    cancelLabel: String,
    confirmCancel: String? = null,
    onSave: (name: String, vehicle: Vehicle?, done: (error: String?) -> Unit) -> Unit
) {
    val dialogView = layoutInflater.inflate(R.layout.dialog_track_name, null)
    val nameLayout = dialogView.findViewById<TextInputLayout>(R.id.trackNameLayout)
    val editText = dialogView.findViewById<EditText>(R.id.editTrackName)
    editText.setText(name)
    val chosenVehicle = vehicle?.let { Vehicle.bindToggle(dialogView, it, vehicles) }
    if (vehicle == null) Vehicle.hideToggle(dialogView)

    val dialog = AlertDialog.Builder(requireContext())
        .setTitle(title)
        .apply { message?.let { setMessage(it) } }
        .setView(dialogView)
        // set below so a click doesn't close the dialog
        .setPositiveButton(saveLabel, null)
        .setNegativeButton(cancelLabel, null)
        .setCancelable(confirmCancel == null)
        .showAboveKeyboard()
    val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
    val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)

    editText.doAfterTextChanged { nameLayout.error = null }
    saveButton.setOnClickListener {
        val trimmed = editText.text.toString().trim()
        if (trimmed.isEmpty()) {
            nameLayout.error = getString(R.string.track_name_missing)
            return@setOnClickListener
        }
        saveButton.isEnabled = false
        cancelButton.isEnabled = false
        onSave(trimmed, chosenVehicle?.invoke()) { error ->
            if (!dialog.isShowing) return@onSave
            if (error == null) {
                dialog.dismiss()
            } else {
                nameLayout.error = error
                saveButton.isEnabled = true
                cancelButton.isEnabled = true
            }
        }
    }
    if (confirmCancel != null) {
        cancelButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setMessage(confirmCancel)
                .setPositiveButton(cancelLabel) { _, _ -> dialog.dismiss() }
                .setNegativeButton(R.string.track_keep, null)
                .show()
        }
    }
}
