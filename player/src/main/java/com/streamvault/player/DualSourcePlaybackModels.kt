package com.streamvault.player

import com.streamvault.domain.model.StreamInfo

data class DualSourcePlaybackState(
    val video: PlaybackState = PlaybackState.IDLE,
    val audio: PlaybackState = PlaybackState.IDLE,
    val audioAttached: Boolean = false,
    val driftMs: Long? = null,
    val syncState: DualSourceSyncState = DualSourceSyncState.IDLE,
    val reconnectingAudio: Boolean = false,
    val reconnectingVideo: Boolean = false
)

enum class DualSourceSyncState {
    IDLE,
    WAITING_FOR_VIDEO,
    STARTING_AUDIO,
    SYNCHRONIZED,
    SOFT_CORRECTING,
    HARD_RESYNC,
    AUDIO_DISCONNECTED,
    VIDEO_DISCONNECTED,
    ERROR
}

data class DualSourceSession(
    val videoStream: StreamInfo,
    val audioStream: StreamInfo?
)
