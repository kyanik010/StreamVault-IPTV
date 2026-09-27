package com.streamvault.feature.playback.player

import com.streamvault.domain.model.AudioSourceChannel
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import com.streamvault.player.PlayerEngine
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioSourceUiState(
    val available: Boolean = false,
    val providers: List<Provider> = emptyList(),
    val providerId: Long? = null,
    val providerName: String = "Audio M3U",
    val channels: List<Channel> = emptyList(),
    val selectedChannelId: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val driftMs: Long? = null,
    val manualOffsetMs: Long = 0L,
    val syncState: String = "IDLE",
    val addingAccount: Boolean = false
)

class DualSourceAudioCoordinator @Inject constructor() {
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
        val channels = currentEngine.audioSourceChannels.value
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
            providerName = "Audio M3U",
            channels = mapped,
            selectedChannelId = selectedId,
            loading = false,
            error = if (mapped.isEmpty()) "لا توجد قنوات صوتية محملة" else null,
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
        ValidateAndAddProviderResult.ValidationError("مصدر الصوت يُدار من اشتراك التفعيل.")

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
        engine?.let {
            it.setAudioSourceSyncMs(it.audioSourceSyncMs.value + deltaMs.toInt())
        }
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
}
