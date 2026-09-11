package com.example.trackmate

import android.content.Context
import android.graphics.Color
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.views.overlay.CopyrightOverlay

enum class MapStyle(val id: String, val label: String, val urlSegment: String) {
    OUTDOORS("outdoors", "Outdoors", "outdoors"),
    STREETS("osm_bright", "Streets", "osm_bright"),
    LIGHT("alidade_smooth", "Light", "alidade_smooth"),
    DARK("alidade_smooth_dark", "Dark", "alidade_smooth_dark");

    companion object {
        fun fromId(id: String?): MapStyle = entries.find { it.id == id } ?: OUTDOORS
    }
}

private const val MAP_PREFS_NAME = "map_prefs"
private const val KEY_MAP_STYLE = "map_style"

fun getSavedMapStyle(context: Context): MapStyle {
    val prefs = context.getSharedPreferences(MAP_PREFS_NAME, Context.MODE_PRIVATE)
    return MapStyle.fromId(prefs.getString(KEY_MAP_STYLE, null))
}

fun saveMapStyle(context: Context, style: MapStyle) {
    context.getSharedPreferences(MAP_PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(KEY_MAP_STYLE, style.id).apply()
}

fun tileSourceFor(style: MapStyle): XYTileSource = XYTileSource(
    "Stadia_${style.id}",
    0, 20, 256, ".png?api_key=${BuildConfig.STADIA_MAPS_API_KEY}",
    arrayOf("https://tiles.stadiamaps.com/tiles/${style.urlSegment}/"),
    OSM_ATTRIBUTION
)

const val OSM_ATTRIBUTION = "© Stadia Maps, © OpenMapTiles, © OpenStreetMap contributors"

const val COPYRIGHT_START_OFFSET_DP = 8
const val COPYRIGHT_BOTTOM_OFFSET_DP = 10
const val COPYRIGHT_TEXT_ALPHA = 140

fun buildCopyrightOverlay(context: Context): CopyrightOverlay {
    val density = context.resources.displayMetrics.density
    return CopyrightOverlay(context).apply {
        setCopyrightNotice(OSM_ATTRIBUTION)
        setTextColor(Color.argb(COPYRIGHT_TEXT_ALPHA, 0, 0, 0))
        setOffset((COPYRIGHT_START_OFFSET_DP * density).toInt(), (COPYRIGHT_BOTTOM_OFFSET_DP * density).toInt())
    }
}
