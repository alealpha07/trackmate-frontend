package com.example.trackmate.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import com.example.trackmate.R

// device settings, kept on logout
private const val SETTINGS_PREFS = "app_settings"
private const val KEY_THEME = "theme"

enum class ThemeMode(val id: String, @StringRes val label: Int, val nightMode: Int) {
    SYSTEM("system", R.string.settings_system_default, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("light", R.string.settings_theme_light, AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", R.string.settings_theme_dark, AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        fun fromId(id: String?): ThemeMode = entries.find { it.id == id } ?: SYSTEM
    }
}

fun savedTheme(context: Context): ThemeMode =
    ThemeMode.fromId(context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, null))

fun saveTheme(context: Context, theme: ThemeMode) {
    context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE).edit { putString(KEY_THEME, theme.id) }
    AppCompatDelegate.setDefaultNightMode(theme.nightMode)
}

fun applySavedTheme(context: Context) {
    AppCompatDelegate.setDefaultNightMode(savedTheme(context).nightMode)
}

// names in their own language, so anyone can find theirs
enum class AppLanguage(val tag: String, val displayName: String) {
    ENGLISH("en", "English"),
    ITALIAN("it", "Italiano"),
    GERMAN("de", "Deutsch");

    companion object {
        fun fromTag(tag: String?): AppLanguage? = entries.find { it.tag == tag }
    }
}

// null follows the system, Android keeps the per-app choice
fun chosenLanguage(): AppLanguage? =
    AppLanguage.fromTag(AppCompatDelegate.getApplicationLocales()[0]?.language)

fun chooseLanguage(language: AppLanguage?) {
    AppCompatDelegate.setApplicationLocales(
        language?.let { LocaleListCompat.forLanguageTags(it.tag) } ?: LocaleListCompat.getEmptyLocaleList()
    )
}

// what the server and voice guidance get: the app language, else the first supported system one
fun currentLanguage(): AppLanguage {
    chosenLanguage()?.let { return it }
    val system = LocaleListCompat.getAdjustedDefault()
    for (i in 0 until system.size()) {
        AppLanguage.fromTag(system[i]?.language)?.let { return it }
    }
    return AppLanguage.ENGLISH
}
