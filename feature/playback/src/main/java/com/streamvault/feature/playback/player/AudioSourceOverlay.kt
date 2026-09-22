package com.streamvault.feature.playback.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight(0.82f)
                .widthIn(min = 360.dp, max = 620.dp),
            shape = RoundedCornerShape(18.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = Color(0xFF0C1624)
            )
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Audio Source", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(
                    text = if (state.providerName.isBlank()) "Xtream audio account" else state.providerName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )
                if (state.loading) {
                    Text("Loading live channels…", color = Color.White)
                } else if (state.error != null && state.channels.isEmpty()) {
                    Text(state.error, color = Color.White)
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            TvClickableSurface(
                                onClick = onRemove,
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    focusedContainerColor = Primary.copy(alpha = 0.35f)
                                )
                            ) {
                                Text(
                                    "None",
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                )
                            }
                        }
                        items(state.channels, key = { it.id }) { channel ->
                            TvClickableSurface(
                                onClick = { onSelect(channel) },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    focusedContainerColor = Primary.copy(alpha = 0.35f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (state.selectedChannelId == channel.id) {
                                        Text("✓", color = Primary)
                                    }
                                    Text(channel.name, color = Color.White)
                                }
                            }
                        }
                    }
                }
                TvClickableSurface(
                    onClick = onDismiss,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.08f),
                        focusedContainerColor = Primary.copy(alpha = 0.35f)
                    )
                ) {
                    Text(
                        "Close",
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}
