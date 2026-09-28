package com.streamvault.plugin.audiosource

import android.content.Context

object PluginPrefs {
    private const val PREFS = "audio_source_plugin"
    private const val KEY_M3U = "m3u_url"
    private const val KEY_ENABLED = "enabled"

    fun m3uUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_M3U, "").orEmpty()

    fun setM3uUrl(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_M3U, value.trim()).apply()
    }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
    }
}
