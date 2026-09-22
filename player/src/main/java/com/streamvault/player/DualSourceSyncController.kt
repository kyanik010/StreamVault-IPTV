package com.streamvault.player

class DualSourceSyncController(
    private val softThresholdMs: Long = DEFAULT_SOFT_THRESHOLD_MS,
    private val hardThresholdMs: Long = DEFAULT_HARD_THRESHOLD_MS
) {
    init {
        require(softThresholdMs >= 0)
        require(hardThresholdMs > softThresholdMs)
    }

    fun evaluate(
        video: PlaybackClockSnapshot,
        audio: PlaybackClockSnapshot,
        manualOffsetMs: Long = 0L
    ): SyncDecision {
        val drift = calculateDriftMs(video, audio, manualOffsetMs) ?: return SyncDecision.NoClock
        val abs = kotlin.math.abs(drift)
        return when {
            abs <= softThresholdMs -> SyncDecision.Stable(drift)
            abs < hardThresholdMs -> SyncDecision.SoftCorrect(drift)
            else -> SyncDecision.HardResync(drift)
        }
    }

    /**
     * Drift = audio clock minus video clock.
     * Positive manual offset means additional delay applied to audio, reducing effective drift.
     */
    fun calculateDriftMs(
        video: PlaybackClockSnapshot,
        audio: PlaybackClockSnapshot,
        manualOffsetMs: Long = 0L
    ): Long? {
        if (!video.available || !audio.available) return null
        val raw = when {
            video.playbackWallClockMs != null && audio.playbackWallClockMs != null ->
                audio.playbackWallClockMs - video.playbackWallClockMs
            video.isLive && audio.isLive &&
                video.liveOffsetMs != null && audio.liveOffsetMs != null ->
                video.liveOffsetMs - audio.liveOffsetMs
            !video.isLive && !audio.isLive ->
                audio.positionMs - video.positionMs
            else -> null
        }
        return raw?.minus(manualOffsetMs)
    }

    companion object {
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
