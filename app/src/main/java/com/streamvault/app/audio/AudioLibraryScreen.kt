package com.streamvault.app.audio

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.streamvault.domain.model.ExternalAudioSource

@OptIn(UnstableApi::class)
@Composable
fun AudioLibraryScreen(
    modifier: Modifier = Modifier,
    viewModel: AudioLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }

    val httpFactory = remember(context) {
        DefaultHttpDataSource.Factory()
            .setUserAgent("StreamVault/Audio")
    }

    val player = remember(context, httpFactory) {
        val selector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            )
        }
        ExoPlayer.Builder(context)
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(DefaultLoadControl())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .build()
            .also { exo ->
                exo.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        selectedId = mediaItem?.mediaId?.toLongOrNull()
                        playbackError = null
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        isPlaying = false
                        playbackError = error.message ?: "تعذر تشغيل مصدر الصوت."
                    }
                })
            }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    fun play(source: ExternalAudioSource) {
        playbackError = null
        val playbackUrl = if (!source.isExpired() && source.resolvedUrl.isNotBlank()) {
            source.resolvedUrl
        } else {
            source.sourceUrl
        }
        if (playbackUrl.isBlank()) {
            playbackError = "رابط مصدر الصوت غير صالح."
            return
        }

        httpFactory.setDefaultRequestProperties(source.headers)
        source.userAgent?.takeIf { it.isNotBlank() }?.let(httpFactory::setUserAgent)

        val mediaItem = MediaItem.Builder()
            .setMediaId(source.channelId.toString())
            .setUri(playbackUrl)
            .setTag(source)
            .build()

        runCatching {
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
            selectedId = source.channelId
        }.onFailure { error ->
            isPlaying = false
            playbackError = error.message ?: "تعذر تشغيل مصدر الصوت."
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Audio",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "مكتبة صوتية جاهزة من نفس اشتراك IPTV",
            style = MaterialTheme.typography.bodyMedium
        )

        playbackError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        when {
            state.loading -> Text("جاري قراءة مكتبة Audio...")
            state.sources.isEmpty() -> {
                Text(
                    text = state.error ?: "لا توجد مصادر صوتية جاهزة.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = state.sources,
                        key = { it.channelId }
                    ) { source ->
                        AudioLibraryRow(
                            source = source,
                            selected = selectedId == source.channelId,
                            playing = selectedId == source.channelId && isPlaying,
                            onClick = { play(source) },
                            onPlayPause = {
                                if (selectedId == source.channelId && isPlaying) {
                                    player.pause()
                                } else {
                                    play(source)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioLibraryRow(
    source: ExternalAudioSource,
    selected: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleMedium
                )
                source.groupTitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (selected) {
                    Text(
                        text = if (playing) "يعمل صوت فقط" else "متوقف",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null
                )
            }
        }
    }
}
