package com.streamvault.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Optional second Media3 player used only for external audio.
 *
 * The primary player is never replaced or reconfigured. The secondary player
 * receives the external stream with video tracks disabled; this keeps the existing
 * primary playback path intact while avoiding a second rendered video.
 */
class ExternalAudioController(context: Context) {
    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
    private val dataSourceFactory = DefaultDataSource.Factory(
        context.applicationContext,
        httpDataSourceFactory
    )
    private val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
    private val player = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(mediaSourceFactory)
        .build().apply {
        trackSelectionParameters = trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
        volume = 1f
        playWhenReady = true
    }

    private var syncJob: Job? = null
    private var offsetMs: Long = 0L

    fun start(
        url: String,
        primaryPositionMs: () -> Long,
        primaryIsPlaying: () -> Boolean,
        scope: CoroutineScope,
        headers: Map<String, String> = emptyMap()
    ) {
        stop()
        if (headers.isNotEmpty()) {
            httpDataSourceFactory.setDefaultRequestProperties(headers)
        }
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = primaryIsPlaying()
        syncJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                if (!player.isPlaying && primaryIsPlaying()) player.play()
                if (player.isPlaying && !primaryIsPlaying()) player.pause()
                val target = primaryPositionMs() + offsetMs
                val current = player.currentPosition
                if (target >= 0L && kotlin.math.abs(current - target) > 350L) {
                    player.seekTo(target.coerceAtLeast(0L))
                }
            }
        }
    }

    fun setOffsetMs(value: Long) {
        offsetMs = value.coerceIn(-10_000L, 10_000L)
    }

    fun stop() {
        syncJob?.cancel()
        syncJob = null
        player.stop()
        player.clearMediaItems()
    }

    fun isActive(): Boolean = player.mediaItemCount > 0

    fun release() {
        stop()
        player.release()
    }
}
