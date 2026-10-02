package com.streamvault.app.audio

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.streamvault.domain.model.ExternalAudioSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dedicated player for the Audio library.
 *
 * This player is intentionally separate from the video/player screen:
 * - no video surface is attached
 * - video and subtitle tracks are disabled
 * - each selected source gets its own HTTP request configuration
 * - the player never touches the main video player
 */
@OptIn(UnstableApi::class)
class AudioOnlyPlayerController(
    private val context: Context,
) {
    private var player: ExoPlayer? = null
    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId: StateFlow<Long?> = _selectedId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun play(source: ExternalAudioSource) {
        val url = source.resolvedUrl
            .takeIf { it.isNotBlank() && !source.isExpired() }
            ?: source.sourceUrl
        if (url.isBlank()) {
            _error.value = "مصدر الصوت غير جاهز. أعد مزامنة الاشتراك."
            _isPlaying.value = false
            return
        }

        stop(clearSelection = false)
        _error.value = null

        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(source.headers)
        source.userAgent
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(httpFactory::setUserAgent)

        val selector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)
        val mediaItem = MediaItem.Builder()
            .setMediaId(source.channelId.toString())
            .setUri(url)
            .setTag(source)
            .build()

        val newPlayer = runCatching {
            ExoPlayer.Builder(context)
                .setTrackSelector(selector)
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
        }.getOrElse {
            _error.value = it.message ?: "تعذر إنشاء مشغل الصوت."
            return
        }

        newPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val hasAudio = newPlayer.currentTracks.groups.any { group ->
                        group.type == C.TRACK_TYPE_AUDIO &&
                            (0 until group.length).any(group::isTrackSelected)
                    }
                    if (!hasAudio) {
                        _isPlaying.value = false
                        _error.value = "المصدر المحدد لا يحتوي على مسار صوتي قابل للتشغيل."
                        newPlayer.stop()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _isPlaying.value = false
                _error.value = error.message ?: "تعذر تشغيل مصدر الصوت."
            }
        })

        player = newPlayer
        _selectedId.value = source.channelId

        runCatching {
            newPlayer.setMediaItem(mediaItem)
            newPlayer.prepare()
            newPlayer.play()
        }.onFailure {
            _isPlaying.value = false
            _error.value = it.message ?: "تعذر تشغيل مصدر الصوت."
            newPlayer.release()
            player = null
        }
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun toggle(source: ExternalAudioSource) {
        if (_selectedId.value == source.channelId && player != null) {
            if (_isPlaying.value) pause() else resume()
        } else {
            play(source)
        }
    }

    fun stop(clearSelection: Boolean = true) {
        player?.runCatching {
            stop()
            release()
        }
        player = null
        _isPlaying.value = false
        if (clearSelection) _selectedId.value = null
    }

    fun release() {
        stop()
    }
}
