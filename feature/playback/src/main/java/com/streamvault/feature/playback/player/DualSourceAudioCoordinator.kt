package com.streamvault.feature.playback.player

import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
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
    val state: StateFlow<AudioSourceUiState> = _state.asStateFlow()

    suspend fun load(currentProviderId: Long): AudioSourceUiState {
        _state.value = _state.value.copy(loading = true, error = null)
        audioSourceManager.restoreSelected()
        val channels = audioSourceManager.channels.first()
        val selected = audioSourceManager.selected.first()
        val mapped = channels.mapIndexed { index, channel ->
            Channel(id = index.toLong() + 1L, name = channel.name, canonicalName = channel.name,
                logoUrl = channel.logo, groupTitle = channel.group, streamUrl = channel.url)
        }
        val selectedId = selected?.let { s -> channels.indexOfFirst { it.url == s.url }.takeIf { it >= 0 }?.plus(1L) }
        _state.value = _state.value.copy(
            available = mapped.isNotEmpty(), providers = emptyList(), providerId = null,
            providerName = "Audio M3U", channels = mapped, selectedChannelId = selectedId,
            loading = false, error = if (mapped.isEmpty()) "لا توجد قنوات صوتية محملة" else null,
            manualOffsetMs = audioSourceManager.syncMs.value,
            syncState = if (selected != null) "AUDIO_ACTIVE" else "IDLE"
        )
        return _state.value
    }

    suspend fun addXtreamAudioAccount(serverUrl: String, username: String, password: String, name: String): ValidateAndAddProviderResult =
        ValidateAndAddProviderResult.ValidationError("مصدر الصوت يُدار من اشتراك التفعيل.")

    suspend fun selectProvider(providerId: Long, currentProviderId: Long): AudioSourceUiState = _state.value

    suspend fun select(channel: Channel, currentProviderId: Long, videoEngine: PlayerEngine, videoStream: StreamInfo): Result<Unit> {
        if (channel.streamUrl.isBlank()) return Result.error("رابط مصدر الصوت غير صالح.")
        audioSourceManager.play(AudioM3uChannel(channel.name, channel.streamUrl, channel.logoUrl, channel.groupTitle))
        _state.value = _state.value.copy(selectedChannelId = channel.id, error = null, syncState = "AUDIO_ACTIVE")
        return Result.success(Unit)
    }

    fun syncNow() { audioSourceManager.syncToVideo(0L); syncState() }
    fun adjustOffset(deltaMs: Long) { audioSourceManager.setSyncMs(audioSourceManager.syncMs.value + deltaMs.toInt()); syncState() }
    fun resetSync() { audioSourceManager.setSyncMs(0); syncState() }
    fun syncState() {
        _state.value = _state.value.copy(
            manualOffsetMs = audioSourceManager.syncMs.value,
            syncState = if (audioSourceManager.selected.value != null) "AUDIO_ACTIVE" else "IDLE",
            driftMs = null, error = null
        )
    }
    fun updateVideoStream(streamInfo: StreamInfo) = Unit
    fun remove() {
        audioSourceManager.stop()
        _state.value = _state.value.copy(selectedChannelId = null, error = null, driftMs = null, syncState = "IDLE")
    }
    fun stop() { audioSourceManager.stop(); _state.value = AudioSourceUiState() }
}