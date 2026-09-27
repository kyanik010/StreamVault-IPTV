package com.streamvault.feature.playback.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

data class AudioM3uChannel(val name: String, val url: String, val logo: String? = null, val group: String? = null)

@OptIn(UnstableApi::class)
@Singleton
class AudioSourceManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("streamvault_audio_source", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private val cacheFile by lazy { File(context.filesDir, "audio_channels.cache") }

    private val _channels = MutableStateFlow<List<AudioM3uChannel>>(emptyList())
    val channels: StateFlow<List<AudioM3uChannel>> = _channels.asStateFlow()
    private val _selected = MutableStateFlow<AudioM3uChannel?>(null)
    val selected: StateFlow<AudioM3uChannel?> = _selected.asStateFlow()
    private val _url = MutableStateFlow(prefs.getString("m3u_url", "") ?: "")
    val url: StateFlow<String> = _url.asStateFlow()
    private val _syncMs = MutableStateFlow(prefs.getInt("sync_ms", 0))
    val syncMs: StateFlow<Int> = _syncMs.asStateFlow()

    init {
        val savedUrl = _url.value
        if (savedUrl.isNotBlank()) scope.launch { if (!loadCachedChannels(savedUrl)) loadPlaylist(savedUrl) }
    }

    suspend fun loadPlaylistIfChanged(rawUrl: String): Result<Int> = withContext(Dispatchers.IO) {
        val normalized = rawUrl.trim()
        require(normalized.isNotEmpty()) { "M3U URL is empty" }
        if (_url.value.trim() == normalized && loadCachedChannels(normalized)) Result.success(_channels.value.size)
        else loadPlaylist(normalized)
    }

    suspend fun loadPlaylist(rawUrl: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = rawUrl.trim()
            require(normalized.isNotEmpty()) { "M3U URL is empty" }
            val connection = URL(normalized).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "StreamVault/Audio")
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val parsed = parseM3u(text)
                require(parsed.isNotEmpty()) { "No audio channels found in M3U" }
                _url.value = normalized
                prefs.edit().putString("m3u_url", normalized).apply()
                _channels.value = parsed
                saveCachedChannels(normalized, parsed)
                restoreSelected()
                parsed.size
            } finally { connection.disconnect() }
        }
    }

    private suspend fun loadCachedChannels(expectedUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (!cacheFile.exists()) return@runCatching false
            DataInputStream(BufferedInputStream(cacheFile.inputStream(), 64 * 1024)).use { input ->
                if (input.readInt() != 1 || input.readUTF() != expectedUrl) return@runCatching false
                val count = input.readInt()
                if (count < 0 || count > 2_000_000) return@runCatching false
                val parsed = ArrayList<AudioM3uChannel>(count)
                repeat(count) {
                    parsed += AudioM3uChannel(input.readUTF(), input.readUTF(),
                        input.readUTF().takeIf(String::isNotEmpty),
                        input.readUTF().takeIf(String::isNotEmpty))
                }
                _url.value = expectedUrl
                _channels.value = parsed
                restoreSelected()
                true
            }
        }.getOrDefault(false)
    }

    private suspend fun saveCachedChannels(url: String, channels: List<AudioM3uChannel>) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 64 * 1024)).use { output ->
                output.writeInt(1); output.writeUTF(url); output.writeInt(channels.size)
                channels.forEach {
                    output.writeUTF(it.name); output.writeUTF(it.url)
                    output.writeUTF(it.logo.orEmpty()); output.writeUTF(it.group.orEmpty())
                }
            }
            if (!tmp.renameTo(cacheFile)) { cacheFile.delete(); tmp.renameTo(cacheFile) }
        }
    }

    fun restoreSelected() {
        val saved = prefs.getString("selected_url", null) ?: return
        _channels.value.firstOrNull { it.url == saved }?.let { _selected.value = it }
    }

    fun play(channel: AudioM3uChannel) {
        val selector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
        mainHandler.post {
            player?.release()
            val http = DefaultHttpDataSource.Factory().setUserAgent("StreamVault/Audio")
                .setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000)
            player = ExoPlayer.Builder(context).setTrackSelector(selector)
                .setMediaSourceFactory(DefaultMediaSourceFactory(http)).build().also {
                    it.setMediaItem(MediaItem.fromUri(channel.url)); it.prepare(); it.playWhenReady = true
                }
        }
        _selected.value = channel
        prefs.edit().putString("selected_url", channel.url).apply()
    }

    fun stop() {
        mainHandler.post { player?.stop(); player?.release(); player = null }
        _selected.value = null
        prefs.edit().remove("selected_url").apply()
    }

    fun setSyncMs(value: Int) {
        val clamped = value.coerceIn(-5000, 5000)
        _syncMs.value = clamped
        prefs.edit().putInt("sync_ms", clamped).apply()
    }

    fun syncToVideo(videoPositionMs: Long) {
        val target = (videoPositionMs - _syncMs.value).coerceAtLeast(0L)
        mainHandler.post { player?.seekTo(target) }
    }

    fun seekRelative(deltaMs: Long) {
        mainHandler.post { player?.let { it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0L)) } }
    }

    private fun parseM3u(text: String): List<AudioM3uChannel> {
        val result = mutableListOf<AudioM3uChannel>()
        var name: String? = null; var logo: String? = null; var group: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", true) -> {
                    name = line.substringAfterLast(',').trim().ifBlank { "Audio" }
                    logo = Regex("""tvg-logo=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1)
                    group = Regex("""group-title=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1)
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    name?.let { result += AudioM3uChannel(it, line, logo, group) }
                    name = null; logo = null; group = null
                }
            }
        }
        return result
    }
}