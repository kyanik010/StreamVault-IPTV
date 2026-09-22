package com.streamvault.player

import javax.inject.Inject

/** Synchronization policy for two independent playback clocks. */
class DualSourceSyncController @Inject constructor(
    private val softThresholdMs: Long = DEFAULT_SOFT_THRESHOLD_MS,
    private val hardThresholdMs: Long = DEFAULT_HARD_THRESHOLD_MS
) {
    init { require(softThresholdMs >= 0); require(hardThresholdMs > softThresholdMs) }
    fun evaluate(video: PlaybackClockSnapshot, audio: PlaybackClockSnapshot): SyncDecision {
        val drift = calculateDriftMs(video, audio) ?: return SyncDecision.NoClock
        val abs = kotlin.math.abs(drift)
        return when { abs <= softThresholdMs -> SyncDecision.Stable(drift); abs < hardThresholdMs -> SyncDecision.SoftCorrect(drift); else -> SyncDecision.HardResync(drift) }
    }
    fun calculateDriftMs(video: PlaybackClockSnapshot, audio: PlaybackClockSnapshot): Long? {
        if (!video.available || !audio.available) return null
        if (video.playbackWallClockMs != null && audio.playbackWallClockMs != null) return audio.playbackWallClockMs - video.playbackWallClockMs
        if (!video.isLive && !audio.isLive) return audio.positionMs - video.positionMs
        return null
    }
    companion object {
        // Provisional policy values; validate with real provider streams before release.
        const val DEFAULT_SOFT_THRESHOLD_MS = 250L
        const val DEFAULT_HARD_THRESHOLD_MS = 750L
    }
}
sealed interface SyncDecision {
    data class Stable(val driftMs: Long) : SyncDecision
    data class SoftCorrect(val driftMs: Long) : SyncDecision
    data class HardResync(val driftMs: Long) : SyncDecision
    data object NoClock : SyncDecision
}
