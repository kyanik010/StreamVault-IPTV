package com.streamvault.feature.playback.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

private data class ExternalAudioChannel(val id: String, val name: String)
private const val PREFS = "streamvault_external_audio"
private const val KEY_NAME = "account_name"
private const val KEY_SERVER = "server"
private const val KEY_USERNAME = "username"
private const val KEY_PASSWORD = "password"
private const val KEY_STREAM_ID = "stream_id"

@Composable
internal fun ExternalAudioDialog(
    active: Boolean,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onOffsetChanged: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, 0) }
    var accountName by remember { mutableStateOf(prefs.getString(KEY_NAME, "") ?: "") }
    var server by remember { mutableStateOf(prefs.getString(KEY_SERVER, "") ?: "") }
    var username by remember { mutableStateOf(prefs.getString(KEY_USERNAME, "") ?: "") }
    var password by remember { mutableStateOf(prefs.getString(KEY_PASSWORD, "") ?: "") }
    var channels by remember { mutableStateOf<List<ExternalAudioChannel>>(emptyList()) }
    var selectedChannel by remember { mutableStateOf<ExternalAudioChannel?>(null) }
    var offset by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var loginNonce by remember { mutableStateOf(0) }

    fun normalizedServer(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://" + trimmed
    }

    fun streamUrl(channel: ExternalAudioChannel): String =
        normalizedServer(server) + "/live/" + Uri.encode(username.trim()) + "/" +
            Uri.encode(password.trim()) + "/" + Uri.encode(channel.id) + ".ts"

    suspend fun loginAndLoadChannels() {
        busy = true
        status = "Connecting..."
        try {
            val base = normalizedServer(server)
            val loginUri = Uri.parse(base).buildUpon()
                .appendPath("player_api.php")
                .appendQueryParameter("username", username.trim())
                .appendQueryParameter("password", password.trim())
                .build()
            val listUri = loginUri.buildUpon().appendQueryParameter("action", "get_live_streams").build()
            val client = OkHttpClient()

            val loginResponse = withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(loginUri.toString()).build()).execute()
            }
            val loginCode = loginResponse.code
            val loginBody = loginResponse.use { it.body?.string().orEmpty() }
            if (!loginResponse.isSuccessful || loginBody.isBlank()) {
                error("Login request failed (" + loginCode + ")")
            }

            val loginJson = JSONObject(loginBody)
            val userInfo = loginJson.optJSONObject("user_info")
            if (userInfo != null && userInfo.has("auth") && !userInfo.optBoolean("auth", false)) {
                error("Xtream login failed.")
            }

            val listResponse = withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(listUri.toString()).build()).execute()
            }
            val listCode = listResponse.code
            val listBody = listResponse.use { it.body?.string().orEmpty() }
            if (!listResponse.isSuccessful || listBody.isBlank()) {
                error("Could not load audio channels (" + listCode + ").")
            }

            val array = JSONArray(listBody)
            channels = buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("stream_id").takeIf { it.isNotBlank() } ?: continue
                    val name = item.optString("name").ifBlank { "Channel " + id }
                    add(ExternalAudioChannel(id, name))
                }
            }
            if (channels.isEmpty()) error("No Live TV channels were returned.")

            prefs.edit()
                .putString(KEY_NAME, accountName.ifBlank { "Audio Account" })
                .putString(KEY_SERVER, normalizedServer(server))
                .putString(KEY_USERNAME, username.trim())
                .putString(KEY_PASSWORD, password.trim())
                .apply()

            selectedChannel = channels.firstOrNull { it.id == prefs.getString(KEY_STREAM_ID, "") } ?: channels.first()
            prefs.edit().putString(KEY_STREAM_ID, selectedChannel?.id).apply()
            status = "Audio account connected. " + channels.size + " channels loaded."
        } catch (t: Throwable) {
            status = t.message ?: "Audio account connection failed."
            channels = emptyList()
        } finally {
            busy = false
        }
    }

    LaunchedEffect(loginNonce) {
        if (loginNonce > 0) loginAndLoadChannels()
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.background(Color(0xFF10151D)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Audio Source", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text("Add Audio Account", color = Color.White)

            BasicTextField(value = accountName, onValueChange = { accountName = it },
                modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f)).padding(10.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White), singleLine = true)
            Text("Account Name", color = Color.LightGray)

            BasicTextField(value = server, onValueChange = { server = it },
                modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f)).padding(10.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White), singleLine = true)
            Text("Server URL", color = Color.LightGray)

            BasicTextField(value = username, onValueChange = { username = it },
                modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f)).padding(10.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White), singleLine = true)
            Text("Username", color = Color.LightGray)

            BasicTextField(value = password, onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f)).padding(10.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White), singleLine = true)
            Text("Password", color = Color.LightGray)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = !busy && server.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                    onClick = { loginNonce++ }
                ) { Text(if (busy) "Connecting..." else "Add & Login") }

                if (active) {
                    TextButton(onClick = onStop) { Text("Remove Audio") }
                } else if (selectedChannel != null) {
                    TextButton(onClick = { onStart(streamUrl(selectedChannel!!)) }) { Text("Reconnect Audio") }
                }

                TextButton(onClick = onDismiss) { Text("Close") }
            }

            Text("Audio Account", color = Color.White)
            if (channels.isNotEmpty()) {
                Text("Audio Channel", color = Color.White)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(channels) { channel ->
                        TextButton(onClick = {
                            selectedChannel = channel
                            prefs.edit().putString(KEY_STREAM_ID, channel.id).apply()
                            onStart(streamUrl(channel))
                        }) {
                            Text(
                                if (selectedChannel?.id == channel.id) "Selected: " + channel.name else channel.name,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            Text("Sync", color = Color.White)
            Text("Offset: " + offset + " ms", color = Color.LightGray)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    offset = (offset - 250L).coerceAtLeast(-10_000L)
                    onOffsetChanged(offset)
                }) { Text("− Offset") }
                TextButton(onClick = {
                    offset = 0L
                    onOffsetChanged(0L)
                }) { Text("Reset") }
                TextButton(onClick = {
                    offset = (offset + 250L).coerceAtMost(10_000L)
                    onOffsetChanged(offset)
                }) { Text("+ Offset") }
            }

            if (status.isNotBlank()) Text(status, color = Color.LightGray)
        }
    }
}
