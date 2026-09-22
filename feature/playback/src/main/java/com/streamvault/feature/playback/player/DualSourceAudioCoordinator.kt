package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.player.DualSourcePlaybackController
import com.streamvault.player.PlayerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

data class AudioSourceUiState(
    val available: Boolean = false,
    val providerId: Long? = null,
    val providerName: String = "",
    val channels: List<Channel> = emptyList(),
    val selectedChannelId: Long? = null,
    val loading: Boolean = false,
    val error: String? = null
)

class DualSourceAudioCoordinator @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val channelRepository: ChannelRepository,
    private val playbackController: DualSourcePlaybackController
) {
    private val _state = MutableStateFlow(AudioSourceUiState())
    val state: StateFlow<AudioSourceUiState> = _state.asStateFlow()

    suspend fun load(currentProviderId: Long): AudioSourceUiState {
        _state.value = _state.value.copy(loading = true, error = null)
        val provider = providerRepository.getProviders().first()
            .firstOrNull {
                it.type == ProviderType.XTREAM_CODES &&
                    it.id > 0L &&
                    it.id != currentProviderId
            }
        if (provider == null) {
            val result = AudioSourceUiState(error = "Add a second Xtream account to use Audio Source.")
            _state.value = result
            return result
        }
        val channels = channelRepository.getChannels(provider.id).first()
            .filter { it.streamUrl.isNotBlank() }
        val result = AudioSourceUiState(
            available = channels.isNotEmpty(),
            providerId = provider.id,
            providerName = provider.name,
            channels = channels,
            loading = false,
            error = if (channels.isEmpty()) "The audio account has no live channels yet." else null
        )
        _state.value = result
        return result
    }

    suspend fun select(
        channel: Channel,
        currentProviderId: Long,
        videoEngine: PlayerEngine,
        videoStream: StreamInfo
    ): Result<Unit> {
        val audioProviderId = _state.value.providerId
            ?: return Result.error("Audio Xtream account is not configured.")
        if (audioProviderId == currentProviderId) {
            return Result.error("Audio account must be different from the video account.")
        }
        return when (val result = channelRepository.getStreamInfo(channel)) {
            is Result.Success -> {
                playbackController.attachAudio(videoEngine, videoStream, result.data)
                _state.value = _state.value.copy(selectedChannelId = channel.id, error = null)
                Result.success(Unit)
            }
            is Result.Error -> {
                _state.value = _state.value.copy(error = result.message)
                Result.error(result.message, result.exception)
            }
            is Result.Loading -> Result.error("Audio source is still loading.")
        }
    }

    fun remove() {
        playbackController.stopAudioOnly()
        _state.value = _state.value.copy(selectedChannelId = null, error = null)
    }

    fun stop() {
        playbackController.stop()
        _state.value = AudioSourceUiState()
    }
}
