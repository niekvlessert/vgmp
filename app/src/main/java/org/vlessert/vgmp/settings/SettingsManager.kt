package org.vlessert.vgmp.settings

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {
    private const val PREFS_NAME = "vgmp_settings"
    private const val KEY_ANALYZER_ENABLED = "analyzer_enabled"
    private const val KEY_TRANSPARENCY_LEVEL = "transparency_level"
    private const val KEY_FADE_TIMEOUT = "fade_timeout"
    
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
}
