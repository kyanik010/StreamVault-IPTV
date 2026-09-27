package com.streamvault.feature.playback.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary

@Composable
internal fun AudioSourceOverlay(
    state: AudioSourceUiState,
    onSelect: (com.streamvault.domain.model.Channel) -> Unit,
    onSelectProvider: (Long) -> Unit,
    onAddAudioAccount: (String, String, String, String) -> Unit,
    onSync: () -> Unit,
    onOffsetMinus: () -> Unit,
    onOffsetPlus: () -> Unit,
    onResetSync: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxHeight(0.86f).widthIn(min = 420.dp, max = 720.dp),
            shape = RoundedCornerShape(18.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xFF0C1624))
        ) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Audio Source", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(
                    if (state.selectedChannelId == null) "Select an audio channel" else "Audio source active",
                    style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvClickableSurface(
                        onClick = onRemove,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.08f))
                    ) { Text("None / Stop", color = Color.White, modifier = Modifier.padding(12.dp)) }
                    TvClickableSurface(
                        onClick = onDismiss,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.08f))
                    ) { Text("Close", color = Color.White, modifier = Modifier.padding(12.dp)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvClickableSurface(
                        onClick = onSync,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Primary.copy(alpha = 0.25f))
                    ) { Text("Sync", color = Color.White, modifier = Modifier.padding(12.dp)) }
                    TvClickableSurface(
                        onClick = onOffsetMinus,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.08f))
                    ) { Text("−50 ms", color = Color.White, modifier = Modifier.padding(12.dp)) }
                    TvClickableSurface(
                        onClick = onResetSync,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.08f))
                    ) { Text("Reset", color = Color.White, modifier = Modifier.padding(12.dp)) }
                    TvClickableSurface(
                        onClick = onOffsetPlus,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.08f))
                    ) { Text("+50 ms", color = Color.White, modifier = Modifier.padding(12.dp)) }
                }
                Text("Sync offset: ${state.manualOffsetMs} ms", color = Color.White)
                Slider(
                    value = state.manualOffsetMs.toFloat(),
                    onValueChange = { value ->
                        val target = value.toInt().toLong()
                        val delta = target - state.manualOffsetMs
                        if (delta > 0) repeat((delta / 50L).coerceAtMost(100L).toInt()) { onOffsetPlus() }
                        else if (delta < 0) repeat((-delta / 50L).coerceAtMost(100L).toInt()) { onOffsetMinus() }
                    },
                    valueRange = -5000f..5000f,
                    steps = 199
                )
                if (state.loading) {
                    Text("Loading audio channels…", color = Color.White)
                } else if (state.error != null && state.channels.isEmpty()) {
                    Text(state.error, color = Color.White)
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.channels, key = { it.id }) { channel ->
                            TvClickableSurface(
                                onClick = { onSelect(channel) },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    focusedContainerColor = Primary.copy(alpha = 0.35f)
                                )
                            ) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (state.selectedChannelId == channel.id) Text("✓", color = Primary)
                                    Text(channel.name, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}