package com.streamvault.feature.playback.player

import com.streamvault.domain.model.AudioSourceChannel
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.player.PlayerEngine
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

data class AudioSourceUiState(
    val available: Boolean = false,
    val channels: List<Channel> = emptyList(),
    val selectedChannelId: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val driftMs: Long? = null,
    val manualOffsetMs: Long = 0L,
    val syncState: String = "IDLE",
)

class DualSourceAudioCoordinator @Inject constructor(
    private val channelRepository: ChannelRepository,
) {
    private val _state = MutableStateFlow(AudioSourceUiState())
    val state: StateFlow<AudioSourceUiState> = _state.asStateFlow()
    private var engine: PlayerEngine? = null

    fun bind(playerEngine: PlayerEngine) {
        engine = playerEngine
        syncState()
    }

    suspend fun load(
        currentProviderId: Long,
        currentVideoUrl: String? = null,
    ): AudioSourceUiState {
        val currentEngine = engine ?: return _state.value.copy(
            error = "مصدر الصوت غير جاهز",
            loading = false,
        )
        if (currentProviderId <= 0L) {
            return _state.value.copy(
                available = false,
                channels = emptyList(),
                loading = false,
                error = "لم يتم العثور على مصدر IPTV الحالي.",
            )
        }

        _state.value = _state.value.copy(loading = true, error = null)

        val channels = runCatching {
            channelRepository.getChannels(currentProviderId).first()
        }.getOrDefault(emptyList())
            .filter { channel ->
                currentVideoUrl.isNullOrBlank() || channel.streamUrl != currentVideoUrl
            }

        val selected = currentEngine.selectedAudioSource.value
        val selectedId = selected?.let { selectedSource ->
            channels.firstOrNull { it.streamUrl == selectedSource.url }?.id
        }

        _state.value = _state.value.copy(
            available = channels.isNotEmpty(),
            channels = channels,
            selectedChannelId = selectedId,
            loading = false,
            error = if (channels.isEmpty()) {
                "لا توجد قنوات IPTV متاحة في الاشتراك الحالي."
            } else {
                null
            },
            manualOffsetMs = currentEngine.audioSourceSyncMs.value.toLong(),
            syncState = if (selected != null) "AUDIO_ACTIVE" else "IDLE",
        )
        return _state.value
    }

    suspend fun select(
        channel: Channel,
        currentProviderId: Long,
        videoEngine: PlayerEngine,
        videoStream: StreamInfo
    ): Result<Unit> {
        engine = videoEngine
        if (channel.streamUrl.isBlank()) return Result.error("رابط مصدر الصوت غير صالح.")

        videoEngine.playAudioSource(
            AudioSourceChannel(
                channel.name,
                channel.streamUrl,
                channel.logoUrl,
                channel.groupTitle
            )
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
        engine?.let { current ->
            current.syncAudioSourceToVideo(current.currentPosition.value)
        }
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