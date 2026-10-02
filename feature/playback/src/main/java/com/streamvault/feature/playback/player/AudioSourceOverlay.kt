package com.streamvault.feature.playback.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    onSync: () -> Unit,
    onOffsetMinus: () -> Unit,
    onOffsetPlus: () -> Unit,
    onResetSync: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredChannels = remember(state.channels, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) state.channels
        else state.channels.filter { channel ->
            channel.name.contains(query, ignoreCase = true) ||
                channel.groupTitle.orEmpty().contains(query, ignoreCase = true)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxHeight(0.86f).widthIn(min = 420.dp, max = 720.dp),
            shape = RoundedCornerShape(18.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xFF0C1624))
        ) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Audio Source", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(
                    "Choose IPTV audio channel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )
                Text(
                    if (state.selectedChannelId == null) {
                        "Use a different IPTV channel for audio while keeping this video"
                    } else {
                        "External audio is active. Video remains on the current channel."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    decorationBox = { innerTextField ->
                        if (searchQuery.isBlank()) {
                            Text("Search IPTV channels", color = OnSurfaceDim)
                        }
                        innerTextField()
                    }
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
                if (state.error != null && state.channels.isEmpty()) {
                    Text(state.error, color = Color.White)
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filteredChannels, key = { it.id }) { channel ->
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
                                    Column(Modifier.weight(1f)) {
                                        Text(channel.name, color = Color.White)
                                        channel.groupTitle?.takeIf { it.isNotBlank() }?.let { group ->
                                            Text(group, color = OnSurfaceDim, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}