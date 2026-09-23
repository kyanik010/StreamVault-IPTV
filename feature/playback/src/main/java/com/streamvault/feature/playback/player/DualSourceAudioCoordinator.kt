package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.ValidateAndAddProvider
import com.streamvault.domain.usecase.XtreamProviderSetupCommand
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import com.streamvault.player.DualSourcePlaybackController
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
    val providerName: String = "",
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
    private val providerRepository: ProviderRepository,
    private val channelRepository: ChannelRepository,
    private val playbackController: DualSourcePlaybackController,
    private val validateAndAddProvider: ValidateAndAddProvider
) {
    private val _state = MutableStateFlow(AudioSourceUiState())
    val state: StateFlow<AudioSourceUiState> = _state.asStateFlow()

    suspend fun load(currentProviderId: Long): AudioSourceUiState {
        _state.value = _state.value.copy(loading = true, error = null)
        val providers = providerRepository.getProviders().first()
            .filter { it.type == ProviderType.XTREAM_CODES && it.id > 0L && it.id != currentProviderId }
        if (providers.isEmpty()) {
            val result = AudioSourceUiState(error = "Add a second Xtream account to use Audio Source.")
            _state.value = result
            return result
        }
        val selectedId = _state.value.providerId?.takeIf { id -> providers.any { it.id == id } }
            ?: providers.first().id
        return loadProvider(selectedId, providers)
    }

    suspend fun addXtreamAudioAccount(
        serverUrl: String,
        username: String,
        password: String,
        name: String
    ): ValidateAndAddProviderResult {
        _state.value = _state.value.copy(addingAccount = true, error = null)
        val command = XtreamProviderSetupCommand(
            serverUrl = serverUrl.trim(),
            username = username.trim(),
            password = password,
            name = name.trim().ifBlank { "Audio Source" }
        )
        val result = validateAndAddProvider.loginXtream(command)
        _state.value = _state.value.copy(
            addingAccount = false,
            error = when (result) {
                is ValidateAndAddProviderResult.Success -> null
                is ValidateAndAddProviderResult.SavedWithWarning -> result.warning
                is ValidateAndAddProviderResult.ValidationError -> result.message
                is ValidateAndAddProviderResult.Error -> result.message
                is ValidateAndAddProviderResult.TransportConsentRequired -> "Audio Xtream account requires transport confirmation."
                is ValidateAndAddProviderResult.VerificationInconclusive -> result.message
            }
        )
        if (result is ValidateAndAddProviderResult.Success || result is ValidateAndAddProviderResult.SavedWithWarning) {
            _state.value = _state.value.copy(error = null)
        }
        return result
    }

    suspend fun selectProvider(providerId: Long, currentProviderId: Long): AudioSourceUiState {
        val providers = providerRepository.getProviders().first()
            .filter { it.type == ProviderType.XTREAM_CODES && it.id > 0L && it.id != currentProviderId }
        if (providers.none { it.id == providerId }) {
            val result = _state.value.copy(error = "Selected audio account is unavailable.")
            _state.value = result
            return result
        }
        return loadProvider(providerId, providers)
    }

    private suspend fun loadProvider(providerId: Long, providers: List<Provider>): AudioSourceUiState {
        val provider = providers.first { it.id == providerId }
        _state.value = _state.value.copy(
            loading = true, error = null, providers = providers,
            providerId = providerId, providerName = provider.name
        )
        val channels = channelRepository.getChannels(providerId).first()
            .filter { it.streamUrl.isNotBlank() }
        val result = _state.value.copy(
            available = channels.isNotEmpty(), channels = channels, loading = false,
            error = if (channels.isEmpty()) "The selected audio account has no live channels yet." else null
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
            ?: return Result.error("Select an audio Xtream account first.")
        if (audioProviderId == currentProviderId) {
            return Result.error("Audio account must be different from the video account.")
        }
        return when (val result = channelRepository.getStreamInfo(channel)) {
            is Result.Success -> {
                playbackController.attachAudio(videoEngine, videoStream, result.data)
                _state.value = _state.value.copy(
                    selectedChannelId = channel.id, error = null, driftMs = null,
                    manualOffsetMs = playbackController.manualOffsetMs(), syncState = "STARTING_AUDIO"
                )
                Result.success(Unit)
            }
            is Result.Error -> {
                _state.value = _state.value.copy(error = result.message)
                Result.error(result.message, result.exception)
            }
            is Result.Loading -> Result.error("Audio source is still loading.")
        }
    }

    fun reconnect() { playbackController.reconnectAudio(); syncState() }

    fun syncNow() { playbackController.syncNow(); syncState() }

    fun adjustOffset(deltaMs: Long) {
        playbackController.adjustManualOffsetMs(deltaMs)
        syncState()
    }

    fun resetSync() {
        playbackController.resetManualOffset()
        syncState()
    }

    fun syncState() {
        val s = playbackController.state.value
        _state.value = _state.value.copy(
            driftMs = s.driftMs,
            manualOffsetMs = playbackController.manualOffsetMs(),
            syncState = s.syncState.name
        )
    }

    fun updateVideoStream(streamInfo: StreamInfo) {
        if (_state.value.selectedChannelId != null) {
            playbackController.updateVideoStream(streamInfo)
            syncState()
        }
    }

    fun remove() {
        playbackController.stopAudioOnly()
        _state.value = _state.value.copy(
            selectedChannelId = null, error = null, driftMs = null,
            manualOffsetMs = 0L, syncState = "IDLE"
        )
    }

    fun stop() {
        playbackController.stop()
        _state.value = AudioSourceUiState()
    }
}
