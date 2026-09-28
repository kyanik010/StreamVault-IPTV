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
                    val configured = PluginPrefs.server(this).isNotBlank() &&
                        PluginPrefs.username(this).isNotBlank() &&
                        PluginPrefs.password(this).isNotBlank()
                    response.putString(PluginContract.KEY_STATUS_LABEL, when {
                        !PluginPrefs.enabled(this) -> "Disabled"
                        configured -> "Ready"
                        else -> "Not configured"
                    })
                    response.putString(PluginContract.KEY_MESSAGE, if (configured) "Xtream audio account configured" else "Configure the Xtream audio account")
                }
                PluginContract.MSG_GET_PROVIDER_URL -> {
                    response.putBoolean(PluginContract.KEY_SUCCESS, false)
                    response.putString(PluginContract.KEY_MESSAGE, "Audio Source is consumed directly by the player.")
                }
                PluginContract.MSG_GET_AUDIO_CHANNELS -> {
                    val channels = fetchXtreamChannels()
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

    private fun fetchXtreamChannels(): JSONArray {
        require(PluginPrefs.enabled(this)) { "Audio Source is disabled." }
        val server = PluginPrefs.server(this).trim().trimEnd('/')
        val username = PluginPrefs.username(this).trim()
        val password = PluginPrefs.password(this)
        require(server.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
            "Configure the Xtream audio account first."
        }

        val endpoint = URL(
            "$server/player_api.php?username=${enc(username)}&password=${enc(password)}&action=get_live_streams"
        )
        val connection = endpoint.openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "StreamVault-Audio-Plugin/1.0")
        try {
            if (connection.responseCode !in 200..299) error("Xtream HTTP ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val source = JSONArray(body)
            val result = JSONArray()
            for (i in 0 until source.length()) {
                val item = source.optJSONObject(i) ?: continue
                val id = item.optString("stream_id")
                if (id.isBlank()) continue
                val ext = item.optString("container_extension").ifBlank { "ts" }
                val streamUrl = "$server/live/${enc(username)}/${enc(password)}/$id.$ext"
                result.put(JSONObject()
                    .put("id", id)
                    .put("name", item.optString("name").ifBlank { "Audio $id" })
                    .put("url", streamUrl)
                    .put("logo", item.optString("stream_icon"))
                    .put("group", item.optString("category_name")))
            }
            return result
        } finally {
            connection.disconnect()
        }
    }

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
            .put("versionName", "2.0.0")
            .put("versionCode", 2)
            .put("description", "Independent Xtream audio source for StreamVault.")
            .put("providerName", "StreamVault Audio Source")
            .put("configurationMode", "activity")
            .put("configurationActivityAction", "com.streamvault.plugin.audiosource.CONFIGURE")
            .put("capabilities", JSONArray().put("configuration.activity"))
            .toString()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
