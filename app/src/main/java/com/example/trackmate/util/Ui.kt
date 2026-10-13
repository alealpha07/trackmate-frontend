package com.example.trackmate.util

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.util.Log
import android.view.WindowManager
import android.widget.ImageView
import com.example.trackmate.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response

// dialog with text fields that the keyboard resizes
fun AlertDialog.Builder.showAboveKeyboard(): AlertDialog =
    create().also { dialog ->
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
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
