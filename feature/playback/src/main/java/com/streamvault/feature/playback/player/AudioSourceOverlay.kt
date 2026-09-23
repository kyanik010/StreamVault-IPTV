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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.material3.OutlinedTextField
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
    onReconnect: () -> Unit,
    onOffsetMinus: () -> Unit,
    onOffsetPlus: () -> Unit,
    onResetSync: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    var showAddAccount by remember { mutableStateOf(state.providers.isEmpty()) }
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var accountName by remember { mutableStateOf("Audio Source") }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxHeight(0.86f).widthIn(min = 420.dp, max = 720.dp),
            shape = RoundedCornerShape(18.dp),
            colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = Color(0xFF0C1624))
        ) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Audio Source", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(
                    if (state.providerName.isBlank()) "Select an Xtream audio account" else state.providerName,
                    style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvClickableSurface(
                        onClick = { showAddAccount = !showAddAccount },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Primary.copy(alpha = 0.20f),
                            focusedContainerColor = Primary.copy(alpha = 0.35f)
                        )
                    ) {
                        Text(if (showAddAccount) "Cancel Add Account" else "Add Audio Account", color = Color.White, modifier = Modifier.padding(12.dp))
                    }
                }
                if (showAddAccount) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = accountName, onValueChange = { accountName = it }, label = { Text("Account Name") }, singleLine = true)
                        OutlinedTextField(value = serverUrl, onValueChange = { serverUrl = it }, label = { Text("Server URL (HTTP/HTTPS)") }, singleLine = true)
                        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true)
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true)
                        TvClickableSurface(
                            onClick = {
                                if (!state.addingAccount && serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                                    onAddAudioAccount(serverUrl, username, password, accountName)
                                }
                            },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Primary.copy(alpha = 0.35f))
                        ) {
                            Text(if (state.addingAccount) "Adding…" else "Add & Login", color = Color.White, modifier = Modifier.padding(12.dp))
                        }
                        Text("This account is used only as the audio source. Your current video account remains unchanged.", color = OnSurfaceDim)
                    }
                }
                if (state.providers.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.providers.forEach { provider ->
                            TvClickableSurface(
                                onClick = { onSelectProvider(provider.id) },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    focusedContainerColor = Primary.copy(alpha = 0.35f)
                                )
                            ) {
                                Text(
                                    if (provider.id == state.providerId) "✓ " + provider.name else provider.name,
                                    color = Color.White, modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Sync: " + state.syncState + "  Drift: " +
                            (state.driftMs?.let { it.toString() + " ms" } ?: "—") +
                            "  Offset: " + state.manualOffsetMs + " ms",
                        color = Color.White, modifier = Modifier.weight(1f)
                    )
                    TvClickableSurface(
                        onClick = onSync,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.08f))
                    ) { Text("Sync", color = Color.White, modifier = Modifier.padding(12.dp)) }
                    TvClickableSurface(
                        onClick = onReconnect,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.08f))
                    ) { Text("Reconnect Audio", color = Color.White, modifier = Modifier.padding(12.dp)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvClickableSurface(
                        onClick = onOffsetMinus,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.08f))
                    ) { Text("−", color = Color.White, modifier = Modifier.padding(12.dp)) }
                    TvClickableSurface(
                        onClick = onResetSync,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.08f))
                    ) { Text("Reset", color = Color.White, modifier = Modifier.padding(12.dp)) }
                    TvClickableSurface(
                        onClick = onOffsetPlus,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.08f))
                    ) { Text("+", color = Color.White, modifier = Modifier.padding(12.dp)) }
                }
                if (state.loading) {
                    Text("Loading live channels…", color = Color.White)
                } else if (state.error != null && state.channels.isEmpty()) {
                    Text(state.error, color = Color.White)
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            TvClickableSurface(
                                onClick = onRemove,
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.08f))
                            ) { Text("None", color = Color.White, modifier = Modifier.padding(12.dp)) }
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
                                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (state.selectedChannelId == channel.id) Text("✓", color = Primary)
                                    Text(channel.name, color = Color.White)
                                }
                            }
                        }
                    }
                }
                TvClickableSurface(
                    onClick = onDismiss,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.08f))
                ) { Text("Close", color = Color.White, modifier = Modifier.padding(12.dp)) }
            }
        }
    }
}
