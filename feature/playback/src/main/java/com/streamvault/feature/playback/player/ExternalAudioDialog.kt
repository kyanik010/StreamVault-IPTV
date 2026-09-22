package com.streamvault.feature.playback.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
internal fun ExternalAudioDialog(
    active: Boolean,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onOffsetChanged: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf("") }
    var offset by remember { mutableStateOf(0L) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF10151D))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("External Audio", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                "Play audio from a second HTTP/HTTPS/HLS stream while keeping the current video.",
                color = Color.LightGray
            )
            BasicTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(12.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                singleLine = true
            )
            Text(
                "Sync offset: ${offset} ms",
                color = Color.White
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    offset = (offset - 250L).coerceAtLeast(-10_000L)
                    onOffsetChanged(offset)
                }) { Text("-250 ms") }
                TextButton(onClick = {
                    offset = (offset + 250L).coerceAtMost(10_000L)
                    onOffsetChanged(offset)
                }) { Text("+250 ms") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                if (active) {
                    TextButton(onClick = onStop) { Text("Stop") }
                }
                TextButton(
                    onClick = { if (url.isNotBlank()) onStart(url.trim()) }
                ) { Text("Start") }
            }
        }
    }
}
