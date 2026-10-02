package com.streamvault.feature.playback.player

import com.streamvault.domain.model.AudioSourceChannel
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.player.PlayerEngine
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val audioSourceCatalogCache: AudioSourceCatalogCache,
) {
    private val _state = MutableStateFlow(AudioSourceUiState())
    val state: StateFlow<AudioSourceUiState> = _state.asStateFlow()
    private var engine: PlayerEngine? = null

    fun bind(playerEngine: PlayerEngine) {
        engine = playerEngine
        syncState()
    }

    suspend fun preload(currentProviderId: Long) {
        if (currentProviderId > 0L) {
            audioSourceCatalogCache.warm(currentProviderId)
        }
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

        // The Audio library is prepared during subscription sync and persisted in Room.
        // If the in-memory view was not warmed yet (for example after process recreation),
        // hydrate it from that already-prepared local library. This never contacts Xtream.
        val cached = audioSourceCatalogCache.get(currentProviderId)
            ?: audioSourceCatalogCache.warm(currentProviderId)
        _state.value = _state.value.copy(
            loading = false,
            error = null
        )
        val channels = cached
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

        // Selection is strictly in-memory. The source must have been prepared during Sync
        // and loaded into the cache before the picker is opened.
        val prepared = audioSourceCatalogCache.getPrepared(currentProviderId, channel.id)
        if (prepared == null) {
            return Result.error("مصدر الصوت غير موجود في مكتبة Audio الجاهزة.")
        }
        val playbackUrl = prepared.resolvedUrl
            .takeIf { it.isNotBlank() && !prepared.isExpired() }
            ?: prepared.sourceUrl

        val resolvedStreamInfo = prepared.let {
            StreamInfo(
                url = playbackUrl,
                title = it.name,
                headers = it.headers,
                userAgent = it.userAgent,
                containerExtension = it.containerExtension,
                expirationTime = it.expirationTime
            )
        }

        if (resolvedStreamInfo.url.isBlank()) {
            return Result.error("تعذر تجهيز رابط مصدر الصوت للقناة المحددة.")
        }

        val audioChannel = AudioSourceChannel(
            channel.name,
            resolvedStreamInfo.url,
            channel.logoUrl,
            channel.groupTitle
        )
        // Start the independent audio stream only. Synchronization is manual via the Sync button.
        // Do not seek/reposition the audio source automatically when it is selected.
        videoEngine.playAudioSource(audioChannel, resolvedStreamInfo)
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