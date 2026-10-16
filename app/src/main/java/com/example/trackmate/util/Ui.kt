package com.example.trackmate.util

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.trackmate.MainActivity
import com.example.trackmate.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.IOException

// dialog with text fields that the keyboard resizes
fun AlertDialog.Builder.showAboveKeyboard(): AlertDialog =
    create().also { dialog ->
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }

// a page that stays empty says why
fun Fragment.showLoadError(error: Throwable) {
    Log.d("API-ERROR", error.stackTraceToString())
    val text = if (error is IOException) R.string.error_offline else R.string.error_generic
    activity?.runOnUiThread { context?.let { Toast.makeText(it, text, Toast.LENGTH_LONG).show() } }
}

fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, R.string.link_no_app, Toast.LENGTH_SHORT).show()
    }
}

// page is "privacy" or "terms"
fun Fragment.openLegalPage(page: String) {
    findNavController().navigate(R.id.legal, bundleOf("page" to page))
}

fun ImageView.loadProfileImage(userId: Int, activity: MainActivity) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val response: Response<ResponseBody> = activity.profileService.getProfileImage(userId)
            if (response.isSuccessful && response.body() != null) {
                val bitmap = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeStream(response.body()!!.byteStream())
                }
                withContext(Dispatchers.Main) {
                    this@loadProfileImage.setImageBitmap(bitmap)
                }
            }
        } catch (e: Exception) {
            Log.d("API-ERROR", e.stackTraceToString())
        }
    }
}

fun formatTime(seconds: Float): String {
    val totalSeconds = seconds.toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, secs)
}
