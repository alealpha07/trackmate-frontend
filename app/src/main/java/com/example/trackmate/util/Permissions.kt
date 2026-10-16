package com.example.trackmate.util

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import com.example.trackmate.R

// Android 12+ ignores a fine location request without coarse
private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
)

fun Context.hasLocationPermission() =
    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

fun Map<String, Boolean>.locationGranted() = this[Manifest.permission.ACCESS_FINE_LOCATION] == true

// recording and navigation also need their notification to show
fun Fragment.requestLocationWithRationale(
    launcher: ActivityResultLauncher<Array<String>>,
    withNotifications: Boolean = true
) {
    val permissions = if (withNotifications) LOCATION_PERMISSIONS + Manifest.permission.POST_NOTIFICATIONS
        else LOCATION_PERMISSIONS
    AlertDialog.Builder(requireContext())
        .setTitle(R.string.location_rationale_title)
        .setMessage(R.string.location_rationale_message)
        .setPositiveButton(R.string.location_rationale_continue) { _, _ -> launcher.launch(permissions) }
        .setNegativeButton(R.string.dialog_cancel, null)
        .show()
}

// after a second denial Android stops asking, only the app settings can turn it on
fun Fragment.onLocationDenied() {
    if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
        Toast.makeText(requireContext(), R.string.location_denied, Toast.LENGTH_SHORT).show()
        return
    }
    AlertDialog.Builder(requireContext())
        .setTitle(R.string.location_rationale_title)
        .setMessage(R.string.location_blocked_message)
        .setPositiveButton(R.string.location_open_settings) { _, _ ->
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${requireContext().packageName}".toUri())
            )
        }
        .setNegativeButton(R.string.dialog_cancel, null)
        .show()
}
