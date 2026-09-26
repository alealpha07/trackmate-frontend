package com.example.trackmate

import android.app.AlertDialog
import android.view.WindowManager

// dialog with text fields that the keyboard resizes
fun AlertDialog.Builder.showAboveKeyboard(): AlertDialog =
    create().also { dialog ->
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }
