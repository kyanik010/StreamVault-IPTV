package com.streamvault.plugin.audiosource

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import org.json.JSONObject

class StreamVaultAudioPluginService : Service() {
    private val handler = Handler(Looper.getMainLooper()) { message ->
        handle(message)
        true
    }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun handle(message: Message) {
        val request = message.data ?: Bundle.EMPTY
        val reply = message.replyTo ?: return
        val response = Bundle().apply {
            putInt(PluginContract.KEY_API_VERSION, PluginContract.API_VERSION)
            putString(PluginContract.KEY_REQUEST_ID, request.getString(PluginContract.KEY_REQUEST_ID))
            putBoolean(PluginContract.KEY_SUCCESS, true)
        }

        try {
            when (message.what) {
                PluginContract.MSG_GET_MANIFEST -> {
                    response.putString(
                        PluginContract.KEY_MANIFEST_JSON,
                        manifestJson()
                    )
                }
                PluginContract.MSG_SET_ENABLED -> {
                    val enabled = request.getBoolean(PluginContract.KEY_ENABLED, true)
                    PluginPrefs.setEnabled(this, enabled)
                    response.putString(
                        PluginContract.KEY_MESSAGE,
                        if (enabled) "Audio Source enabled" else "Audio Source disabled"
                    )
                }
                PluginContract.MSG_GET_STATUS -> {
                    response.putString(
                        PluginContract.KEY_STATUS_LABEL,
                        if (PluginPrefs.enabled(this)) "Ready" else "Disabled"
                    )
                    response.putString(
                        PluginContract.KEY_MESSAGE,
                        if (PluginPrefs.m3uUrl(this).isBlank()) {
                            "Configure an M3U audio source"
                        } else {
                            "External audio source configured"
                        }
                    )
                }
                PluginContract.MSG_GET_PROVIDER_URL -> {
                    val url = PluginPrefs.m3uUrl(this)
                    if (url.isBlank()) {
                        response.putBoolean(PluginContract.KEY_SUCCESS, false)
                        response.putString(PluginContract.KEY_MESSAGE, "No M3U audio source configured")
                    } else {
                        response.putString(PluginContract.KEY_URL, url)
                        response.putString(PluginContract.KEY_PROVIDER_NAME, "Audio Source")
                    }
                }
                PluginContract.MSG_PREPARE_PLAYBACK -> {
                    // The public StreamVault Plugin API can prepare a single playback URL.
                    // It cannot attach a second independent audio renderer to the host player.
                    response.putBoolean(PluginContract.KEY_HANDLED, false)
                }
                PluginContract.MSG_GET_CONFIGURATION_VALUES -> {
                    response.putString(
                        PluginContract.KEY_CONFIGURATION_VALUES_JSON,
                        JSONObject().put("m3uUrl", PluginPrefs.m3uUrl(this)).toString()
                    )
                }
                PluginContract.MSG_SET_CONFIGURATION_VALUES -> {
                    val values = JSONObject(
                        request.getString(PluginContract.KEY_CONFIGURATION_VALUES_JSON).orEmpty()
                    )
                    PluginPrefs.setM3uUrl(this, values.optString("m3uUrl", ""))
                }
                PluginContract.MSG_RUN_CONFIGURATION_ACTION -> {
                    response.putString(
                        PluginContract.KEY_MESSAGE,
                        "Open Audio Source to manage the external audio playlist."
                    )
                }
            }
        } catch (error: Exception) {
            response.putBoolean(PluginContract.KEY_SUCCESS, false)
            response.putString(PluginContract.KEY_MESSAGE, error.message ?: "Plugin error")
        }

        runCatching { reply.send(Message.obtain().apply { data = response }) }
    }

    private fun manifestJson(): String =
        """{"schemaVersion":1,"id":"com.streamvault.plugin.audiosource","name":"Audio Source","versionName":"1.0.0","versionCode":1,"description":"External M3U audio source manager for StreamVault.","providerName":"Audio Source","configurationMode":"activity","configurationActivityAction":"com.streamvault.plugin.audiosource.CONFIGURE","capabilities":["provider.m3u","configuration.activity"]}"""
}
