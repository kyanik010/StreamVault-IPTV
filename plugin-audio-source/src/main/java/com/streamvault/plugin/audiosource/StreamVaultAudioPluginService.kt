package com.streamvault.plugin.audiosource

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class StreamVaultAudioPluginService : Service() {
    private val handler = Handler(Looper.getMainLooper()) { message ->
        handle(message)
        true
    }
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun handle(message: Message) {
        val request = message.data ?: Bundle.EMPTY
        val response = Bundle().apply {
            putInt(PluginContract.KEY_API_VERSION, PluginContract.API_VERSION)
            putString(PluginContract.KEY_REQUEST_ID, request.getString(PluginContract.KEY_REQUEST_ID))
            putBoolean(PluginContract.KEY_SUCCESS, true)
        }

        try {
            when (message.what) {
                PluginContract.MSG_GET_MANIFEST -> response.putString(PluginContract.KEY_MANIFEST_JSON, manifestJson())
                PluginContract.MSG_SET_ENABLED -> {
                    val enabled = request.getBoolean(PluginContract.KEY_ENABLED, true)
                    PluginPrefs.setEnabled(this, enabled)
                    response.putString(PluginContract.KEY_MESSAGE, if (enabled) "Audio Source enabled" else "Audio Source disabled")
                }
                PluginContract.MSG_GET_STATUS -> {
                    val configured = getSharedPreferences("streamvault_audio_source", MODE_PRIVATE)
                        .getString("m3u_url", null).orEmpty().isNotBlank()
                    response.putString(PluginContract.KEY_STATUS_LABEL, when {
                        !PluginPrefs.enabled(this) -> "Disabled"
                        configured -> "Ready"
                        else -> "Not configured"
                    })
                    response.putString(PluginContract.KEY_MESSAGE, if (configured) "Managed M3U audio source configured" else "No managed audio M3U is configured")
                }
                PluginContract.MSG_GET_PROVIDER_URL -> {
                    response.putBoolean(PluginContract.KEY_SUCCESS, false)
                    response.putString(PluginContract.KEY_MESSAGE, "Audio Source is consumed directly by the player.")
                }
                PluginContract.MSG_GET_AUDIO_CHANNELS -> {
                    val channels = fetchM3uChannels()
                    response.putString(PluginContract.KEY_AUDIO_CHANNELS_JSON, channels.toString())
                }
                PluginContract.MSG_PREPARE_PLAYBACK -> response.putBoolean(PluginContract.KEY_HANDLED, false)
                PluginContract.MSG_GET_CONFIGURATION_VALUES -> {
                    response.putString(PluginContract.KEY_CONFIGURATION_VALUES_JSON, configurationValues().toString())
                }
                PluginContract.MSG_SET_CONFIGURATION_VALUES -> {
                    persistConfiguration(request.getString(PluginContract.KEY_CONFIGURATION_VALUES_JSON).orEmpty())
                }
                PluginContract.MSG_RUN_CONFIGURATION_ACTION -> {
                    response.putString(PluginContract.KEY_MESSAGE, "Configuration saved.")
                }
            }
        } catch (error: Exception) {
            response.putBoolean(PluginContract.KEY_SUCCESS, false)
            response.putString(PluginContract.KEY_MESSAGE, error.message ?: "Plugin error")
        }

        runCatching { message.replyTo?.send(Message.obtain().apply { data = response }) }
    }

    private fun fetchM3uChannels(): JSONArray {
        require(PluginPrefs.enabled(this)) { "Audio Source is disabled." }

        // Audio is a dedicated managed M3U subscription from the activation record.
        // Never fall back to the video/Xtream subscription.
        val m3uUrl = getSharedPreferences("streamvault_audio_source", MODE_PRIVATE)
            .getString("m3u_url", null)
            .orEmpty()
            .trim()

        if (m3uUrl.isBlank()) return cachedChannels()

        return try {
            val connection = (URL(m3uUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "StreamVault-Audio/1.0")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    error("M3U HTTP ${connection.responseCode}")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val channels = parseM3u(body)
                if (channels.length() == 0) {
                    error("The managed audio M3U contains no playable channels.")
                }
                PluginPrefs.saveCachedChannels(this, channels.toString())
                channels
            } finally {
                connection.disconnect()
            }
        } catch (error: Exception) {
            cachedChannels().takeIf { it.length() > 0 } ?: throw error
        }
    }

    private fun parseM3u(body: String): JSONArray {
        val result = JSONArray()
        var pending: JSONObject? = null
        for (raw in body.split(Regex("\\r?\\n"))) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val comma = line.indexOf(",")
                    val metadata = if (comma >= 0) line.substring(0, comma) else line
                    val name = line.substringAfter(",", "").trim()
                    pending = JSONObject()
                        .put("name", name.ifBlank { "Audio" })
                        .put("logo", attribute(metadata, "tvg-logo"))
                        .put("group", attribute(metadata, "group-title"))
                }
                line.startsWith("#") -> Unit
                pending != null -> {
                    pending!!.put("url", line)
                    result.put(pending)
                    pending = null
                }
            }
        }
        return result
    }

    private fun attribute(metadata: String, key: String): String {
        val match = Regex(key + "\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(metadata)
        return match?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun cachedChannels(): JSONArray =
        runCatching { JSONArray(PluginPrefs.cachedChannels(this)) }.getOrDefault(JSONArray())

    private fun configurationValues(): JSONObject = JSONObject()
        .put("serverUrl", PluginPrefs.server(this))
        .put("username", PluginPrefs.username(this))
        .put("password", PluginPrefs.password(this))
        .put("enabled", PluginPrefs.enabled(this))

    private fun persistConfiguration(raw: String) {
        val json = JSONObject(raw)
        PluginPrefs.saveAccount(this, json.optString("serverUrl", ""), json.optString("username", ""), json.optString("password", ""))
        PluginPrefs.setEnabled(this, json.optBoolean("enabled", true))
    }

    private fun manifestJson(): String =
        JSONObject()
            .put("schemaVersion", 1)
            .put("id", "com.streamvault.plugin.audiosource")
            .put("name", "StreamVault Audio Source")
            .put("versionName", "2.0.1")
            .put("versionCode", 3)
            .put("description", "Independent Xtream audio source for StreamVault.")
            .put("providerName", "StreamVault Audio Source")
            .put("configurationMode", "activity")
            .put("configurationActivityAction", "com.streamvault.plugin.audiosource.CONFIGURE")
            .put("capabilities", JSONArray().put("configuration.activity"))
            .toString()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
