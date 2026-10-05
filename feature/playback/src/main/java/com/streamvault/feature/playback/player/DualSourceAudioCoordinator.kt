package com.streamvault.feature.playback.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.streamvault.domain.model.AudioSourceChannel
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import com.streamvault.player.PlayerEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class AudioSourceUiState(
    val available: Boolean = false,
    val providers: List<Provider> = emptyList(),
    val providerId: Long? = null,
    val providerName: String = "Audio Source",
    val channels: List<Channel> = emptyList(),
    val selectedChannelId: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val driftMs: Long? = null,
    val manualOffsetMs: Long = 0L,
    val syncState: String = "IDLE",
    val addingAccount: Boolean = false
)

class DualSourceAudioCoordinator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _state = MutableStateFlow(AudioSourceUiState())
    val state: StateFlow<AudioSourceUiState> = _state.asStateFlow()
    private var engine: PlayerEngine? = null

    fun bind(playerEngine: PlayerEngine) {
        engine = playerEngine
        syncState()
    }

    suspend fun load(currentProviderId: Long): AudioSourceUiState {
        val currentEngine = engine ?: return _state.value.copy(error = "مصدر الصوت غير جاهز")
        _state.value = _state.value.copy(loading = true, error = null)

        val pluginChannels = loadPluginChannels()
        val channels = pluginChannels

        val selected = currentEngine.selectedAudioSource.value
        val mapped = channels.mapIndexed { index, channel ->
            Channel(
                id = index.toLong() + 1L,
                name = channel.name,
                canonicalName = channel.name,
                logoUrl = channel.logo,
                groupTitle = channel.group,
                streamUrl = channel.url,
            )
        }
        val selectedId = selected?.let { s ->
            channels.indexOfFirst { it.url == s.url }.takeIf { it >= 0 }?.plus(1L)
        }

        _state.value = _state.value.copy(
            available = mapped.isNotEmpty(),
            providers = emptyList(),
            providerId = null,
            providerName = if (pluginChannels.isNotEmpty()) "StreamVault Audio Source" else "Audio Source",
            channels = mapped,
            selectedChannelId = selectedId,
            loading = false,
            error = if (mapped.isEmpty()) "لا توجد قنوات صوتية في اشتراك M3U الصوتي." else null,
            manualOffsetMs = currentEngine.audioSourceSyncMs.value.toLong(),
            syncState = if (selected != null) "AUDIO_ACTIVE" else "IDLE",
        )
        return _state.value
    }

    suspend fun addXtreamAudioAccount(
        serverUrl: String,
        username: String,
        password: String,
        name: String
    ): ValidateAndAddProviderResult =
        ValidateAndAddProviderResult.ValidationError("اضبط حساب الصوت من إضافة StreamVault Audio Source.")

    suspend fun selectProvider(providerId: Long, currentProviderId: Long): AudioSourceUiState = _state.value

    suspend fun select(
        channel: Channel,
        currentProviderId: Long,
        videoEngine: PlayerEngine,
        videoStream: StreamInfo
    ): Result<Unit> {
        engine = videoEngine
        if (channel.streamUrl.isBlank()) return Result.error("رابط مصدر الصوت غير صالح.")
        videoEngine.playAudioSource(
            AudioSourceChannel(channel.name, channel.streamUrl, channel.logoUrl, channel.groupTitle)
        )
        videoEngine.syncAudioSourceToVideo(videoEngine.currentPosition.value)
        _state.value = _state.value.copy(
            selectedChannelId = channel.id,
            error = null,
            syncState = "AUDIO_ACTIVE",
            manualOffsetMs = videoEngine.audioSourceSyncMs.value.toLong(),
        )
        return Result.success(Unit)
    }

    fun syncNow() {
        engine?.syncAudioSourceToVideo(engine?.currentPosition?.value ?: 0L)
        syncState()
    }

    fun adjustOffset(deltaMs: Long) {
        engine?.let { it.setAudioSourceSyncMs(it.audioSourceSyncMs.value + deltaMs.toInt()) }
        syncState()
    }

    fun resetSync() {
        engine?.setAudioSourceSyncMs(0)
        syncState()
    }

    fun syncState() {
        val current = engine ?: return
        val selected = current.selectedAudioSource.value
        _state.value = _state.value.copy(
            manualOffsetMs = current.audioSourceSyncMs.value.toLong(),
            syncState = if (selected != null) "AUDIO_ACTIVE" else "IDLE",
            driftMs = null,
            error = null,
        )
    }

    fun updateVideoStream(streamInfo: StreamInfo) = Unit

    fun remove() {
        engine?.stopAudioSource()
        _state.value = _state.value.copy(
            selectedChannelId = null,
            error = null,
            driftMs = null,
            syncState = "IDLE",
        )
    }

    fun stop() {
        engine?.stopAudioSource()
        _state.value = AudioSourceUiState()
    }

    private suspend fun loadPluginChannels(): List<AudioSourceChannel> {
        val component = findAudioPlugin() ?: return emptyList()
        val response = sendToPlugin(component, MSG_GET_AUDIO_CHANNELS) ?: return emptyList()
        if (!response.getBoolean(KEY_SUCCESS, false)) return emptyList()
        val raw = response.getString(KEY_AUDIO_CHANNELS_JSON).orEmpty()
        return runCatching {
            val array = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val url = item.optString("url")
                    if (url.isNotBlank()) {
                        add(
                            AudioSourceChannel(
                                item.optString("name").ifBlank { "Audio" },
                                url,
                                item.optString("logo").ifBlank { null },
                                item.optString("group").ifBlank { null }
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun findAudioPlugin(): ComponentName? {
        val intent = Intent(ACTION_PLUGIN_SERVICE)
        val resolveInfos = context.packageManager.queryIntentServices(intent, PackageManager.GET_META_DATA)
        return resolveInfos.firstOrNull { info ->
            info.serviceInfo?.packageName == AUDIO_PLUGIN_PACKAGE
        }?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
    }

    private suspend fun sendToPlugin(component: ComponentName, what: Int): Bundle? =
        withContext(Dispatchers.Main.immediate) {
            val serviceDeferred = CompletableDeferred<Messenger>()
            val responseDeferred = CompletableDeferred<Bundle>()
            val requestId = UUID.randomUUID().toString()
            var bound = false

            val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                val data = message.data ?: Bundle.EMPTY
                if (data.getString(KEY_REQUEST_ID) == requestId && !responseDeferred.isCompleted) {
                    responseDeferred.complete(Bundle(data))
                }
                true
            })
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    if (service == null) serviceDeferred.completeExceptionally(IllegalStateException("No plugin binder"))
                    else serviceDeferred.complete(Messenger(service))
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    if (!serviceDeferred.isCompleted) serviceDeferred.completeExceptionally(IllegalStateException("Plugin disconnected"))
                    if (!responseDeferred.isCompleted) responseDeferred.completeExceptionally(IllegalStateException("Plugin disconnected"))
                }
            }

            try {
                bound = context.bindService(
                    Intent(ACTION_PLUGIN_SERVICE).apply { this.component = component },
                    connection,
                    Context.BIND_AUTO_CREATE
                )
                if (!bound) return@withContext null
                val service = withTimeoutOrNull(5_000L) { serviceDeferred.await() } ?: return@withContext null
                service.send(Message.obtain(null, what).apply {
                    replyTo = reply
                    data = Bundle().apply {
                        putInt(KEY_API_VERSION, 1)
                        putString(KEY_REQUEST_ID, requestId)
                        if (what == MSG_GET_AUDIO_CHANNELS) {
                            putString(
                                KEY_URL,
                                context.getSharedPreferences("streamvault_audio_source", Context.MODE_PRIVATE)
                                    .getString("m3u_url", null)
                                    .orEmpty()
                                    .trim()
                            )
                        }
                    }
                })
                withTimeoutOrNull(15_000L) { responseDeferred.await() }
            } catch (_: Exception) {
                null
            } finally {
                if (bound) runCatching { context.unbindService(connection) }
            }
        }

    private companion object {
        const val ACTION_PLUGIN_SERVICE = "com.streamvault.plugin.API"
        const val AUDIO_PLUGIN_PACKAGE = "com.streamvault.plugin.audiosource"
        const val MSG_GET_AUDIO_CHANNELS = 20
        const val KEY_API_VERSION = "api_version"
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_SUCCESS = "success"
        const val KEY_AUDIO_CHANNELS_JSON = "audio_channels_json"
        const val KEY_URL = "url"
    }
}
