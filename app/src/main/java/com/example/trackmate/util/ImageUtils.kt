package com.example.trackmate.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import kotlin.math.max

object ImageUtils {

    fun compressImage(
        context: Context,
        uri: Uri,
        maxDimension: Int = 1920,
        maxBytes: Int = 2 * 1024 * 1024
    ): File {
        val contentResolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension * 2 ||
            bounds.outHeight / sampleSize > maxDimension * 2
        ) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
            ?: throw IllegalStateException("Could not decode image")

        val orientation = contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        var bitmap = rotateBitmap(decoded, orientation)

        if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val scale = maxDimension.toFloat() / max(bitmap.width, bitmap.height)
            bitmap = Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true
            )
        }

        val file = File.createTempFile("upload", ".jpg", context.cacheDir)
        var quality = 90
        do {
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            quality -= 10
        } while (file.length() > maxBytes && quality > 30)

        return file
    }

    private fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
