package com.streamvault.feature.playback.player

import android.content.Context
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ExternalAudioSource
import com.streamvault.domain.repository.ChannelRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory view of the persisted External Audio library.
 *
 * The activation record supplies the managed M3U URL. The repository prepares
 * and persists that library in Room; this cache only exposes it to playback.
 */
@Singleton
class AudioSourceCatalogCache @Inject constructor(
    private val channelRepository: ChannelRepository,
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

        // The repository is the persistent source of truth. It synchronizes the
        // managed M3U when available and restores the existing Room library on restart.
        channelRepository.prepareExternalAudioLibraryFromM3u(providerId, managedM3uUrl)

        val prepared = channelRepository.getExternalAudioSources(providerId)
        val preparedByChannel = prepared.associateBy { it.channelId }
        preparedCache[providerId] = preparedByChannel

        val channels = prepared
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

        cache[providerId] = channels
        return channels
    }

    fun get(providerId: Long): List<Channel>? = cache[providerId]

    fun getPreparedSources(providerId: Long): List<ExternalAudioSource> =
        preparedCache[providerId]
            ?.values
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .orEmpty()

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

    private companion object {
        const val AUDIO_PREFS = "streamvault_audio_source"
        const val AUDIO_M3U_KEY = "m3u_url"
    }
}
