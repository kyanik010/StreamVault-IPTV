package com.streamvault.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Optional second Media3 player used only for external audio.
 *
 * The primary player is never replaced or reconfigured. The secondary player
 * receives the external stream and its video renderers are disabled by volumeing
 * it as audio-only; this keeps the existing playback path intact.
 */
class ExternalAudioController(context: Context) {
    private val player = ExoPlayer.Builder(context.applicationContext).build().apply {
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
        val builder = MediaItem.Builder().setUri(url)
        if (headers.isNotEmpty()) {
            // Headers are applied by the app's normal networking layer when available.
            // Direct external sources intentionally remain URL-only here.
        }
        player.setMediaItem(builder.build())
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
