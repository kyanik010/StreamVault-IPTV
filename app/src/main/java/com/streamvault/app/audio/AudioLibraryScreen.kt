package com.streamvault.app.audio

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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AudioLibraryScreen(
    modifier: Modifier = Modifier,
    viewModel: AudioLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val player = remember(context) { AudioOnlyPlayerController(context) }
    val selectedId by player.selectedId.collectAsStateWithLifecycle()
    val isPlaying by player.isPlaying.collectAsStateWithLifecycle()
    val playbackError by player.error.collectAsStateWithLifecycle()

    DisposableEffect(player) {
        onDispose { player.release() }
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
            text = "مكتبة صوتية من نفس اشتراك IPTV — تشغيل صوت فقط",
            style = MaterialTheme.typography.bodyMedium
        )

        playbackError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (selectedId != null) {
            AudioNowPlayingBar(
                title = state.sources.firstOrNull { it.channelId == selectedId }?.name ?: "Audio",
                playing = isPlaying,
                onPlayPause = {
                    state.sources.firstOrNull { it.channelId == selectedId }?.let(player::toggle)
                },
                onStop = { player.stop() }
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
                        val selected = selectedId == source.channelId
                        AudioLibraryRow(
                            name = source.name,
                            groupTitle = source.groupTitle,
                            selected = selected,
                            playing = selected && isPlaying,
                            onClick = { player.play(source) },
                            onPlayPause = { player.toggle(source) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioNowPlayingBar(
    title: String,
    playing: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.MusicNote, contentDescription = null)
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                style = MaterialTheme.typography.titleMedium
            )
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null
                )
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Stop, contentDescription = null)
            }
        }
    }
}

@Composable
private fun AudioLibraryRow(
    name: String,
    groupTitle: String?,
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
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
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
                Text(name, style = MaterialTheme.typography.titleMedium)
                groupTitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                if (selected) {
                    Text(
                        if (playing) "يعمل صوت فقط" else "متوقف",
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
