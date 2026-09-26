package com.example.trackmate

import android.app.AlertDialog
import android.view.WindowManager

/**
 * Shows a dialog that contains text fields so the keyboard resizes it instead of covering
 * its inputs or its buttons.
 */
fun AlertDialog.Builder.showAboveKeyboard(): AlertDialog =
    create().also { dialog ->
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }
