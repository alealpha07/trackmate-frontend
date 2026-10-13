package com.example.trackmate.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import com.example.trackmate.R
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

fun createLetterMarkerIcon(
    context: Context,
    letter: Char,
    sizeDp: Int = 32,
    fillColor: Int = ContextCompat.getColor(
        context,
        com.google.android.material.R.color.design_default_color_primary
    )
): Drawable = createLetterMarkerIcon(context, letter.toString(), sizeDp, fillColor)

fun createLetterMarkerIcon(
    context: Context,
    label: String,
    sizeDp: Int = 32,
    fillColor: Int = ContextCompat.getColor(
        context,
        com.google.android.material.R.color.design_default_color_primary
    )
): Drawable {
    val density = context.resources.displayMetrics.density
    val sizePx = (sizeDp * density).toInt()
    val bitmap = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)

    val strokeWidth = 2f * density

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }

    val center = sizePx / 2f
    val radius = center - strokeWidth / 2f
    canvas.drawCircle(center, center, radius, fillPaint)
    canvas.drawCircle(center, center, radius, strokePaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sizePx * 0.55f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    val textY = center - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(label, center, textY, textPaint)

    return BitmapDrawable(context.resources, bitmap)
}

private const val LOCATION_DOT_SIZE_DP = 20
private const val LOCATION_DOT_STROKE_DP = 2f
private const val NAVIGATION_ARROW_SIZE_DP = 48

private fun createLocationDotBitmap(context: Context): Bitmap {
    val density = context.resources.displayMetrics.density
    val sizePx = (LOCATION_DOT_SIZE_DP * density).toInt()
    val strokeWidth = LOCATION_DOT_STROKE_DP * density
    val bitmap = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)

    val center = sizePx / 2f
    val radius = center - strokeWidth / 2f

    canvas.drawCircle(center, center, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, com.google.android.material.R.color.design_default_color_primary)
        style = Paint.Style.FILL
    })
    canvas.drawCircle(center, center, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    })

    return bitmap
}

private fun createNavigationArrowBitmap(context: Context): Bitmap {
    val density = context.resources.displayMetrics.density
    val sizePx = (NAVIGATION_ARROW_SIZE_DP * density).toInt()
    val drawable = ContextCompat.getDrawable(context, R.drawable.baseline_navigation_24_purple)!!
    return drawable.toBitmap(sizePx, sizePx)
}

fun applyPrimaryLocationIcons(context: Context, overlay: MyLocationNewOverlay) {
    overlay.setPersonIcon(createLocationDotBitmap(context))
    overlay.setPersonAnchor(0.5f, 0.5f)

    overlay.setDirectionIcon(createNavigationArrowBitmap(context))
    overlay.setDirectionAnchor(0.5f, 0.5f)
}
