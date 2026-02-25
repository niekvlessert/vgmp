package org.vlessert.vgmp.settings

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {
    private const val PREFS_NAME = "vgmp_settings"
    private const val KEY_ANALYZER_ENABLED = "analyzer_enabled"
    private const val KEY_TRANSPARENCY_LEVEL = "transparency_level"
    private const val KEY_FADE_TIMEOUT = "fade_timeout"
    private const val KEY_FAVORITES_ONLY_MODE = "favorites_only_mode"
    private const val KEY_ANALYZER_STYLE = "analyzer_style"

    const val ANALYZER_STYLE_KALEIDOSCOPE = "kaleidoscope"
    const val ANALYZER_STYLE_BARS = "bars"
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun isAnalyzerEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ANALYZER_ENABLED, true)
    }
    
    fun setAnalyzerEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ANALYZER_ENABLED, enabled).apply()
    }
    
    fun getTransparencyLevel(context: Context): Int {
        return getPrefs(context).getInt(KEY_TRANSPARENCY_LEVEL, 80) // 0-100, default 80%
    }
    
    fun setTransparencyLevel(context: Context, level: Int) {
        getPrefs(context).edit().putInt(KEY_TRANSPARENCY_LEVEL, level.coerceIn(0, 100)).apply()
    }
    
    fun getFadeTimeout(context: Context): Int {
        return getPrefs(context).getInt(KEY_FADE_TIMEOUT, 10) // seconds, default 10
    }
    
    fun setFadeTimeout(context: Context, timeout: Int) {
        getPrefs(context).edit().putInt(KEY_FADE_TIMEOUT, timeout.coerceIn(0, 60)).apply()
    }
    
    fun isFavoritesOnlyMode(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_FAVORITES_ONLY_MODE, false)
    }
    
    fun setFavoritesOnlyMode(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FAVORITES_ONLY_MODE, enabled).apply()
    }

    fun getAnalyzerStyle(context: Context): String {
        return getPrefs(context).getString(KEY_ANALYZER_STYLE, ANALYZER_STYLE_KALEIDOSCOPE)
            ?: ANALYZER_STYLE_KALEIDOSCOPE
    }

    fun setAnalyzerStyle(context: Context, style: String) {
        getPrefs(context).edit().putString(KEY_ANALYZER_STYLE, style).apply()
    }
}
