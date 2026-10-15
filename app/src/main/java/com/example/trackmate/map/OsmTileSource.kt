package com.example.trackmate.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.annotation.StringRes
import com.example.trackmate.BuildConfig
import com.example.trackmate.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.DatabaseFileArchive
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.XYTileSource
import java.io.File

enum class MapStyle(val id: String, @StringRes val label: Int) {
    AUTO("auto", R.string.map_style_auto),
    OUTDOORS("outdoors", R.string.map_style_outdoors),
    STREETS("osm_bright", R.string.map_style_streets),
    LIGHT("alidade_smooth", R.string.map_style_light),
    DARK("alidade_smooth_dark", R.string.map_style_dark);

    fun shown(context: Context): MapStyle {
        if (this != AUTO) return this
        val night = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return if (night == android.content.res.Configuration.UI_MODE_NIGHT_YES) DARK else OUTDOORS
    }

    companion object {
        fun fromId(id: String?): MapStyle = entries.find { it.id == id } ?: AUTO
    }
}

// Stadia terms: cache on the device only, at most 100 MB, nothing past its expiry
private const val TILE_CACHE_MAX_BYTES = 100L * 1024 * 1024
private const val TILE_CACHE_TRIM_BYTES = 80L * 1024 * 1024

fun configureTileCache(context: Context) {
    val tileCache = File(context.cacheDir, "osmdroid/tiles")
    Configuration.getInstance().apply {
        userAgentValue = context.packageName
        osmdroidBasePath = tileCache.parentFile
        osmdroidTileCache = tileCache
        tileFileSystemCacheMaxBytes = TILE_CACHE_MAX_BYTES
        tileFileSystemCacheTrimBytes = TILE_CACHE_TRIM_BYTES
    }
    Thread { deleteExpiredTiles(File(tileCache, SqlTileWriter.DATABASE_FILENAME)) }.start()
}

// osmdroid keeps expired tiles to show them offline
private fun deleteExpiredTiles(database: File) {
    if (!database.exists()) return
    try {
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.delete(
                DatabaseFileArchive.TABLE,
                "${SqlTileWriter.COLUMN_EXPIRES} < ?",
                arrayOf(System.currentTimeMillis().toString())
            )
        }
    } catch (e: SQLiteException) {
        Log.d("TILE-CACHE", e.stackTraceToString())
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

fun tileSourceFor(context: Context, style: MapStyle): XYTileSource {
    val shown = style.shown(context)
    return XYTileSource(
        "Stadia_${shown.id}",
        0, 20, 256, ".png?api_key=${BuildConfig.STADIA_MAPS_API_KEY}",
        arrayOf("https://tiles.stadiamaps.com/tiles/${shown.id}/"),
        OSM_ATTRIBUTION
    )
}

const val OSM_ATTRIBUTION = "© Stadia Maps, © OpenMapTiles, © OpenStreetMap contributors"
