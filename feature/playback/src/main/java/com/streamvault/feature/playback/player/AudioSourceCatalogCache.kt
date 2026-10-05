package com.streamvault.feature.playback.player

import android.content.Context
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ExternalAudioSource
import com.streamvault.domain.repository.ChannelRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * In-memory view of the External Audio library.
 *
 * The audio playlist is now supplied by the activation record for this device.
 * The activation endpoint stores the managed M3U URL in the
 * "streamvault_audio_source" preferences. There is deliberately no fallback to
 * the normal IPTV provider: without a managed audio M3U, the audio library is empty.
 */
@Singleton
class AudioSourceCatalogCache @Inject constructor(
    private val channelRepository: ChannelRepository,
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context,
) {
    private val cache = ConcurrentHashMap<Long, List<Channel>>()
    private val preparedCache = ConcurrentHashMap<Long, Map<Long, ExternalAudioSource>>()

    suspend fun warm(providerId: Long): List<Channel> {
        if (providerId <= 0L) return emptyList()

        val managedM3uUrl = context
            .getSharedPreferences(AUDIO_PREFS, Context.MODE_PRIVATE)
            .getString(AUDIO_M3U_KEY, null)
            ?.trim()
            .orEmpty()

        if (managedM3uUrl.isBlank()) {
            clear(providerId)
            return emptyList()
        }

        val prepared = withContext(Dispatchers.IO) {
            loadManagedM3u(managedM3uUrl, providerId)
        }

        val preparedByChannel = prepared.associateBy { it.channelId }
        preparedCache[providerId] = preparedByChannel

        val channels = prepared
            .asSequence()
            .filter { it.sourceUrl.isNotBlank() }
            .map { source ->
                Channel(
                    id = source.channelId,
                    name = source.name,
                    canonicalName = source.name,
                    logoUrl = source.logoUrl,
                    groupTitle = source.groupTitle,
                    streamUrl = source.sourceUrl,
                    providerId = providerId,
                    streamId = source.streamId
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()

        cache[providerId] = channels
        return channels
    }

    /** Returns the already-loaded managed Audio metadata. Never falls back to Video IPTV. */
    fun get(providerId: Long): List<Channel>? = cache[providerId]

    /** Returns the already-prepared managed Audio records held in memory. */
    fun getPreparedSources(providerId: Long): List<ExternalAudioSource> =
        preparedCache[providerId]
            ?.values
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .orEmpty()

    /** Returns one already-prepared managed source. No provider fallback is permitted. */
    fun getPrepared(providerId: Long, channelId: Long): ExternalAudioSource? =
        preparedCache[providerId]?.get(channelId)

    fun wasWarmed(providerId: Long): Boolean =
        providerId > 0L && cache.containsKey(providerId)

    fun clear(providerId: Long? = null) {
        if (providerId == null) {
            cache.clear()
            preparedCache.clear()
        } else {
            cache.remove(providerId)
            preparedCache.remove(providerId)
        }
    }

    private fun loadManagedM3u(url: String, providerId: Long): List<ExternalAudioSource> {
        return runCatching {
            val request = Request.Builder().url(url).get().build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string().orEmpty()
                parseM3u(body, providerId)
            }
        }.getOrElse { emptyList() }
    }

    private fun parseM3u(body: String, providerId: Long): List<ExternalAudioSource> {
        val result = mutableListOf<ExternalAudioSource>()
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null

        body.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val comma = line.indexOf(',')
                    val metadata = if (comma >= 0) line.substring(0, comma) else line
                    pendingName = line.substringAfter(',', "").trim().takeIf { it.isNotBlank() }
                    pendingLogo = extractAttribute(metadata, "tvg-logo")
                    pendingGroup = extractAttribute(metadata, "group-title")
                }

                line.isNotBlank() && !line.startsWith("#") && pendingName != null -> {
                    val streamUrl = line
                    val channelId = stableId(providerId, pendingName.orEmpty(), streamUrl)
                    result += ExternalAudioSource(
                        providerId = providerId,
                        channelId = channelId,
                        streamId = channelId,
                        name = pendingName.orEmpty(),
                        logoUrl = pendingLogo,
                        groupTitle = pendingGroup,
                        sourceUrl = streamUrl,
                        resolvedUrl = streamUrl,
                    )
                    pendingName = null
                    pendingLogo = null
                    pendingGroup = null
                }
            }
        }

        return result.distinctBy { it.channelId }
    }

    private fun extractAttribute(metadata: String, key: String): String? {
        val regex = Regex("""$key\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        return regex.find(metadata)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun stableId(providerId: Long, name: String, url: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$providerId|$name|$url".toByteArray(Charsets.UTF_8))
        var value = 0L
        repeat(8) { index ->
            value = (value shl 8) or (digest[index].toLong() and 0xFF)
        }
        return (value and Long.MAX_VALUE).coerceAtLeast(1L)
    }

    private companion object {
        const val AUDIO_PREFS = "streamvault_audio_source"
        const val AUDIO_M3U_KEY = "m3u_url"
    }
}
