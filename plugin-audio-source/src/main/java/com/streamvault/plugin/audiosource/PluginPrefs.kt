package com.streamvault.plugin.audiosource

import android.content.Context

object PluginPrefs {
    private const val PREFS = "audio_source_plugin"
    private const val KEY_SERVER = "server_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_ENABLED = "enabled"

    fun server(context: Context): String = prefs(context).getString(KEY_SERVER, "").orEmpty()
    fun username(context: Context): String = prefs(context).getString(KEY_USERNAME, "").orEmpty()
    fun password(context: Context): String = prefs(context).getString(KEY_PASSWORD, "").orEmpty()
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun saveAccount(context: Context, server: String, username: String, password: String) {
        prefs(context).edit().putString(KEY_SERVER, server.trim()).putString(KEY_USERNAME, username.trim()).putString(KEY_PASSWORD, password).apply()
    }

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun cachedChannels(context: Context): String =
        prefs(context).getString("cached_channels_json", "").orEmpty()

    fun saveCachedChannels(context: Context, value: String) {
        prefs(context).edit().putString("cached_channels_json", value).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
