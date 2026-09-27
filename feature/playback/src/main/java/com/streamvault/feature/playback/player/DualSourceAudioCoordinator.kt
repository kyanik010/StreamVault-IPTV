package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.player.AudioSourceManager
import com.streamvault.player.AudioM3uChannel
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import com.streamvault.player.PlayerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

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

class DualSourceAudioCoordinator @Inject constructor(
    private val audioSourceManager: AudioSourceManager,
) {

    private val _state = MutableStateFlow(AudioSourceUiState())
    private val currentPlayerChannels: List<Channel>
        get() = _lastPlayerEngine?.audioSourceChannels?.value.orEmpty().mapIndexed { index, channel ->
            Channel(index.toLong() + 1L, channel.name, channel.name, channel.logo, channel.group, channel.url)
        }
    private val currentSelectedAudio: com.streamvault.domain.model.AudioSourceChannel?
        get() = _lastPlayerEngine?.selectedAudioSource?.value
    private val currentAudioSyncMs: Int
        get() = _lastPlayerEngine?.audioSourceSyncMs?.value ?: 0
    private var _lastPlayerEngine: PlayerEngine? = null

    val state: StateFlow<AudioSourceUiState> = _state.asStateFlow()

    suspend fun load(currentProviderId: Long, videoEngine: PlayerEngine? = null): AudioSourceUiState {
        if (videoEngine != null) _lastPlayerEngine = videoEngine
        _state.value = _state.value.copy(loading = true, error = null)
        val channels = currentPlayerChannels
        val selected = currentSelectedAudio
        val mapped = channels.mapIndexed { index, channel ->
            Channel(
                id = index.toLong() + 1L,
                name = channel.name,
                canonicalName = channel.name,
                logoUrl = channel.logo,
                groupTitle = channel.group,
                streamUrl = channel.url
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
            manualOffsetMs = currentAudioSyncMs.toLong(),
            syncState = if (selected != null) "AUDIO_ACTIVE" else "IDLE"
        )
        return _state.value
    }

    suspend fun addXtreamAudioAccount(serverUrl: String, username: String, password: String, name: String): ValidateAndAddProviderResult =
        ValidateAndAddProviderResult.ValidationError("مصدر الصوت يُدار من اشتراك التفعيل.")

    suspend fun selectProvider(providerId: Long, currentProviderId: Long): AudioSourceUiState = _state.value

    suspend fun select(channel: Channel, currentProviderId: Long, videoEngine: PlayerEngine, videoStream: StreamInfo): Result<Unit> {
        if (channel.streamUrl.isBlank()) return Result.error("رابط مصدر الصوت غير صالح.")
        videoEngine.playAudioSource(AudioSourceChannel(channel.name, channel.streamUrl, channel.logoUrl, channel.groupTitle))
        videoEngine.syncAudioSourceToVideo(videoEngine.currentPosition.value)
        _state.value = _state.value.copy(
            selectedChannelId = channel.id,
            error = null,
            syncState = "AUDIO_ACTIVE"
        )
        return Result.success(Unit)
    }

    fun syncNow(videoPositionMs: Long, videoEngine: PlayerEngine) {
        videoEngine.syncAudioSourceToVideo(videoPositionMs)
        syncState(videoEngine)
    }

    fun adjustOffset(deltaMs: Long, videoEngine: PlayerEngine) {
        videoEngine.setAudioSourceSyncMs(videoEngine.audioSourceSyncMs.value + deltaMs.toInt())
        syncState(videoEngine)
    }

    fun resetSync(videoEngine: PlayerEngine) {
        videoEngine.setAudioSourceSyncMs(0)
        syncState(videoEngine)
    }

    fun syncState(videoEngine: PlayerEngine) {
        _state.value = _state.value.copy(
            manualOffsetMs = videoEngine.audioSourceSyncMs.value.toLong(),
            syncState = if (videoEngine.selectedAudioSource.value != null) "AUDIO_ACTIVE" else "IDLE",
            driftMs = null,
            error = null
        )
    }

    fun updateVideoStream(streamInfo: StreamInfo) = Unit

    fun remove() {
        audioSourceManager.stop()
        _state.value = _state.value.copy(
            selectedChannelId = null,
            error = null,
            driftMs = null,
            syncState = "IDLE"
        )
    }

    fun stop() {
        audioSourceManager.stop()
        _state.value = AudioSourceUiState()
    }
}