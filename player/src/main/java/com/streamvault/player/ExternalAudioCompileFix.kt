package com.streamvault.player

import androidx.media3.common.PlaybackException
import com.streamvault.domain.model.StreamInfo
import com.streamvault.player.playback.PlaybackPreparationPlan
import com.streamvault.player.playback.PlayerRetryPolicy
import com.streamvault.player.playback.buildPlaybackPreparationPlan as buildPlaybackPreparationPlanBase

/** Shared symbols used by the file-local external-audio controller. */
internal const val TAG = "Media3PlayerEngine"

internal fun buildPlaybackPreparationPlan(
    streamInfo: StreamInfo,
    preload: Boolean,
    fastRetryOnTransientFailures: () -> Boolean = { false },
    playbackStarted: () -> Boolean
): PlaybackPreparationPlan = buildPlaybackPreparationPlanBase(
    streamInfo = streamInfo,
    preload = preload,
    fastRetryOnTransientFailures = fastRetryOnTransientFailures,
    playbackStarted = playbackStarted
)

/** Keeps Throwable cause chains type-safe with Media3's PlaybackException hierarchy. */
internal fun generateSequence(
    seed: PlaybackException,
    nextFunction: (PlaybackException) -> Throwable?
): Sequence<Throwable> = kotlin.sequences.generateSequence<Throwable>(seed) { throwable ->
    nextFunction(throwable as PlaybackException)
}
